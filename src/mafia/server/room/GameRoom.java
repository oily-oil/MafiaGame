package mafia.server.room;

import mafia.Enum.GamePhase;
import mafia.Enum.PlayerStatus;
import mafia.Enum.Role;
import mafia.server.GameServer;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * GameRoom: 하나의 방(게임)을 표현.
 *  - 플레이어 목록, 방장, 준비 상태
 *  - 낮/밤, 투표, 능력 사용, 승패 판단 등 게임 규칙 담당
 *  - 같은 서버 안에 GameRoom 이 여러 개 존재할 수 있음.
 */
public class GameRoom {

    private final GameServer server;
    private final String roomName;

    private final Map<Integer, PlayerSession> players = new LinkedHashMap<>();

    private PlayerSession currentHost = null;

    private GamePhase currentPhase = GamePhase.WAITING;

    // 낮/밤 타이머 관련
    private final ScheduledExecutorService phaseScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService timerScheduler = Executors.newSingleThreadScheduledExecutor();

    private static final long PHASE_TIME_SECONDS = 60;
    private volatile long currentPhaseTimeLeft = 0;

    // 투표 / 밤 능력
    private final Map<PlayerSession, PlayerSession> votes = new HashMap<>();
    private PlayerSession nightKillTarget = null;
    private PlayerSession nightSaveTarget = null;
    private PlayerSession nightInvestigateUser = null;
    private PlayerSession killingMafia = null;

    public GameRoom(GameServer server, String roomName) {
        this.server = server;
        this.roomName = roomName;

        // 초 단위 타이머 (TIMER: 메시지 전송)
        timerScheduler.scheduleAtFixedRate(() -> {
            if (currentPhase != GamePhase.WAITING && currentPhaseTimeLeft > 0) {
                currentPhaseTimeLeft--;
            }
            broadcast("TIMER:" + currentPhase.name() + ":" + currentPhaseTimeLeft);
        }, 0, 1, TimeUnit.SECONDS);
    }

    public String getRoomName() {
        return roomName;
    }

    public GamePhase getCurrentPhase() {
        return currentPhase;
    }

    public int getPlayerCount() {
        return players.size();
    }

    /**
     * 새로운 플레이어가 이 방에 입장 가능한지 여부.
     *  - WAITING 상태에서만 true
     */
    public synchronized boolean isJoinable() {
        return currentPhase == GamePhase.WAITING;
    }

    // ================== 플레이어 입장/퇴장 ==================

    public synchronized void addPlayer(PlayerSession session) {
        players.put(session.getPlayerNumber(), session);

        // 방장 지정
        if (currentHost == null) {
            currentHost = session;
            session.setHost(true);
            session.setReady(true); // 방장은 항상 준비됨
            session.send("SYSTEM:HOST_GRANTED");
            broadcast("SYSTEM:" + formatPlayer(session) + " 님이 방장 권한을 획득했습니다.");
        } else {
            session.setHost(false);
            session.setReady(false);
            session.send("SYSTEM:GUEST_GRANTED");
        }

        // 플레이어 번호 알려줌
        session.send("PLAYER_NUM:" + session.getPlayerNumber());

        broadcast("SYSTEM:" + formatPlayer(session) + " 님이 방 '" + roomName + "' 에 입장했습니다.");
        broadcastPlayerList();

        // 방 인원 변화 → 전체 방 목록 갱신
        server.broadcastRoomListToAll();
    }

    public synchronized void removePlayer(PlayerSession session) {
        if (!players.containsKey(session.getPlayerNumber())) return;

        players.remove(session.getPlayerNumber());

        GamePhase oldPhase = currentPhase;
        currentPhase = GamePhase.WAITING;  // 잠깐 WAITING으로 (퇴장 broadcast용)
        broadcast("SYSTEM:" + formatPlayer(session) + " 님이 방을 떠났습니다.");
        currentPhase = oldPhase;

        // 방장 나갔으면 재지정
        if (session == currentHost) {
            assignNewHost();
        }

        // 게임 중이면 승패 여부 다시 체크
        if (oldPhase != GamePhase.WAITING) {
            checkGameEnd();
        }

        broadcastPlayerList();

        // 인원 감소 → 방 목록 갱신
        server.broadcastRoomListToAll();
    }

