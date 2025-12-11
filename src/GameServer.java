import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GameServer: 실제 마피아 게임 로직과 네트워크 처리를 담당하는 클래스.
 *
 * - 클라이언트 연결 수락
 * - 낮/밤 페이즈 진행
 * - 직업 배정 / 투표 / 능력 처리
 * - BroadCast 관리
 *
 * 개별 클라이언트와의 통신은 ClientHandler에서 처리하고,
 * 게임 규칙 및 공용 상태는 이 클래스에서 관리합니다.
 */
public class GameServer {

    // ====== 내부 타입 ======
    public enum GamePhase { WAITING, DAY, NIGHT }

    // ====== 서버 상태 필드 ======
    final Set<ClientHandler> clientHandlers = new HashSet<>();
    ClientHandler currentHost = null;

    GamePhase currentPhase = GamePhase.WAITING;

    private ScheduledExecutorService phaseScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService timerUpdater = Executors.newSingleThreadScheduledExecutor();

    private static final long PHASE_TIME_SECONDS = 60L;
    private volatile long currentPhaseTimeLeft = 0L;

    private final AtomicInteger playerCounter = new AtomicInteger(1);

    final Map<ClientHandler, ClientHandler> votes = new HashMap<>();

    ClientHandler nightKillTarget = null;
    ClientHandler nightSaveTarget = null;
    ClientHandler nightInvestigateUser = null;
    ClientHandler killingMafia = null;

    // 조사 결과를 저장해, 새로 접속한 클라이언트에게도 MARK_ROLE을 재전송
    final Map<Integer, String> investigatedRoles = new HashMap<>();

    public GameServer() {
    }

    // 현재 페이즈 조회
    public synchronized GamePhase getCurrentPhase() {
        return currentPhase;
    }

    // 새로운 플레이어 번호 할당
    int nextPlayerNumber() {
        return playerCounter.getAndIncrement();
    }

    /**
     * 서버 리스너와 타이머를 시작한다.
     */
    public void start(int port) throws IOException {
        System.out.println("게임 서버가 시작되었습니다. (Port: " + port + ")");
        ExecutorService pool = Executors.newFixedThreadPool(10);

        // 클라이언트 접속 수락 스레드
        new Thread(() -> {
            try (ServerSocket listener = new ServerSocket(port)) {
                while (true) {
                    Socket socket = listener.accept();
                    int playerNumber = nextPlayerNumber();
                    ClientHandler handler = new ClientHandler(this, socket, playerNumber);
                    pool.execute(handler);
                }
            } catch (IOException e) {
                System.err.println("서버 리스너 오류: " + e.getMessage());
            }
        }, "Server-Accept-Thread").start();

        // 타이머 업데이트 스레드: 1초마다 TIMER 브로드캐스트
        timerUpdater.scheduleAtFixedRate(() -> {
            if (currentPhase != GamePhase.WAITING && currentPhaseTimeLeft > 0) {
                currentPhaseTimeLeft--;
            }
            broadcast("TIMER:" + currentPhase.name() + ":" + currentPhaseTimeLeft);
        }, 0, 1, TimeUnit.SECONDS);
    }

    // ====== 클라이언트 접속/해제 시 서버에 알림 ======

    void onClientConnected(ClientHandler handler) {
        synchronized (clientHandlers) {
            clientHandlers.add(handler);

            if (currentHost == null) {
                handler.isHost = true;
                handler.isReady = true;
                currentHost = handler;
                handler.sendMessage("SYSTEM:HOST_GRANTED");
                broadcast("SYSTEM:P" + handler.playerNumber + "(" + handler.name + ") 님이 방장 권한을 획득했습니다.");
            } else {
                handler.sendMessage("SYSTEM:GUEST_GRANTED");
            }
        }
    }

    void sendInvestigatedRolesTo(ClientHandler handler) {
        synchronized (investigatedRoles) {
            for (Map.Entry<Integer, String> entry : investigatedRoles.entrySet()) {
                handler.sendMessage("MARK_ROLE:P" + entry.getKey() + ":" + entry.getValue());
            }
        }
    }

