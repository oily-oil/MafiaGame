import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.JOptionPane;

public class Server {

    private static Set<ClientHandler> clientHandlers = new HashSet<>();

    private enum GamePhase { WAITING, DAY, NIGHT }
    private static GamePhase currentPhase = GamePhase.WAITING;

    private static Timer gameTimer = new Timer();
    private static final long PHASE_TIME_MS = 60000; // 낮, 밤 시간

    private static AtomicInteger playerCounter = new AtomicInteger(1);

    private static Map<ClientHandler, ClientHandler> votes = new HashMap<>();

    // 밤 능력 대상자들
    private static ClientHandler nightKillTarget = null;
    private static ClientHandler nightSaveTarget = null;
    private static ClientHandler nightInvestigateUser = null;

    // 역할 및 상태 Enum 정의 (기존 코드에 없었으므로 추가)
    private enum Role { NONE, MAFIA, CITIZEN, POLICE, DOCTOR }
    private enum PlayerStatus { ALIVE, DEAD }


    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ServerGUI serverGUI = new ServerGUI();

            // GUI의 시작 버튼에 리스너 연결
            serverGUI.getStartButton().addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        int port = serverGUI.getPortNumber();
                        startServerLogic(port);
                        serverGUI.getStartButton().setEnabled(false); // 서버 시작 후 버튼 비활성화
                        serverGUI.setTitle("Mafia Game Server (Running on Port " + port + ")");
                    } catch (IOException ex) {
                        System.err.println("서버 시작 실패: " + ex.getMessage());
                        serverGUI.getStartButton().setEnabled(true);
                        JOptionPane.showMessageDialog(serverGUI, "서버 시작 실패: " + ex.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
                    }
                }
            });
        });
    }

    private static void startServerLogic(int port) throws IOException {
        System.out.println("게임 서버가 시작되었습니다. (Port: " + port + ")");
        ExecutorService pool = Executors.newFixedThreadPool(10);

        // 새로운 스레드에서 서버 리스너를 실행하여 GUI 스레드가 블록되지 않도록 함
        new Thread(() -> {
            try (ServerSocket listener = new ServerSocket(port)) {
                while (true) {
                    pool.execute(new ClientHandler(listener.accept()));
                }
            } catch (IOException e) {
                System.err.println("서버 리스너 오류: " + e.getMessage());
            }
        }).start();
    }


    //게임 시작 함수
    public static synchronized void startGame() {
        if (currentPhase != GamePhase.WAITING) return;

        // 플레이어 수 제한
        if (clientHandlers.size() < 4) {
            broadcast("SYSTEM:게임 시작을 위해 4명 이상의 플레이어가 필요합니다.");
            return;
        }

        // 초기화
        nightKillTarget = null;
        nightSaveTarget = null;
        nightInvestigateUser = null;
        votes.clear();

        broadcast("START_GAME");

        List<ClientHandler> handlersList = new ArrayList<>(clientHandlers);
        Collections.shuffle(handlersList);

        int numPlayers = handlersList.size();

        int numMafias = (numPlayers >= 6) ? 2 : 1;
        int numPolice = 1;
        int numDoctors = 1;

        int currentIndex = 0;

        // 1. 마피아 배정
        System.out.println("--- 직업 배정 시작 ---");
        for (int i = 0; i < numMafias; i++) {
            ClientHandler handler = handlersList.get(currentIndex);
            handler.role = Role.MAFIA;
            handler.sendMessage("ROLE:MAFIA");
            System.out.println("마피아: P" + handler.playerNumber + " (" + handler.name + ")");
            currentIndex++;
        }

        // 2. 경찰 배정
        ClientHandler police = handlersList.get(currentIndex);
        police.role = Role.POLICE;
        police.sendMessage("ROLE:POLICE");
        System.out.println("경찰: P" + police.playerNumber + " (" + police.name + ")");
        currentIndex++;

        // 3. 의사 배정
        ClientHandler doctor = handlersList.get(currentIndex);
        doctor.role = Role.DOCTOR;
        doctor.sendMessage("ROLE:DOCTOR");
        System.out.println("의사: P" + doctor.playerNumber + " (" + doctor.name + ")");
        currentIndex++;

        // 4. 나머지 시민 배정
        while (currentIndex < numPlayers) {
            ClientHandler handler = handlersList.get(currentIndex);
            handler.role = Role.CITIZEN;
            handler.sendMessage("ROLE:CITIZEN");
            currentIndex++;
        }
        System.out.println("--- 직업 배정 완료 ---");

        // 게임을 밤 상태로 시작
        currentPhase = GamePhase.NIGHT;
        broadcast("SYSTEM:밤이 되었습니다. 능력을 사용할 대상을 지목하세요.");
        broadcastPlayerList(); // 역할 배정 후 목록 업데이트
        scheduleDayNightTimer();
    }

    private static void scheduleDayNightTimer() {
        gameTimer.cancel(); // 기존 타이머 취소
        gameTimer = new Timer(); // 새 타이머 생성

        gameTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (clientHandlers) {
                    if (currentPhase == GamePhase.WAITING) {
                        return;
                    }

                    if (currentPhase == GamePhase.DAY) {
                        tallyVotes();
                        if (currentPhase == GamePhase.WAITING) {
                            return;
                        }

                        currentPhase = GamePhase.NIGHT;
                        nightKillTarget = null;
                        nightSaveTarget = null;
                        nightInvestigateUser = null;
                        broadcast("SYSTEM:밤이 되었습니다. 능력을 사용할 대상을 지목하세요.");

                    } else if (currentPhase == GamePhase.NIGHT) {
                        currentPhase = GamePhase.DAY;

                        // 능력로직
                        if (nightKillTarget != null) {
                            if (nightKillTarget != nightSaveTarget) {
                                nightKillTarget.status = PlayerStatus.DEAD;
                                broadcast("SYSTEM:지난 밤, " + nightKillTarget.name + "(P" + nightKillTarget.playerNumber + ") 님이 마피아에게 살해당했습니다.");
                                nightKillTarget.sendMessage("YOU_DIED");
                            } else {
                                broadcast("SYSTEM:지난 밤, 의사의 활약으로 누군가가 기적적으로 살아났습니다!");
                            }
                        } else {
                            broadcast("SYSTEM:지난 밤, 아무 일도 일어나지 않았습니다.");
                        }

                        // 밤이 지난 후 게임 종료 확인
                        if (checkGameEnd()) {
                            return;
                        }

                        broadcast("SYSTEM:낮이 되었습니다. 토론 및 투표를 시작하세요. (/vote 번호)");
                        votes.clear();
                        broadcastPlayerList(); // 사망자 발생 시 목록 업데이트
                    }
                }
            }
        }, PHASE_TIME_MS, PHASE_TIME_MS);
    }

    // 투표 로직
    private static synchronized void tallyVotes() {
        Map<ClientHandler, Integer> voteTally = new HashMap<>();
        int livingPlayers = 0;

        synchronized (clientHandlers) {
            for (ClientHandler h : clientHandlers) {
                if (h.status == PlayerStatus.ALIVE) {
                    livingPlayers++;
                }
            }
            for (Map.Entry<ClientHandler, ClientHandler> entry : votes.entrySet()) {
                if (entry.getKey().status == PlayerStatus.ALIVE && entry.getValue().status == PlayerStatus.ALIVE) {
                    voteTally.put(entry.getValue(), voteTally.getOrDefault(entry.getValue(), 0) + 1);
                }
            }
        }

        if (voteTally.isEmpty()) {
            broadcast("SYSTEM:아무도 투표하지 않아 처형이 없습니다.");
            return;
        }

        int maxVotes = Collections.max(voteTally.values());
        List<ClientHandler> tiedPlayers = new ArrayList<>();
        for (Map.Entry<ClientHandler, Integer> entry : voteTally.entrySet()) {
            if (entry.getValue() == maxVotes) {
                tiedPlayers.add(entry.getKey());
            }
        }

        if (tiedPlayers.size() > 1) {
            broadcast("SYSTEM:동점표(" + maxVotes + "표)가 나와 투표가 무효 처리되었습니다.");
            return;
        }

        ClientHandler personToExecute = tiedPlayers.get(0);
        int majorityThreshold = (livingPlayers / 2) + 1;

        if (maxVotes >= majorityThreshold) {
            personToExecute.status = PlayerStatus.DEAD;
            broadcast("SYSTEM:투표 결과, " + personToExecute.name + "(P" + personToExecute.playerNumber + ") 님이 과반수(" + maxVotes + "표) 득표로 처형당했습니다.");
            personToExecute.sendMessage("YOU_DIED");
            checkGameEnd();
            broadcastPlayerList(); // 사망자 발생 시 목록 업데이트
        } else {
            broadcast("SYSTEM:투표가 과반수(" + majorityThreshold + "표)에 미치지 못해 (" + maxVotes + "표) 처형이 없습니다.");
        }
    }

    // 투표
    public static synchronized void handleVote(ClientHandler voter, String command) {
        try {
            int targetNumber = Integer.parseInt(command.substring(6).trim());

            if (voter.playerNumber == targetNumber) {
                voter.sendMessage("SYSTEM:자신에게 투표할 수 없습니다.");
                return;
            }
            ClientHandler target = getPlayerByNumber(targetNumber);

            if (target == null) {
                voter.sendMessage("SYSTEM:존재하지 않는 플레이어 번호입니다.");
            } else if (target.status == PlayerStatus.DEAD) {
                voter.sendMessage("SYSTEM:이미 죽은 플레이어에게 투표할 수 없습니다.");
            } else {
                votes.put(voter, target);
                voter.sendMessage("SYSTEM:P" + target.playerNumber + " (" + target.name + ") 님에게 투표했습니다.");
            }
        } catch (Exception e) {
            voter.sendMessage("SYSTEM:잘못된 명령어입니다. 예: /vote 2");
        }
    }

    // 마피아 능력 로직
    public static synchronized void handleKillCommand(ClientHandler mafia, String command) {
        if (currentPhase != GamePhase.NIGHT) {
            mafia.sendMessage("SYSTEM:낮에는 죽일 수 없습니다.");
            return;
        }

        try {
            int targetNumber = Integer.parseInt(command.substring(6).trim());
            ClientHandler target = getPlayerByNumber(targetNumber);

            if (target == null) {
                mafia.sendMessage("SYSTEM:존재하지 않는 플레이어 번호입니다.");
            } else if (target.status == PlayerStatus.DEAD) {
                mafia.sendMessage("SYSTEM:이미 죽은 플레이어입니다.");
            } else if (target.role == Role.MAFIA) {
                mafia.sendMessage("SYSTEM:동료 마피아를 죽일 수 없습니다.");
            } else {
                nightKillTarget = target;
                mafia.sendMessage("SYSTEM:P" + target.playerNumber + " (" + target.name + ") 님을 처형 대상으로 지목했습니다.");
            }
        } catch (Exception e) {
            mafia.sendMessage("SYSTEM:잘못된 명령어입니다. 예: /kill 2");
        }
    }

    // 경찰 능력 로직
    public static synchronized void handleInvestigate(ClientHandler police, String command) {
        if (currentPhase != GamePhase.NIGHT) {
            police.sendMessage("SYSTEM:낮에는 조사할 수 없습니다.");
            return;
        }

        if (nightInvestigateUser != null) {
            police.sendMessage("SYSTEM:당신은 이미 조사를 완료했습니다.");
            return;
        }

        try {
            int targetNumber = Integer.parseInt(command.substring(13).trim());
            ClientHandler target = getPlayerByNumber(targetNumber);

            if (target == null) {
                police.sendMessage("SYSTEM:존재하지 않는 플레이어 번호입니다.");
            } else if (target.status == PlayerStatus.DEAD) {
                police.sendMessage("SYSTEM:이미 죽은 플레이어입니다.");
            } else {
                if (target.role == Role.MAFIA) {
                    police.sendMessage("SYSTEM:[조사결과] P" + target.playerNumber + " 님은 [마피아] 입니다.");
                } else {
                    police.sendMessage("SYSTEM:[조사결과] P" + target.playerNumber + " 님은 [시민] 입니다.");
                }
                nightInvestigateUser = police;
            }
        } catch (Exception e) {
            police.sendMessage("SYSTEM:잘못된 명령어입니다. 예: /investigate 2");
        }
    }

    //의사 능력 로직
    public static synchronized void handleSave(ClientHandler doctor, String command) {
        if (currentPhase != GamePhase.NIGHT) {
            doctor.sendMessage("SYSTEM:낮에는 살릴 수 없습니다.");
            return;
        }

        try {
            int targetNumber = Integer.parseInt(command.substring(6).trim());
            ClientHandler target = getPlayerByNumber(targetNumber);

            if (target == null) {
                doctor.sendMessage("SYSTEM:존재하지 않는 플레이어 번호입니다.");
            } else if (target.status == PlayerStatus.DEAD) {
                doctor.sendMessage("SYSTEM:이미 죽은 플레이어입니다.");
            } else {
                nightSaveTarget = target;
                doctor.sendMessage("SYSTEM:P" + target.playerNumber + " (" + target.name + ") 님을 살리기로 결정했습니다.");
            }
        } catch (Exception e) {
            doctor.sendMessage("SYSTEM:잘못된 명령어입니다. 예: /save 2");
        }
    }

    private static ClientHandler getPlayerByNumber(int number) {
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                if (handler.playerNumber == number) {
                    return handler;
                }
            }
        }
        return null;
    }

    // 마피아끼리 대화
    private static void broadcastToMafia(String message) {
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                if (handler.role == Role.MAFIA && handler.status == PlayerStatus.ALIVE) {
                    handler.sendMessage(message);
                }
            }
        }
    }

    // 생존자, 사망자 메시지
    private static void broadcast(String message) {
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                if (currentPhase == GamePhase.DAY || currentPhase == GamePhase.NIGHT) {
                    if (handler.status == PlayerStatus.ALIVE || message.startsWith("SYSTEM:지난 밤")) { // 사망 메시지는 모두에게
                        handler.sendMessage(message);
                    }
                } else {
                    handler.sendMessage(message);
                }
            }
        }
    }

    // 플레이어 목록을 클라이언트에게 전송하는 메서드 (ClientGUI의 중앙 영역 업데이트용)
    private static void broadcastPlayerList() {
        StringBuilder sb = new StringBuilder();
        synchronized (clientHandlers) {
            for (ClientHandler h : clientHandlers) {
                sb.append(h.playerNumber);
            }
        }
        broadcast("PLAYERS_LIST:" + sb.toString());
    }

    //게임 종료 시점 확인
    private static synchronized boolean checkGameEnd() {
        int mafiaAlive = 0;
        int citizensAlive = 0;

        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                if (handler.status == PlayerStatus.ALIVE) {
                    if (handler.role == Role.MAFIA) {
                        mafiaAlive++;
                    } else if (handler.role != Role.NONE) {
                        citizensAlive++;
                    }
                }
            }
        }

        System.out.println("게임 상태 확인: 마피아(" + mafiaAlive + "), 시민팀(" + citizensAlive + ")");

        if (mafiaAlive == 0) {
            broadcast("SYSTEM:모든 마피아가 사망했습니다. 시민의 승리입니다!");
            endGame();
            return true;
        }

        if (mafiaAlive >= citizensAlive) {
            broadcast("SYSTEM:마피아의 수가 시민의 수와 같거나 많아졌습니다. 마피아의 승리입니다!");
            endGame();
            return true;
        }

        return false; // 게임 계속
    }

    // 게임 종료시
    private static synchronized void endGame() {
        System.out.println("게임 종료.");
        gameTimer.cancel();
        currentPhase = GamePhase.WAITING;

        gameTimer = new Timer();

        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                handler.role = Role.NONE;
                handler.status = PlayerStatus.ALIVE;
                handler.sendMessage("GAME_OVER");
            }
        }
        broadcastPlayerList(); // 게임 종료 후 목록 업데이트 (상태 리셋)
    }

    //client마다 Thread실행
    private static class ClientHandler implements Runnable {
        private Socket socket;
        private PrintWriter out;
        private Scanner in;

        public int playerNumber;
        public String name;
        public Role role = Role.NONE;
        public PlayerStatus status = PlayerStatus.ALIVE;

        public ClientHandler(Socket socket) {
            this.socket = socket;
            this.playerNumber = playerCounter.getAndIncrement();
            this.name = "플레이어 " + this.playerNumber; // 초기 이름
        }

        public void sendMessage(String message) {
            out.println(message);
        }

        @Override
        public void run() {
            try {

                in = new Scanner(socket.getInputStream());
                out = new PrintWriter(socket.getOutputStream(), true);

                synchronized (clientHandlers) {
                    clientHandlers.add(this);
                }

                sendMessage("PLAYER_NUM:" + this.playerNumber);

                // 닉네임 수신 대기 (Client.java에서 'NICKNAME:'으로 전송)
                if (in.hasNextLine()) {
                    String firstLine = in.nextLine();
                    if (firstLine.startsWith("NICKNAME:")) {
                        this.name = firstLine.substring(9).trim();
                        if (this.name.isEmpty()) this.name = "P" + this.playerNumber;
                    }
                }

                System.out.println(socket.getRemoteSocketAddress() + "가 P" + playerNumber + "(" + name + ")로 연결되었습니다.");
                broadcast("SYSTEM:" + this.name + "(P" + this.playerNumber + ") 님이 입장했습니다.");
                broadcastPlayerList(); // 입장 시 목록 업데이트

                while (in.hasNextLine()) {
                    String message = in.nextLine();

                    if (status == PlayerStatus.DEAD && !message.trim().equalsIgnoreCase("/start")) {
                        sendMessage("SYSTEM:당신은 죽었습니다. 아무것도 할 수 없습니다.");
                        continue;
                    }

                    if (message.trim().equalsIgnoreCase("/start")) {
                        System.out.println("P" + playerNumber + "로부터 /start 명령 수신");
                        startGame();
                    }
                    else if (message.trim().startsWith("/investigate ")) {
                        if (role == Role.POLICE) {
                            handleInvestigate(this, message.trim());
                        } else {
                            sendMessage("SYSTEM:경찰만 사용할 수 있는 명령어입니다.");
                        }
                    }
                    else if (message.trim().startsWith("/save ")) {
                        if (role == Role.DOCTOR) {
                            handleSave(this, message.trim());
                        } else {
                            sendMessage("SYSTEM:의사만 사용할 수 있는 명령어입니다.");
                        }
                    }
                    else if (message.trim().startsWith("/kill ")) {
                        if (role == Role.MAFIA) {
                            handleKillCommand(this, message.trim());
                        } else {
                            sendMessage("SYSTEM:마피아만 사용할 수 있는 명령어입니다.");
                        }
                    }
                    else if (message.trim().startsWith("/vote ")) {
                        if (currentPhase == GamePhase.DAY) {
                            handleVote(this, message.trim());
                        } else {
                            sendMessage("SYSTEM:투표는 낮에만 할 수 있습니다.");
                        }
                    }
                    else if (message.startsWith("MSG:")) {
                        synchronized (Server.class) {
                            if (currentPhase == GamePhase.DAY) {
                                System.out.println("[낮] P" + playerNumber + ": " + message.substring(4));
                                broadcast("P" + playerNumber + ": " + message.substring(4));
                            }
                            else if (currentPhase == GamePhase.NIGHT) {
                                if (role == Role.MAFIA) {
                                    System.out.println("[밤-마피아] P" + playerNumber + ": " + message.substring(4));
                                    broadcastToMafia("[마피아채팅] P" + playerNumber + ": " + message.substring(4));
                                }
                                else {
                                    System.out.println("[밤-시민팀] P" + playerNumber + " 메시지 차단");
                                    sendMessage("SYSTEM:밤에는 능력을 사용하거나 마피아만 대화할 수 있습니다.");
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("P" + playerNumber + "의 연결이 끊겼습니다: " + e.getMessage());
            } finally {
                if (out != null) {
                    synchronized (clientHandlers) {
                        clientHandlers.remove(this);
                        GamePhase oldPhase = currentPhase;
                        currentPhase = GamePhase.WAITING;
                        broadcast("SYSTEM:" + name + "(P" + playerNumber + ") 님이 퇴장했습니다.");
                        currentPhase = oldPhase;
                        if (oldPhase != GamePhase.WAITING) {
                            checkGameEnd();
                        }
                    }
                }
                try {
                    socket.close();
                } catch (IOException e) {}
                broadcastPlayerList(); // 퇴장 시 목록 업데이트
            }
        }
    }
}