    private void assignNewHost() {
        currentHost = null;
        for (PlayerSession p : players.values()) {
            currentHost = p;
            break;
        }
        if (currentHost != null) {
            currentHost.setHost(true);
            currentHost.setReady(true);
            currentHost.send("SYSTEM:HOST_GRANTED");
            broadcast("SYSTEM:" + formatPlayer(currentHost) + " 님이 새로운 방장이 되었습니다.");
        }
    }

    // ================== READY / START ==================

    public synchronized void handleReady(PlayerSession player) {
        if (currentPhase != GamePhase.WAITING) {
            player.send("SYSTEM:게임이 시작된 후에는 준비/취소할 수 없습니다.");
            return;
        }
        if (player.isHost()) {
            player.send("SYSTEM:방장은 준비 상태를 변경할 수 없습니다. (항상 준비 상태)");
            return;
        }

        boolean newReady = !player.isReady();
        player.setReady(newReady);

        String status = newReady ? "준비 완료" : "준비 취소";
        player.send("SYSTEM:" + status + "되었습니다.");
        broadcast("SYSTEM:" + formatPlayer(player) + " 님이 " + status + "했습니다.");

        broadcastPlayerList();
    }

    public synchronized void startGame(PlayerSession starter) {
        if (currentPhase != GamePhase.WAITING) return;

        if (!starter.isHost()) {
            starter.send("SYSTEM:게임 시작은 방장만 할 수 있습니다.");
            return;
        }

        if (players.size() < 4) {
            starter.send("SYSTEM:게임 시작을 위해 4명 이상의 플레이어가 필요합니다.");
            return;
        }

        boolean allReady = true;
        for (PlayerSession p : players.values()) {
            if (!p.isHost() && !p.isReady()) {
                allReady = false;
                break;
            }
        }

        if (!allReady) {
            starter.send("SYSTEM:모든 플레이어가 준비 상태여야 게임을 시작할 수 있습니다.");
            return;
        }

        // 게임 시작 시 상태 초기화
        nightKillTarget = null;
        nightSaveTarget = null;
        nightInvestigateUser = null;
        votes.clear();
        killingMafia = null;

        broadcast("START_GAME");

        // 역할 배정
        List<PlayerSession> list = new ArrayList<>(players.values());
        Collections.shuffle(list);

        int numPlayers = list.size();
        int numMafias = (numPlayers >= 6) ? 2 : 1;
        int numPolice = 1;
        int numDoctors = 1;

        int index = 0;

        System.out.println("[ROOM " + roomName + "] --- 직업 배정 시작 ---");

        // 마피아
        for (int i = 0; i < numMafias && index < numPlayers; i++) {
            PlayerSession p = list.get(index++);
            p.setRole(Role.MAFIA);
            p.send("ROLE:MAFIA");
            p.send("SYSTEM:[역할] 당신은 'MAFIA'입니다.");
            System.out.println("  마피아: " + formatPlayer(p));
        }

        // 경찰
        if (index < numPlayers) {
            PlayerSession police = list.get(index++);
            police.setRole(Role.POLICE);
            police.send("ROLE:POLICE");
            police.send("SYSTEM:[역할] 당신은 'POLICE'입니다.");
            System.out.println("  경찰: " + formatPlayer(police));
        }

        // 의사
        if (index < numPlayers) {
            PlayerSession doctor = list.get(index++);
            doctor.setRole(Role.DOCTOR);
            doctor.send("ROLE:DOCTOR");
            doctor.send("SYSTEM:[역할] 당신은 'DOCTOR'입니다.");
            System.out.println("  의사: " + formatPlayer(doctor));
        }

        // 나머지 시민
        while (index < numPlayers) {
            PlayerSession p = list.get(index++);
            p.setRole(Role.CITIZEN);
            p.send("ROLE:CITIZEN");
            p.send("SYSTEM:[역할] 당신은 'CITIZEN'입니다.");
        }

        System.out.println("[ROOM " + roomName + "] --- 직업 배정 완료 ---");

        currentPhase = GamePhase.NIGHT;
        broadcast("SYSTEM:밤이 되었습니다. 능력을 사용할 대상을 지목하세요.");
        broadcastPlayerList();

        scheduleDayNightTimer();
    }