    void onClientDisconnected(ClientHandler handler) {
        synchronized (clientHandlers) {
            clientHandlers.remove(handler);

            if (handler.isHost && !clientHandlers.isEmpty()) {
                assignNewHost();
            } else if (handler.isHost) {
                currentHost = null;
            }

            GamePhase oldPhase = currentPhase;
            currentPhase = GamePhase.WAITING;
            broadcast("SYSTEM:" + handler.name + "(P" + handler.playerNumber + ") 님이 퇴장했습니다.");
            currentPhase = oldPhase;
            if (oldPhase != GamePhase.WAITING) {
                checkGameEnd();
            }
        }
        broadcastPlayerList();
    }

    // ====== 호스트 지정 ======

    private synchronized void assignNewHost() {
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
            newHost.isReady = true;
            currentHost = newHost;
            newHost.sendMessage("SYSTEM:HOST_GRANTED");
            broadcast("SYSTEM:" + newHost.name + "(P" + newHost.playerNumber + ") 님이 새로운 방장이 되었습니다.");
            broadcastPlayerList();
        }
    }

    // ====== 준비 상태 처리 ======

    public synchronized void handleReady(ClientHandler readyClient) {
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

    // ====== 게임 시작 ======

    public synchronized void startGame(ClientHandler starter) {
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

        // 게임 시작 시 조사 결과 초기화
        investigatedRoles.clear();

        nightKillTarget = null;
        nightSaveTarget = null;
        nightInvestigateUser = null;
        votes.clear();
        killingMafia = null;

        broadcast("START_GAME");

        List<ClientHandler> handlersList = new ArrayList<>(clientHandlers);
        Collections.shuffle(handlersList);

        int numPlayers = handlersList.size();

        int numMafias = (numPlayers >= 6) ? 2 : 1;
        int numPolice = 1;
        int numDoctors = 1;

        int currentIndex = 0;

        System.out.println("--- 직업 배정 시작 ---");
        for (int i = 0; i < numMafias && currentIndex < numPlayers; i++) {
            ClientHandler handler = handlersList.get(currentIndex);
            handler.role = Role.MAFIA;
            handler.sendMessage("ROLE:MAFIA");
            handler.sendMessage("SYSTEM:[역할] 당신은 'MAFIA'입니다.");
            System.out.println("마피아: P" + handler.playerNumber + " (" + handler.name + ")");
            currentIndex++;
        }

        if (currentIndex < numPlayers && numPolice > 0) {
            ClientHandler police = handlersList.get(currentIndex);
            police.role = Role.POLICE;
            police.sendMessage("ROLE:POLICE");
            police.sendMessage("SYSTEM:[역할] 당신은 'POLICE'입니다.");
            System.out.println("경찰: P" + police.playerNumber + " (" + police.name + ")");
            currentIndex++;
        }

        if (currentIndex < numPlayers && numDoctors > 0) {
            ClientHandler doctor = handlersList.get(currentIndex);
            doctor.role = Role.DOCTOR;
            doctor.sendMessage("ROLE:DOCTOR");
            doctor.sendMessage("SYSTEM:[역할] 당신은 'DOCTOR'입니다.");
            System.out.println("의사: P" + doctor.playerNumber + " (" + doctor.name + ")");
            currentIndex++;
        }

        while (currentIndex < numPlayers) {
            ClientHandler handler = handlersList.get(currentIndex);
            handler.role = Role.CITIZEN;
            handler.sendMessage("ROLE:CITIZEN");
            handler.sendMessage("SYSTEM:[역할] 당신은 'CITIZEN'입니다.");
            currentIndex++;
        }
        System.out.println("--- 직업 배정 완료 ---");

        currentPhase = GamePhase.NIGHT;
        broadcast("SYSTEM:밤이 되었습니다. 능력을 사용할 대상을 지목하세요.");
        broadcastPlayerList();
        scheduleDayNightTimer();
    }

    // ====== 낮/밤 타이머 ======

    private void scheduleDayNightTimer() {
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

    // ====== 투표 집계 ======

    private synchronized void tallyVotes() {
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

    // ====== 명령 처리 메서드들 ======

    public synchronized void handleVote(ClientHandler voter, String command) {
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

    public synchronized void handleKillCommand(ClientHandler mafia, String command) {
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
                killingMafia = mafia;

                // 모든 클라이언트에게 마크 정보 전송
                broadcast("MARK_TARGET:P" + target.playerNumber);

                String notification = "SYSTEM:[마피아 알림] " + mafia.name + "(P" + mafia.playerNumber + ") 님이 P" + target.playerNumber + " (" + target.name + ") 님을 처형 대상으로 지목했습니다.";
                broadcastToMafia(notification);
            }
        } catch (Exception e) {
            mafia.sendMessage("SYSTEM:잘못된 명령어입니다. 예: /kill 2");
        }
    }

    public synchronized void handleInvestigate(ClientHandler police, String command) {
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
                String roleResult;

                if (target.role == Role.MAFIA) {
                    police.sendMessage("SYSTEM:[조사결과] P" + target.playerNumber + " 님은 [마피아] 입니다.");
                    roleResult = "MAFIA";
                } else if (target.role == Role.POLICE) {
                    police.sendMessage("SYSTEM:본인은 조사할 수 없습니다.");
                    return;
                } else {
                    police.sendMessage("SYSTEM:[조사결과] P" + target.playerNumber + " 님은 [시민] 입니다.");
                    roleResult = "CITIZEN";
                }

                nightInvestigateUser = police;

                // 조사 결과를 클라이언트에게 전송 (마크용)
                broadcast("MARK_ROLE:P" + target.playerNumber + ":" + roleResult);

                // 새로 접속하는 클라이언트에게도 전달할 수 있도록 저장
                synchronized (investigatedRoles) {
                    investigatedRoles.put(target.playerNumber, roleResult);
                }
            }
        } catch (Exception e) {
            police.sendMessage("SYSTEM:잘못된 명령어입니다. 예: /investigate 2");
        }
    }

    public synchronized void handleSave(ClientHandler doctor, String command) {
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

                // 모든 클라이언트에게 마크 정보 전송
                broadcast("MARK_TARGET:P" + target.playerNumber);
            }
        } catch (Exception e) {
            doctor.sendMessage("SYSTEM:잘못된 명령어입니다. 예: /save 2");
        }
    }

    // ====== 유틸 ======

    private ClientHandler getPlayerByNumber(int number) {
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                if (handler.playerNumber == number) {
                    return handler;
                }
            }
        }
        return null;
    }

    void broadcastToMafia(String message) {
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                if (handler.role == Role.MAFIA && handler.status == PlayerStatus.ALIVE) {
                    handler.sendMessage(message);
                }
            }
        }
    }

    void broadcastToMafiaExceptSender(String message, ClientHandler sender) {
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                if (handler != sender && handler.role == Role.MAFIA && handler.status == PlayerStatus.ALIVE) {
                    handler.sendMessage(message);
                }
            }
        }
    }

    void broadcastToDeadExceptSender(String message, ClientHandler sender) {
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                if (handler != sender && handler.status == PlayerStatus.DEAD) {
                    handler.sendMessage(message);
                }
            }
        }
    }

    void broadcastExceptSenderToAll(String message, ClientHandler sender) {
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                if (handler != sender) {
                    handler.sendMessage(message);
                }
            }
        }
    }

    private void broadcast(String message) {
        synchronized (clientHandlers) {
            for (ClientHandler handler : clientHandlers) {
                if (currentPhase == GamePhase.DAY || currentPhase == GamePhase.NIGHT) {
                    if (message.startsWith("TIMER:") || handler.status == PlayerStatus.ALIVE ||
                            message.startsWith("SYSTEM:지난 밤") || message.startsWith("MARK_")) {
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

    private void broadcastPlayerList() {
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

                sb.append("P").append(h.playerNumber)
                        .append(" - ").append(h.name)
                        .append(" (").append(statusText).append(")")
                        .append(roleText)
                        .append(hostReadyStatus);
            }
        }
        broadcast("PLAYERS_LIST:" + sb.toString());
    }

    // ====== 게임 종료 체크 / 종료 ======

    private synchronized boolean checkGameEnd() {
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

    private synchronized void endGame() {
        System.out.println("게임 종료.");
        phaseScheduler.shutdownNow();

        currentPhase = GamePhase.WAITING;
        currentPhaseTimeLeft = 0;

        investigatedRoles.clear();

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
}
