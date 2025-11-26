import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.io.BufferedReader; // [수정] BufferedReader 추가
import java.io.InputStreamReader; // [수정] InputStreamReader 추가
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.JOptionPane;


public class Server {

    private static Set<ClientHandler> clientHandlers = new HashSet<>();
    private static volatile ClientHandler currentHost = null;

    private enum GamePhase { WAITING, DAY, NIGHT }
    private static GamePhase currentPhase = GamePhase.WAITING;

    private static ScheduledExecutorService phaseScheduler = Executors.newSingleThreadScheduledExecutor();
    private static ScheduledExecutorService timerUpdater = Executors.newSingleThreadScheduledExecutor();

    private static final long PHASE_TIME_SECONDS = 60;
    private static volatile long currentPhaseTimeLeft = 0;

    private static AtomicInteger playerCounter = new AtomicInteger(1);

    private static Map<ClientHandler, ClientHandler> votes = new HashMap<>();

    private static ClientHandler nightKillTarget = null;
    private static ClientHandler nightSaveTarget = null;
    private static ClientHandler nightInvestigateUser = null;

    private enum Role { NONE, MAFIA, CITIZEN, POLICE, DOCTOR }
    private enum PlayerStatus { ALIVE, DEAD }


    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ServerGUI serverGUI = new ServerGUI();

            serverGUI.getStartButton().addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        int port = serverGUI.getPortNumber();
                        startServerLogic(port);
                        serverGUI.getStartButton().setEnabled(false);
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

        new Thread(() -> {
            try (ServerSocket listener = new ServerSocket(port)) {
                while (true) {
                    pool.execute(new ClientHandler(listener.accept()));
                }
            } catch (IOException e) {
                System.err.println("서버 리스너 오류: " + e.getMessage());
            }
        }).start();