    // ================== 낮/밤 타이머 ==================

    private synchronized void scheduleDayNightTimer() {
        currentPhaseTimeLeft = PHASE_TIME_SECONDS;

        // (기존 구조 유지: 새로운 스케줄러를 만들지만, currentPhase로 흐름 제어)
        phaseScheduler.shutdownNow();
        final GamePhase startingPhase = currentPhase;

        try {
            java.lang.reflect.Field f = GameRoom.class.getDeclaredField("phaseScheduler");
        } catch (NoSuchFieldException e) {
            // ignore (IDE 경고 방지용)
        }

        Executors.newSingleThreadScheduledExecutor().schedule(() -> {
            synchronized (GameRoom.this) {
                if (currentPhase == GamePhase.WAITING) {
                    return;
                }

                if (startingPhase == GamePhase.DAY && currentPhase == GamePhase.DAY) {
                    // 낮 종료 → 투표 집계
                    tallyVotes();
                    if (currentPhase == GamePhase.WAITING) {
                        return;
                    }

                    currentPhase = GamePhase.NIGHT;
                    nightKillTarget = null;
                    nightSaveTarget = null;
                    nightInvestigateUser = null;
                    broadcast("SYSTEM:밤이 되었습니다. 능력을 사용할 대상을 지목하세요.");
                } else if (startingPhase == GamePhase.NIGHT && currentPhase == GamePhase.NIGHT) {
                    // 밤 종료 → 밤 결과 처리
                    if (nightKillTarget != null) {
                        if (nightKillTarget != nightSaveTarget) {
                            nightKillTarget.setStatus(PlayerStatus.DEAD);
                            broadcast("SYSTEM:지난 밤, " + formatPlayer(nightKillTarget) + " 님이 마피아에게 살해당했습니다.");
                            nightKillTarget.send("YOU_DIED");
                        } else {
                            broadcast("SYSTEM:지난 밤, 의사의 활약으로 누군가가 기적적으로 살아났습니다!");
                        }
                    } else {
                        broadcast("SYSTEM:지난 밤, 아무 일도 일어나지 않았습니다.");
                    }

                    if (checkGameEnd()) {
                        return;
                    }

                    currentPhase = GamePhase.DAY;
                    broadcast("SYSTEM:낮이 되었습니다. 토론 및 투표를 시작하세요. (/vote 번호)");
                    votes.clear();
                    broadcastPlayerList();
                }

                // 다음 phase 타이머
                scheduleDayNightTimer();
            }
        }, PHASE_TIME_SECONDS, TimeUnit.SECONDS);
    }

    // ================== 투표 처리 ==================

    private synchronized void tallyVotes() {
        Map<PlayerSession, Integer> voteTally = new HashMap<>();
        int livingPlayers = 0;

        for (PlayerSession p : players.values()) {
            if (p.getStatus() == PlayerStatus.ALIVE) {
                livingPlayers++;
            }
        }

        for (Map.Entry<PlayerSession, PlayerSession> entry : votes.entrySet()) {
            PlayerSession voter = entry.getKey();
            PlayerSession target = entry.getValue();
            if (voter.getStatus() == PlayerStatus.ALIVE && target.getStatus() == PlayerStatus.ALIVE) {
                voteTally.put(target, voteTally.getOrDefault(target, 0) + 1);
            }
        }

        if (voteTally.isEmpty()) {
            broadcast("SYSTEM:아무도 투표하지 않아 처형이 없습니다.");
            return;
        }

        int maxVotes = Collections.max(voteTally.values());
        List<PlayerSession> tiedPlayers = new ArrayList<>();
        for (Map.Entry<PlayerSession, Integer> entry : voteTally.entrySet()) {
            if (entry.getValue() == maxVotes) {
                tiedPlayers.add(entry.getKey());
            }
        }

        if (tiedPlayers.size() > 1) {
            broadcast("SYSTEM:동점표(" + maxVotes + "표)가 나와 투표가 무효 처리되었습니다.");
            return;
        }

        PlayerSession personToExecute = tiedPlayers.get(0);
        int majorityThreshold = (livingPlayers / 2) + 1;

        if (maxVotes >= majorityThreshold) {
            personToExecute.setStatus(PlayerStatus.DEAD);
            broadcast("SYSTEM:투표 결과, " + formatPlayer(personToExecute) +
                    " 님이 과반수(" + maxVotes + "표) 득표로 처형당했습니다.");
            personToExecute.send("YOU_DIED");
            checkGameEnd();
            broadcastPlayerList();
        } else {
            broadcast("SYSTEM:투표가 과반수(" + majorityThreshold + "표)에 미치지 못해 (" + maxVotes + "표) 처형이 없습니다.");
        }
    }

    public synchronized void handleVote(PlayerSession voter, String command) {
        try {
            int targetNumber = Integer.parseInt(command.substring(6).trim());
            if (voter.getPlayerNumber() == targetNumber) {
                voter.send("SYSTEM:자신에게 투표할 수 없습니다.");
                return;
            }

            PlayerSession target = players.get(targetNumber);
            if (target == null) {
                voter.send("SYSTEM:존재하지 않는 플레이어 번호입니다.");
            } else if (target.getStatus() == PlayerStatus.DEAD) {
                voter.send("SYSTEM:이미 죽은 플레이어에게 투표할 수 없습니다.");
            } else {
                votes.put(voter, target);
                voter.send("SYSTEM:P" + target.getPlayerNumber() + " (" + target.getName() + ") 님에게 투표했습니다.");
            }
        } catch (Exception e) {
            voter.send("SYSTEM:잘못된 명령어입니다. 예: /vote 2");
        }
    }

    // ================== 밤 능력 처리 ==================

    public synchronized void handleKillCommand(PlayerSession mafia, String command) {
        if (currentPhase != GamePhase.NIGHT) {
            mafia.send("SYSTEM:낮에는 죽일 수 없습니다.");
            return;
        }

        try {
            int targetNumber = Integer.parseInt(command.substring(6).trim());
            PlayerSession target = players.get(targetNumber);

            if (target == null) {
                mafia.send("SYSTEM:존재하지 않는 플레이어 번호입니다.");
            } else if (target.getStatus() == PlayerStatus.DEAD) {
                mafia.send("SYSTEM:이미 죽은 플레이어입니다.");
            } else if (target.getRole() == Role.MAFIA) {
                mafia.send("SYSTEM:동료 마피아를 죽일 수 없습니다.");
            } else {
                nightKillTarget = target;
                killingMafia = mafia;

                broadcast("MARK_TARGET:P" + target.getPlayerNumber());

                String notification = "SYSTEM:[마피아 알림] " + formatPlayer(mafia) +
                        " 님이 P" + target.getPlayerNumber() + " (" + target.getName() + ") 님을 처형 대상으로 지목했습니다.";
                broadcastToMafia(notification);
            }
        } catch (Exception e) {
            mafia.send("SYSTEM:잘못된 명령어입니다. 예: /skill 2");
        }
    }

    public synchronized void handleInvestigate(PlayerSession police, String command) {
        if (currentPhase != GamePhase.NIGHT) {
            police.send("SYSTEM:낮에는 조사할 수 없습니다.");
            return;
        }

        if (nightInvestigateUser != null) {
            police.send("SYSTEM:당신은 이미 조사를 완료했습니다.");
            return;
        }

        try {
            // "/skill 2", "/investigate 2", "2" 어떤 형태가 와도
            // 마지막 토큰을 숫자로 해석하도록 처리
            String trimmed = command.trim();
            String[] tokens = trimmed.split("\\s+");
            if (tokens.length == 0) {
                throw new IllegalArgumentException("no target");
            }

            String lastToken = tokens[tokens.length - 1];
            int targetNumber = Integer.parseInt(lastToken);

            PlayerSession target = players.get(targetNumber);

            if (target == null) {
                police.send("SYSTEM:존재하지 않는 플레이어 번호입니다.");
                return;
            } else if (target.getStatus() == PlayerStatus.DEAD) {
                police.send("SYSTEM:이미 죽은 플레이어입니다.");
                return;
            }

            String roleResult;

            if (target.getRole() == Role.MAFIA) {
                police.send("SYSTEM:[조사결과] P" + target.getPlayerNumber() + " 님은 [마피아] 입니다.");
                roleResult = "MAFIA";
            } else if (target.getRole() == Role.POLICE) {
                police.send("SYSTEM:본인은 조사할 수 없습니다.");
                return;
            } else {
                police.send("SYSTEM:[조사결과] P" + target.getPlayerNumber() + " 님은 [시민] 입니다.");
                roleResult = "CITIZEN";
            }

            nightInvestigateUser = police;

            // 🔹 조사 결과는 "경찰 본인에게만" 전송 (클라는 myRole == POLICE 일 때만 해석)
            police.send("MARK_ROLE:P" + target.getPlayerNumber() + ":" + roleResult);

        } catch (Exception e) {
            police.send("SYSTEM:잘못된 명령어입니다. 예: /skill 2");
        }
    }