        timerUpdater.scheduleAtFixedRate(() -> {
            if (currentPhase != GamePhase.WAITING && currentPhaseTimeLeft > 0) {
                currentPhaseTimeLeft--;
            }
            broadcast("TIMER:" + currentPhase.name() + ":" + currentPhaseTimeLeft);
        }, 0, 1, TimeUnit.SECONDS);
    }


    private static synchronized void assignNewHost() {
        if (currentHost != null) {
            currentHost.isHost = false;
            currentHost = null;
        }

        ClientHandler newHost = null;
        synchronized (clientHandlers) {
            if (clientHandlers.isEmpty()) {
                return;
            }

            newHost = clientHandlers.stream()
                    .min(Comparator.comparingInt(h -> h.playerNumber))
                    .orElse(null);
        }

        if (newHost != null) {
            newHost.isHost = true;
            currentHost = newHost;
            newHost.sendMessage("SYSTEM:HOST_GRANTED");
            broadcast("SYSTEM:" + newHost.name + "(P" + newHost.playerNumber + ") 님이 새로운 방장이 되었습니다.");
            broadcastPlayerList();
        }
    }


    public static synchronized void handleReady(ClientHandler readyClient) {
        if (currentPhase != GamePhase.WAITING) {
            readyClient.sendMessage("SYSTEM:게임이 시작된 후에는 준비/취소할 수 없습니다.");
            return;
        }
        if (readyClient.isHost) {
            readyClient.sendMessage("SYSTEM:방장은 준비 상태를 변경할 수 없습니다. (항상 준비 상태)");
            return;
        }

        readyClient.isReady = !readyClient.isReady;
        String status = readyClient.isReady ? "준비 완료" : "준비 취소";
        readyClient.sendMessage("SYSTEM:" + status + "되었습니다.");
        broadcast("SYSTEM:" + readyClient.name + "(P" + readyClient.playerNumber + ") 님이 " + status + "했습니다.");

        broadcastPlayerList();
    }


    public static synchronized void startGame(ClientHandler starter) {
        if (currentPhase != GamePhase.WAITING) return;

        if (!starter.isHost) {
            starter.sendMessage("SYSTEM:게임 시작은 방장만 할 수 있습니다.");
            return;
        }

        if (clientHandlers.size() < 4) {
            starter.sendMessage("SYSTEM:게임 시작을 위해 4명 이상의 플레이어가 필요합니다.");
            return;
        }

        boolean allReady = true;
        for (ClientHandler handler : clientHandlers) {
            if (!handler.isHost && !handler.isReady) {
                allReady = false;
                break;
            }
        }

        if (!allReady) {
            starter.sendMessage("SYSTEM:모든 플레이어가 준비 상태여야 게임을 시작할 수 있습니다.");
            return;
        }


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

        System.out.println("--- 직업 배정 시작 ---");
        for (int i = 0; i < numMafias; i++) {
            ClientHandler handler = handlersList.get(currentIndex);
            handler.role = Role.MAFIA;
            handler.sendMessage("ROLE:MAFIA");
            System.out.println("마피아: P" + handler.playerNumber + " (" + handler.name + ")");
            currentIndex++;
        }

        if (currentIndex < numPlayers) {
            ClientHandler police = handlersList.get(currentIndex);
            police.role = Role.POLICE;
            police.sendMessage("ROLE:POLICE");
            System.out.println("경찰: P" + police.playerNumber + " (" + police.name + ")");
            currentIndex++;
        }

        if (currentIndex < numPlayers) {
            ClientHandler doctor = handlersList.get(currentIndex);
            doctor.role = Role.DOCTOR;
            doctor.sendMessage("ROLE:DOCTOR");
            System.out.println("의사: P" + doctor.playerNumber + " (" + doctor.name + ")");
            currentIndex++;
        }

        while (currentIndex < numPlayers) {
            ClientHandler handler = handlersList.get(currentIndex);
            handler.role = Role.CITIZEN;
            handler.sendMessage("ROLE:CITIZEN");
            currentIndex++;
        }
        System.out.println("--- 직업 배정 완료 ---");

        currentPhase = GamePhase.NIGHT;
        broadcast("SYSTEM:밤이 되었습니다. 능력을 사용할 대상을 지목하세요.");
        broadcastPlayerList();
        scheduleDayNightTimer();
    }

    private static void scheduleDayNightTimer() {
        phaseScheduler.shutdownNow();
        phaseScheduler = Executors.newSingleThreadScheduledExecutor();

        currentPhaseTimeLeft = PHASE_TIME_SECONDS;

        phaseScheduler.schedule(() -> {
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

                    if (checkGameEnd()) {
                        return;
                    }

                    broadcast("SYSTEM:낮이 되었습니다. 토론 및 투표를 시작하세요. (/vote 번호)");
                    votes.clear();
                    broadcastPlayerList();
                }
                scheduleDayNightTimer();
            }
        }, PHASE_TIME_SECONDS, TimeUnit.SECONDS);
    }

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
            broadcastPlayerList();
        } else {
            broadcast("SYSTEM:투표가 과반수(" + majorityThreshold + "표)에 미치지 못해 (" + maxVotes + "표) 처형이 없습니다.");
        }
    }

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
            } else if (PlayerStatus.DEAD == target.status) {
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
            int targetNumber = Integer.parseInt(command.substring(6).trim());
            ClientHandler target = getPlayerByNumber(targetNumber);

            if (target == null) {
                police.sendMessage("SYSTEM:존재하지 않는 플레이어 번호입니다.");
            } else if (target.status == PlayerStatus.DEAD) {
                police.sendMessage("SYSTEM:이미 죽은 플레이어입니다.");
            } else {
                if (target.role == Role.MAFIA) {
                    police.sendMessage("SYSTEM:[조사결과] P" + target.playerNumber + " 님은 [마피아] 입니다.");
                } else if (target.role == Role.POLICE) {
                    police.sendMessage("SYSTEM:본인은 조사할 수 없습니다.");
                    return;
                } else {
                    police.sendMessage("SYSTEM:[조사결과] P" + target.playerNumber + " 님은 [시민] 입니다.");
                }
                nightInvestigateUser = police;
            }
        } catch (Exception e) {
            police.sendMessage("SYSTEM:잘못된 명령어입니다. 예: /investigate 2");
        }
    }

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

    private static void broadcastToMafia(String message) {
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                if (handler.role == Role.MAFIA && handler.status == PlayerStatus.ALIVE) {
                    handler.sendMessage(message);
                }
            }
        }
    }

    private static void broadcastToDead(String message) {
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                if (handler.status == PlayerStatus.DEAD) {
                    handler.sendMessage(message);
                }
            }
        }
    }

    private static void broadcastToMafiaExceptSender(String message, ClientHandler sender) {
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                if (handler != sender && handler.role == Role.MAFIA && handler.status == PlayerStatus.ALIVE) {
                    handler.sendMessage(message);
                }
            }
        }
    }

    private static void broadcastToDeadExceptSender(String message, ClientHandler sender) {
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                if (handler != sender && handler.status == PlayerStatus.DEAD) {
                    handler.sendMessage(message);
                }
            }
        }
    }

    private static void broadcastExceptSenderToAlive(String message, ClientHandler sender) {
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                if (handler != sender && handler.status == PlayerStatus.ALIVE) {
                    handler.sendMessage(message);
                }
            }
        }
    }


    private static void broadcast(String message) {
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                if (currentPhase == GamePhase.DAY || currentPhase == GamePhase.NIGHT) {
                    if (message.startsWith("TIMER:") || handler.status == PlayerStatus.ALIVE || message.startsWith("SYSTEM:지난 밤")) {
                        handler.sendMessage(message);
                    } else if (handler.status == PlayerStatus.DEAD && message.startsWith("SYSTEM:")) {
                        handler.sendMessage(message);
                    }
                } else {
                    handler.sendMessage(message);
                }
            }
        }
    }

    private static void broadcastPlayerList() {
        StringBuilder sb = new StringBuilder();
        synchronized (clientHandlers) {
            List<ClientHandler> sortedHandlers = new ArrayList<>(clientHandlers);
            Collections.sort(sortedHandlers, Comparator.comparingInt(h -> h.playerNumber));

            for (ClientHandler h : sortedHandlers) {
                if (sb.length() > 0) sb.append(",");
                String statusText = (h.status == PlayerStatus.ALIVE) ? "생존" : "사망";
                String roleText = (currentPhase == GamePhase.WAITING) ? "" : " [" + h.role.toString().charAt(0) + "]";

                String hostReadyStatus = "";
                if (currentPhase == GamePhase.WAITING) {
                    if (h.isHost) {
                        hostReadyStatus = " (방장)";
                    } else if (h.isReady) {
                        hostReadyStatus = " (준비)";
                    } else {
                        hostReadyStatus = " (대기)";
                    }
                }

                sb.append("P").append(h.playerNumber).append(" - ").append(h.name).append(" (").append(statusText).append(")").append(roleText).append(hostReadyStatus);
            }
        }
        broadcast("PLAYERS_LIST:" + sb.toString());
    }

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

        return false;
    }

    private static synchronized void endGame() {
        System.out.println("게임 종료.");
        phaseScheduler.shutdownNow();

        currentPhase = GamePhase.WAITING;
        currentPhaseTimeLeft = 0;

        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                handler.role = Role.NONE;
                handler.status = PlayerStatus.ALIVE;
                handler.isReady = handler.isHost;
                handler.sendMessage("GAME_OVER");
            }
            broadcastPlayerList();
        }
    }

    private static class ClientHandler implements Runnable {
        private Socket socket;
        private PrintWriter out;
        // [수정] Scanner 대신 BufferedReader 사용
        private BufferedReader in;

        public int playerNumber;
        public String name;
        public Role role = Role.NONE;
        public PlayerStatus status = PlayerStatus.ALIVE;
        public boolean isHost = false;
        public boolean isReady = false;

        public ClientHandler(Socket socket) {
            this.socket = socket;
            this.playerNumber = playerCounter.getAndIncrement();
            this.name = "플레이어 " + this.playerNumber;
        }

        public void sendMessage(String message) {
            out.println(message);
        }

        @Override
        public void run() {
            try {
                // [수정] BufferedReader를 사용하여 안정적으로 라인 단위로 읽음
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                synchronized (clientHandlers) {
                    clientHandlers.add(this);

                    if (currentHost == null) {
                        this.isHost = true;
                        this.isReady = true;
                        currentHost = this;
                        sendMessage("SYSTEM:HOST_GRANTED");
                        broadcast("SYSTEM:P" + this.playerNumber + "(" + this.name + ") 님이 방장 권한을 획득했습니다.");
                    } else {
                        sendMessage("SYSTEM:GUEST_GRANTED");
                    }
                }

                sendMessage("PLAYER_NUM:" + this.playerNumber);

                // [수정] BufferedReader 사용에 맞춰 메시지 수신 로직 변경
                String line;
                while ((line = in.readLine()) != null) {
                    final String message = line.trim();

                    // [수정] 메시지가 비어있으면 건너뜀 (안전 장치)
                    if (message.isEmpty()) {
                        continue;
                    }

                    if (message.startsWith("TIMER:")) {
                        continue;
                    }

                    // [수정] 사망자일 때 CHAT, /ready, /vote 외의 모든 능력/명령어 차단
                    if (status == PlayerStatus.DEAD && !message.startsWith("CHAT:") && !message.startsWith("/ready") && !message.startsWith("/vote")) {
                        sendMessage("SYSTEM:당신은 죽었습니다. 채팅 외의 행동은 할 수 없습니다.");
                        continue;
                    }

                    if (message.trim().equalsIgnoreCase("/start")) {
                        System.out.println("P" + playerNumber + "로부터 /start 명령 수신");
                        startGame(this);
                    }
                    else if (message.trim().equalsIgnoreCase("/ready")) {
                        System.out.println("P" + playerNumber + "로부터 /ready 명령 수신");
                        handleReady(this);
                    }
                    else if(message.trim().startsWith("/skill "))
                    {
                        if (currentPhase != GamePhase.NIGHT) {
                            sendMessage("SYSTEM:능력은 밤에만 사용할 수 없습니다.");
                            continue;
                        }
                        switch (role){
                            case POLICE:
                                handleInvestigate(this, message.trim());
                                break;
                            case DOCTOR:
                                handleSave(this, message.trim());
                                break;
                            case MAFIA:
                                handleKillCommand(this, message.trim());
                                break;
                            case CITIZEN:
                                sendMessage("SYSTEM:시민은 능력을 사용할 수 없습니다.");
                        }
                    }
                    else if (message.trim().startsWith("/vote ")) {
                        if (currentPhase == GamePhase.DAY) {
                            handleVote(this, message.trim());
                        } else {
                            sendMessage("SYSTEM:투표는 낮에만 할 수 있습니다.");
                        }
                    }
                    else if (message.startsWith("CHAT:")) {
                        synchronized (Server.class) {
                            String fullChat = message.substring("CHAT:".length());

                            // [수정] 1. 사망자 여부 확인 (낮/밤 상관없이 사망자끼리만 대화 가능)
                            if (this.status == PlayerStatus.DEAD) {
                                System.out.println("[사망자 채팅] " + fullChat);
                                broadcastToDeadExceptSender("CHAT:" + fullChat, this);
                                return;
                            }

                            // 2. 생존자 채팅 (낮, 밤, 대기 중)
                            if (currentPhase == GamePhase.DAY || currentPhase == GamePhase.WAITING) {
                                System.out.println("[" + currentPhase.name() + "] " + fullChat);
                                broadcastExceptSenderToAlive("CHAT:" + fullChat, this);
                            }
                            else if (currentPhase == GamePhase.NIGHT) {
                                // [수정] 밤: 마피아 생존자 채팅
                                if (role == Role.MAFIA && status == PlayerStatus.ALIVE) {
                                    System.out.println("[밤-마피아] " + fullChat);
                                    broadcastToMafiaExceptSender("CHAT:" + fullChat, this);

                                } else {
                                    // [수정] 밤: 시민팀 생존자 채팅 차단
                                    System.out.println("[밤-시민팀 생존자] 메시지 차단");
                                    sendMessage("SYSTEM:밤에는 마피아만 대화 가능합니다.");
                                }
                            }
                        }
                    }
                    else {
                        sendMessage("SYSTEM:알 수 없는 명령어입니다.");
                    }
                }
            } catch (IOException e) {
                // [수정] IOException 발생 시 연결 종료로 간주
                System.out.println("P" + playerNumber + "의 연결이 끊겼습니다 (IOException): " + e.getMessage());
            } catch (Exception e) {
                // [수정] 기타 예외 발생 시 로그 출력
                System.out.println("P" + playerNumber + " 처리 중 예상치 못한 오류 발생: " + e.getMessage());
                e.printStackTrace();
            } finally {
                // [수정] 연결 종료 및 정리 로직
                if (out != null) {
                    synchronized (clientHandlers) {
                        clientHandlers.remove(this);

                        if (this.isHost && clientHandlers.size() > 0) {
                            assignNewHost();
                        } else if (this.isHost) {
                            currentHost = null;
                        }

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
                    if (socket != null) socket.close();
                } catch (IOException e) {}
                broadcastPlayerList();
            }
        }
    }
}