    public synchronized void handleSave(PlayerSession doctor, String command) {
        if (currentPhase != GamePhase.NIGHT) {
            doctor.send("SYSTEM:낮에는 살릴 수 없습니다.");
            return;
        }

        try {
            int targetNumber = Integer.parseInt(command.substring(6).trim());
            PlayerSession target = players.get(targetNumber);

            if (target == null) {
                doctor.send("SYSTEM:존재하지 않는 플레이어 번호입니다.");
            } else if (target.getStatus() == PlayerStatus.DEAD) {
                doctor.send("SYSTEM:이미 죽은 플레이어입니다.");
            } else {
                nightSaveTarget = target;
                doctor.send("SYSTEM:P" + target.getPlayerNumber() + " (" + target.getName() + ") 님을 살리기로 결정했습니다.");
                broadcast("MARK_TARGET:P" + target.getPlayerNumber());
            }
        } catch (Exception e) {
            doctor.send("SYSTEM:잘못된 명령어입니다. 예: /skill 2");
        }
    }

    // ================== 채팅 처리 ==================

    public synchronized void handleChat(PlayerSession sender, String rawMessage) {
        String content = rawMessage;
        if (rawMessage.startsWith("CHAT_MAFIA:")) {
            content = rawMessage.substring("CHAT_MAFIA:".length());
        } else if (rawMessage.startsWith("CHAT_DEAD:")) {
            content = rawMessage.substring("CHAT_DEAD:".length());
        } else if (rawMessage.startsWith("CHAT:")) {
            content = rawMessage.substring("CHAT:".length());
        }

        String chatMessage = content;

        if (sender.getStatus() == PlayerStatus.DEAD) {
            System.out.println("[ROOM " + roomName + "][사망자 채팅] " + chatMessage);
            broadcastToDeadExceptSender("CHAT_DEAD:" + chatMessage, sender);
        } else {
            if (currentPhase == GamePhase.DAY || currentPhase == GamePhase.WAITING) {
                System.out.println("[ROOM " + roomName + "][" + currentPhase.name() + "] " + chatMessage);
                broadcastExceptSender("CHAT:" + chatMessage, sender);
            } else if (currentPhase == GamePhase.NIGHT) {
                if (sender.getRole() == Role.MAFIA && sender.getStatus() == PlayerStatus.ALIVE) {
                    System.out.println("[ROOM " + roomName + "][밤-마피아] " + chatMessage);
                    broadcastToMafiaExceptSender("CHAT_MAFIA:" + chatMessage, sender);
                } else {
                    System.out.println("[ROOM " + roomName + "][밤-시민팀 생존자] 메시지 차단");
                    sender.send("SYSTEM:밤에는 마피아만 대화 가능합니다.");
                }
            }
        }
    }

    // ================== 승패 체크 ==================

    private synchronized boolean checkGameEnd() {
        int mafiaAlive = 0;
        int citizensAlive = 0;

        for (PlayerSession p : players.values()) {
            if (p.getStatus() == PlayerStatus.ALIVE) {
                if (p.getRole() == Role.MAFIA) {
                    mafiaAlive++;
                } else if (p.getRole() != Role.NONE) {
                    citizensAlive++;
                }
            }
        }

        System.out.println("[ROOM " + roomName + "] 게임 상태 확인: 마피아(" + mafiaAlive +
                "), 시민팀(" + citizensAlive + ")");

        if (mafiaAlive == 0) {
            broadcast("SYSTEM:모든 마피아가 사망했습니다. 시민의 승리입니다!");
            endGame("시민팀 승리");
            return true;
        }

        if (mafiaAlive >= citizensAlive) {
            broadcast("SYSTEM:마피아의 수가 시민의 수와 같거나 많아졌습니다. 마피아의 승리입니다!");
            endGame("마피아 승리");
            return true;
        }

        return false;
    }

    private synchronized void endGame(String resultText) {
        System.out.println("[ROOM " + roomName + "] 게임 종료.");
        currentPhase = GamePhase.WAITING;
        currentPhaseTimeLeft = 0;

        for (PlayerSession p : players.values()) {
            p.setRole(Role.NONE);
            p.setStatus(PlayerStatus.ALIVE);
            p.setReady(p.isHost());  // 방장은 기본 준비, 나머지는 대기
            p.send("GAME_OVER " + resultText);
        }
        broadcastPlayerList();

        // 게임이 끝났으니 다시 입장 가능한 상태 → 방 목록 갱신
        server.broadcastRoomListToAll();
    }

    // ================== Broadcast 유틸 ==================

    private String formatPlayer(PlayerSession p) {
        return "P" + p.getPlayerNumber() + " (" + p.getName() + ")";
    }

    private void broadcast(String message) {
        for (PlayerSession p : players.values()) {
            if (currentPhase == GamePhase.DAY || currentPhase == GamePhase.NIGHT) {
                if (message.startsWith("TIMER:") ||
                        p.getStatus() == PlayerStatus.ALIVE ||
                        message.startsWith("SYSTEM:지난 밤") ||
                        message.startsWith("MARK_")) {
                    p.send(message);
                } else if (p.getStatus() == PlayerStatus.DEAD && message.startsWith("SYSTEM:")) {
                    p.send(message);
                }
            } else {
                p.send(message);
            }
        }
    }

    private void broadcastPlayerList() {
        StringBuilder sb = new StringBuilder();
        List<PlayerSession> list = new ArrayList<>(players.values());
        list.sort(Comparator.comparingInt(PlayerSession::getPlayerNumber));

        for (PlayerSession p : list) {
            if (sb.length() > 0) sb.append(",");
            String statusText = (p.getStatus() == PlayerStatus.ALIVE) ? "생존" : "사망";
            String roleText = (currentPhase == GamePhase.WAITING) ? "" : " [" + p.getRole().toString().charAt(0) + "]";

            String hostReadyStatus = "";
            if (currentPhase == GamePhase.WAITING) {
                if (p.isHost()) {
                    hostReadyStatus = " (방장)";
                } else if (p.isReady()) {
                    hostReadyStatus = " (준비)";
                } else {
                    hostReadyStatus = " (대기)";
                }
            }

            sb.append("P")
                    .append(p.getPlayerNumber())
                    .append(" - ")
                    .append(p.getName())
                    .append(" (")
                    .append(statusText)
                    .append(")")
                    .append(roleText)
                    .append(hostReadyStatus);
        }

        broadcast("PLAYERS_LIST:" + sb.toString());
    }

    private void broadcastToMafia(String message) {
        for (PlayerSession p : players.values()) {
            if (p.getRole() == Role.MAFIA && p.getStatus() == PlayerStatus.ALIVE) {
                p.send(message);
            }
        }
    }

    private void broadcastToMafiaExceptSender(String message, PlayerSession sender) {
        for (PlayerSession p : players.values()) {
            if (p != sender && p.getRole() == Role.MAFIA && p.getStatus() == PlayerStatus.ALIVE) {
                p.send(message);
            }
        }
    }

    private void broadcastToDeadExceptSender(String message, PlayerSession sender) {
        for (PlayerSession p : players.values()) {
            if (p != sender && p.getStatus() == PlayerStatus.DEAD) {
                p.send(message);
            }
        }
    }

    private void broadcastExceptSender(String message, PlayerSession sender) {
        for (PlayerSession p : players.values()) {
            if (p != sender) {
                p.send(message);
            }
        }
    }
}
