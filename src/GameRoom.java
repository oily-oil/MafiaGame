import java.util.*;

/**
 * 실제 게임 규칙, 상태, 페이즈 전환, 투표/능력 처리 등이 모두 여기로 모여 있음.
 * 네트워크 소켓, GUI 같은 것은 전혀 모름.
 */
public class GameRoom {

    private final List<PlayerSession> players = new ArrayList<>();
    private final Map<PlayerSession, PlayerSession> votes = new HashMap<>();

    private PlayerSession nightKillTarget = null;
    private PlayerSession nightSaveTarget = null;
    private PlayerSession nightInvestigateUser = null;
    private PlayerSession killingMafia = null;

    // 새로 접속한 플레이어에게 알려주기 위한 조사 결과 캐시
    private final Map<Integer, String> investigatedRoleMarks = new HashMap<>();

    private GamePhase currentPhase = GamePhase.WAITING;
    private int phaseTimeLeft = 0;
    private static final int PHASE_DURATION_SECONDS = 60;

    private int nextPlayerNumber = 1;
    private PlayerSession currentHost = null;

    // ====== tick(1초) ======

    public synchronized void tickOneSecond() {
        if (currentPhase != GamePhase.WAITING && phaseTimeLeft > 0) {
            phaseTimeLeft--;
        }

        // TIMER 브로드캐스트
        broadcast("TIMER:" + currentPhase.name() + ":" + phaseTimeLeft);

        if (currentPhase != GamePhase.WAITING && phaseTimeLeft <= 0) {
            if (currentPhase == GamePhase.DAY) {
                onDayTimeUp();
            } else if (currentPhase == GamePhase.NIGHT) {
                onNightTimeUp();
            }
        }
    }

    private void onDayTimeUp() {
        tallyVotes();
        if (currentPhase == GamePhase.WAITING) {
            // 게임이 종료되었을 수 있음
            return;
        }

        // 다음 페이즈: NIGHT
        startNightPhase();
    }

    private void onNightTimeUp() {
        resolveNightActions();

        if (checkGameEnd()) {
            return;
        }

        // 다음 페이즈: DAY
        startDayPhase();
    }

    // ====== 플레이어 입장/퇴장 ======

    public synchronized PlayerSession addPlayer(ClientHandler handler, String nickname) {
        int playerNumber = nextPlayerNumber++;
        String name = (nickname == null || nickname.isBlank())
                ? "플레이어 " + playerNumber
                : nickname;

        PlayerSession session = new PlayerSession(playerNumber, name);
        session.setHandler(handler);

        players.add(session);

        // 방장 지정
        if (currentHost == null) {
            session.setHost(true);
            session.setReady(true);
            currentHost = session;
            session.send("SYSTEM:HOST_GRANTED");
            broadcast("SYSTEM:P" + session.getPlayerNumber() + "(" + session.getName() + ") 님이 방장 권한을 획득했습니다.");
        } else {
            session.setHost(false);
            session.setReady(false);
            session.send("SYSTEM:GUEST_GRANTED");
        }

        // 플레이어 번호 전송
        session.send("PLAYER_NUM:" + session.getPlayerNumber());

        // 기존 조사 결과가 있다면 전송
        sendInvestigatedMarksTo(session);

        // 전체 목록 갱신
        broadcastPlayerList();

        return session;
    }

    public synchronized void handleDisconnect(PlayerSession session) {
        if (session == null) return;

        players.remove(session);

        boolean wasHost = session.isHost();

        broadcast("SYSTEM:" + session.getName() + "(P" + session.getPlayerNumber() + ") 님이 퇴장했습니다.");

        if (players.isEmpty()) {
            currentHost = null;
            currentPhase = GamePhase.WAITING;
            phaseTimeLeft = 0;
            votes.clear();
            nightKillTarget = null;
            nightSaveTarget = null;
            nightInvestigateUser = null;
            killingMafia = null;
            investigatedRoleMarks.clear();
            return;
        }

        if (wasHost) {
            assignNewHost();
        }

        if (currentPhase != GamePhase.WAITING) {
            checkGameEnd();
        }

        broadcastPlayerList();
    }

    private void assignNewHost() {
        currentHost = players.stream()
                .min(Comparator.comparingInt(PlayerSession::getPlayerNumber))
                .orElse(null);

        if (currentHost != null) {
            currentHost.setHost(true);
            currentHost.setReady(true);
            currentHost.send("SYSTEM:HOST_GRANTED");
            broadcast("SYSTEM:" + currentHost.getName() + "(P" + currentHost.getPlayerNumber() + ") 님이 새로운 방장이 되었습니다.");
            broadcastPlayerList();
        }
    }

    // ====== ready / start ======

    public synchronized void handleReady(PlayerSession player) {
        if (currentPhase != GamePhase.WAITING) {
            player.send("SYSTEM:게임이 시작된 후에는 준비/취소할 수 없습니다.");
            return;
        }
        if (player.isHost()) {
            player.send("SYSTEM:방장은 준비 상태를 변경할 수 없습니다. (항상 준비 상태)");
            return;
        }

        player.setReady(!player.isReady());
        String status = player.isReady() ? "준비 완료" : "준비 취소";
        player.send("SYSTEM:" + status + "되었습니다.");
        broadcast("SYSTEM:" + player.getName() + "(P" + player.getPlayerNumber() + ") 님이 " + status + "했습니다.");

        broadcastPlayerList();
    }

    public synchronized void handleStartGame(PlayerSession starter) {
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
        for (PlayerSession p : players) {
            if (!p.isHost() && !p.isReady()) {
                allReady = false;
                break;
            }
        }

        if (!allReady) {
            starter.send("SYSTEM:모든 플레이어가 준비 상태여야 게임을 시작할 수 있습니다.");
            return;
        }

        // 조사 결과 / 투표 / 밤 능력 초기화
        investigatedRoleMarks.clear();
        votes.clear();
        nightKillTarget = null;
        nightSaveTarget = null;
        nightInvestigateUser = null;
        killingMafia = null;

        // 게임 시작
        broadcast("START_GAME");

        // 역할 배정
        assignRoles();

        // 첫 페이즈: NIGHT
        startNightPhase();
        broadcastPlayerList();
    }

    private void assignRoles() {
        List<PlayerSession> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);

        int numPlayers = shuffled.size();
        int numMafias = (numPlayers >= 6) ? 2 : 1;
        int numPolice = 1;
        int numDoctors = 1;

        int index = 0;

        System.out.println("--- 직업 배정 시작 ---");
        for (int i = 0; i < numMafias && index < numPlayers; i++) {
            PlayerSession p = shuffled.get(index++);
            p.setRole(Role.MAFIA);
            p.send("ROLE:MAFIA");
            p.send("SYSTEM:[역할] 당신은 'MAFIA'입니다.");
            System.out.println("마피아: P" + p.getPlayerNumber() + " (" + p.getName() + ")");
        }

        if (index < numPlayers) {
            PlayerSession police = shuffled.get(index++);
            police.setRole(Role.POLICE);
            police.send("ROLE:POLICE");
            police.send("SYSTEM:[역할] 당신은 'POLICE'입니다.");
            System.out.println("경찰: P" + police.getPlayerNumber() + " (" + police.getName() + ")");
        }

        if (index < numPlayers) {
            PlayerSession doctor = shuffled.get(index++);
            doctor.setRole(Role.DOCTOR);
            doctor.send("ROLE:DOCTOR");
            doctor.send("SYSTEM:[역할] 당신은 'DOCTOR'입니다.");
            System.out.println("의사: P" + doctor.getPlayerNumber() + " (" + doctor.getName() + ")");
        }

        while (index < numPlayers) {
            PlayerSession c = shuffled.get(index++);
            c.setRole(Role.CITIZEN);
            c.send("ROLE:CITIZEN");
            c.send("SYSTEM:[역할] 당신은 'CITIZEN'입니다.");
            System.out.println("시민: P" + c.getPlayerNumber() + " (" + c.getName() + ")");
        }
        System.out.println("--- 직업 배정 완료 ---");
    }

    private void startNightPhase() {
        currentPhase = GamePhase.NIGHT;
        phaseTimeLeft = PHASE_DURATION_SECONDS;

        nightKillTarget = null;
        nightSaveTarget = null;
        nightInvestigateUser = null;
        killingMafia = null;

        broadcast("SYSTEM:밤이 되었습니다. 능력을 사용할 대상을 지목하세요.");
    }

    private void startDayPhase() {
        currentPhase = GamePhase.DAY;
        phaseTimeLeft = PHASE_DURATION_SECONDS;

        votes.clear();
        broadcast("SYSTEM:낮이 되었습니다. 토론 및 투표를 시작하세요. (/vote 번호)");
        broadcastPlayerList();
    }

    // ====== 투표 ======

    public synchronized void handleVote(PlayerSession voter, String targetNumberStr) {
        if (currentPhase != GamePhase.DAY) {
            voter.send("SYSTEM:투표는 낮에만 할 수 있습니다.");
            return;
        }

        try {
            int targetNumber = Integer.parseInt(targetNumberStr.trim());

            if (voter.getPlayerNumber() == targetNumber) {
                voter.send("SYSTEM:자신에게 투표할 수 없습니다.");
                return;
            }

            PlayerSession target = getPlayerByNumber(targetNumber);
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

    private void tallyVotes() {
        Map<PlayerSession, Integer> voteTally = new HashMap<>();
        int livingPlayers = 0;

        for (PlayerSession p : players) {
            if (p.getStatus() == PlayerStatus.ALIVE) {
                livingPlayers++;
            }
        }

        for (Map.Entry<PlayerSession, PlayerSession> e : votes.entrySet()) {
            PlayerSession voter = e.getKey();
            PlayerSession target = e.getValue();
            if (voter.getStatus() == PlayerStatus.ALIVE && target.getStatus() == PlayerStatus.ALIVE) {
                voteTally.put(target, voteTally.getOrDefault(target, 0) + 1);
            }
        }

        if (voteTally.isEmpty()) {
            broadcast("SYSTEM:아무도 투표하지 않아 처형이 없습니다.");
            return;
        }

        int maxVotes = Collections.max(voteTally.values());
        List<PlayerSession> tied = new ArrayList<>();
        for (Map.Entry<PlayerSession, Integer> e : voteTally.entrySet()) {
            if (e.getValue() == maxVotes) {
                tied.add(e.getKey());
            }
        }

        if (tied.size() > 1) {
            broadcast("SYSTEM:동점표(" + maxVotes + "표)가 나와 투표가 무효 처리되었습니다.");
            return;
        }

        PlayerSession execute = tied.get(0);
        int majorityThreshold = (livingPlayers / 2) + 1;

        if (maxVotes >= majorityThreshold) {
            execute.setStatus(PlayerStatus.DEAD);
            broadcast("SYSTEM:투표 결과, " + execute.getName() + "(P" + execute.getPlayerNumber() + ") 님이 과반수(" + maxVotes + "표) 득표로 처형당했습니다.");
            execute.send("YOU_DIED");
            checkGameEnd();
            broadcastPlayerList();
        } else {
            broadcast("SYSTEM:투표가 과반수(" + majorityThreshold + "표)에 미치지 못해 (" + maxVotes + "표) 처형이 없습니다.");
        }
    }

    // ====== 능력 처리 (/skill) ======

    public synchronized void handleSkill(PlayerSession user, String targetNumberStr) {
        if (currentPhase != GamePhase.NIGHT) {
            user.send("SYSTEM:능력은 밤에만 사용할 수 있습니다.");
            return;
        }

        switch (user.getRole()) {
            case POLICE:
                handleInvestigate(user, targetNumberStr);
                break;
            case DOCTOR:
                handleSave(user, targetNumberStr);
                break;
            case MAFIA:
                handleKill(user, targetNumberStr);
                break;
            case CITIZEN:
            case NONE:
            default:
                user.send("SYSTEM:시민은 능력을 사용할 수 없습니다.");
                break;
        }
    }

    private void handleKill(PlayerSession mafia, String targetNumberStr) {
        try {
            int targetNumber = Integer.parseInt(targetNumberStr.trim());
            PlayerSession target = getPlayerByNumber(targetNumber);

            if (target == null) {
                mafia.send("SYSTEM:존재하지 않는 플레이어 번호입니다.");
            } else if (target.getStatus() == PlayerStatus.DEAD) {
                mafia.send("SYSTEM:이미 죽은 플레이어입니다.");
            } else if (target.getRole() == Role.MAFIA) {
                mafia.send("SYSTEM:동료 마피아를 죽일 수 없습니다.");
            } else {
                nightKillTarget = target;
                killingMafia = mafia;

                // 대상 마크
                broadcast("MARK_TARGET:P" + target.getPlayerNumber());

                String notification = "SYSTEM:[마피아 알림] " + mafia.getName() + "(P" + mafia.getPlayerNumber() + ") 님이 P" +
                        target.getPlayerNumber() + " (" + target.getName() + ") 님을 처형 대상으로 지목했습니다.";
                broadcastToMafia(notification);
            }
        } catch (Exception e) {
            mafia.send("SYSTEM:잘못된 명령어입니다. 예: /skill 2");
        }
    }

    private void handleInvestigate(PlayerSession police, String targetNumberStr) {
        if (nightInvestigateUser != null) {
            police.send("SYSTEM:당신은 이미 조사를 완료했습니다.");
            return;
        }

        try {
            int targetNumber = Integer.parseInt(targetNumberStr.trim());
            PlayerSession target = getPlayerByNumber(targetNumber);

            if (target == null) {
                police.send("SYSTEM:존재하지 않는 플레이어 번호입니다.");
            } else if (target.getStatus() == PlayerStatus.DEAD) {
                police.send("SYSTEM:이미 죽은 플레이어입니다.");
            } else {
                String roleResult;
                if (target.getRole() == Role.MAFIA) {
                    police.send("SYSTEM:[조사결과] P" + target.getPlayerNumber() + " 님은 [마피아] 입니다.");
                    roleResult = "MAFIA";
                } else if (target == police) {
                    police.send("SYSTEM:본인은 조사할 수 없습니다.");
                    return;
                } else {
                    police.send("SYSTEM:[조사결과] P" + target.getPlayerNumber() + " 님은 [시민] 입니다.");
                    roleResult = "CITIZEN";
                }

                nightInvestigateUser = police;

                // 조사 결과 캐시 저장 + 전체 마크 전송
                investigatedRoleMarks.put(target.getPlayerNumber(), roleResult);
                broadcast("MARK_ROLE:P" + target.getPlayerNumber() + ":" + roleResult);
            }
        } catch (Exception e) {
            police.send("SYSTEM:잘못된 명령어입니다. 예: /skill 2");
        }
    }

    private void handleSave(PlayerSession doctor, String targetNumberStr) {
        try {
            int targetNumber = Integer.parseInt(targetNumberStr.trim());
            PlayerSession target = getPlayerByNumber(targetNumber);

            if (target == null) {
                doctor.send("SYSTEM:존재하지 않는 플레이어 번호입니다.");
            } else if (target.getStatus() == PlayerStatus.DEAD) {
                doctor.send("SYSTEM:이미 죽은 플레이어입니다.");
            } else {
                nightSaveTarget = target;
                doctor.send("SYSTEM:P" + target.getPlayerNumber() + " (" + target.getName() + ") 님을 살리기로 결정했습니다.");

                // 대상 마크
                broadcast("MARK_TARGET:P" + target.getPlayerNumber());
            }
        } catch (Exception e) {
            doctor.send("SYSTEM:잘못된 명령어입니다. 예: /skill 2");
        }
    }

    private void resolveNightActions() {
        if (nightKillTarget != null) {
            if (nightKillTarget != nightSaveTarget) {
                nightKillTarget.setStatus(PlayerStatus.DEAD);
                broadcast("SYSTEM:지난 밤, " + nightKillTarget.getName() + "(P" + nightKillTarget.getPlayerNumber() + ") 님이 마피아에게 살해당했습니다.");
                nightKillTarget.send("YOU_DIED");
            } else {
                broadcast("SYSTEM:지난 밤, 의사의 활약으로 누군가가 기적적으로 살아났습니다!");
            }
        } else {
            broadcast("SYSTEM:지난 밤, 아무 일도 일어나지 않았습니다.");
        }

        nightKillTarget = null;
        nightSaveTarget = null;
        nightInvestigateUser = null;
        killingMafia = null;
    }

    // ====== 채팅 ======

    public synchronized void handleChat(PlayerSession sender, String rawMessage) {
        String content = rawMessage;
        String prefix = "";

        if (rawMessage.startsWith("CHAT_MAFIA:")) {
            prefix = "CHAT_MAFIA:";
            content = rawMessage.substring("CHAT_MAFIA:".length());
        } else if (rawMessage.startsWith("CHAT_DEAD:")) {
            prefix = "CHAT_DEAD:";
            content = rawMessage.substring("CHAT_DEAD:".length());
        } else if (rawMessage.startsWith("CHAT:")) {
            prefix = "CHAT:";
            content = rawMessage.substring("CHAT:".length());
        }

        String chatPayload = content; // "닉네임:메시지" 형식 그대로 유지

        if (sender.getStatus() == PlayerStatus.DEAD) {
            // 사망자 채팅
            System.out.println("[사망자 채팅] " + chatPayload);
            broadcastToDeadExcept(sender, "CHAT_DEAD:" + chatPayload);
            return;
        }

        if (currentPhase == GamePhase.DAY || currentPhase == GamePhase.WAITING) {
            System.out.println("[" + currentPhase.name() + "] " + chatPayload);
            broadcastChatToAliveExcept(sender, "CHAT:" + chatPayload);
        } else if (currentPhase == GamePhase.NIGHT) {
            if (sender.getRole() == Role.MAFIA && sender.getStatus() == PlayerStatus.ALIVE) {
                System.out.println("[밤-마피아] " + chatPayload);
                broadcastToMafiaExcept(sender, "CHAT_MAFIA:" + chatPayload);
            } else {
                System.out.println("[밤-시민팀 생존자] 메시지 차단");
                sender.send("SYSTEM:밤에는 마피아만 대화 가능합니다.");
            }
        }
    }

    // ====== 게임 종료 체크 ======

    private boolean checkGameEnd() {
        int mafiaAlive = 0;
        int citizensAlive = 0;

        for (PlayerSession p : players) {
            if (p.getStatus() == PlayerStatus.ALIVE) {
                if (p.getRole() == Role.MAFIA) {
                    mafiaAlive++;
                } else if (p.getRole() != Role.NONE) {
                    citizensAlive++;
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

    private void endGame() {
        System.out.println("게임 종료.");
        currentPhase = GamePhase.WAITING;
        phaseTimeLeft = 0;

        investigatedRoleMarks.clear();
        votes.clear();
        nightKillTarget = null;
        nightSaveTarget = null;
        nightInvestigateUser = null;
        killingMafia = null;

        for (PlayerSession p : players) {
            p.setRole(Role.NONE);
            p.setStatus(PlayerStatus.ALIVE);
            p.setReady(p.isHost());
            p.send("GAME_OVER");
        }

        broadcastPlayerList();
    }

    // ====== 유틸 ======

    private PlayerSession getPlayerByNumber(int number) {
        for (PlayerSession p : players) {
            if (p.getPlayerNumber() == number) {
                return p;
            }
        }
        return null;
    }

    public synchronized void broadcast(String message) {
        for (PlayerSession p : players) {
            if (currentPhase == GamePhase.DAY || currentPhase == GamePhase.NIGHT) {
                if (message.startsWith("TIMER:")
                        || p.getStatus() == PlayerStatus.ALIVE
                        || message.startsWith("SYSTEM:지난 밤")
                        || message.startsWith("MARK_")) {
                    p.send(message);
                } else if (p.getStatus() == PlayerStatus.DEAD && message.startsWith("SYSTEM:")) {
                    p.send(message);
                }
            } else {
                p.send(message);
            }
        }
    }

    public synchronized void broadcastPlayerList() {
        StringBuilder sb = new StringBuilder();

        List<PlayerSession> sorted = new ArrayList<>(players);
        sorted.sort(Comparator.comparingInt(PlayerSession::getPlayerNumber));

        for (PlayerSession p : sorted) {
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

            sb.append("P").append(p.getPlayerNumber())
                    .append(" - ").append(p.getName())
                    .append(" (").append(statusText).append(")")
                    .append(roleText)
                    .append(hostReadyStatus);
        }

        broadcast("PLAYERS_LIST:" + sb);
    }

    private void broadcastToMafia(String message) {
        for (PlayerSession p : players) {
            if (p.getRole() == Role.MAFIA && p.getStatus() == PlayerStatus.ALIVE) {
                p.send(message);
            }
        }
    }

    private void broadcastToMafiaExcept(PlayerSession sender, String message) {
        for (PlayerSession p : players) {
            if (p != sender && p.getRole() == Role.MAFIA && p.getStatus() == PlayerStatus.ALIVE) {
                p.send(message);
            }
        }
    }

    private void broadcastToDeadExcept(PlayerSession sender, String message) {
        for (PlayerSession p : players) {
            if (p != sender && p.getStatus() == PlayerStatus.DEAD) {
                p.send(message);
            }
        }
    }

    private void broadcastChatToAliveExcept(PlayerSession sender, String message) {
        for (PlayerSession p : players) {
            if (p != sender && p.getStatus() == PlayerStatus.ALIVE) {
                p.send(message);
            }
        }
    }

    public synchronized void sendInvestigatedMarksTo(PlayerSession session) {
        for (Map.Entry<Integer, String> e : investigatedRoleMarks.entrySet()) {
            int targetNum = e.getKey();
            String result = e.getValue();
            session.send("MARK_ROLE:P" + targetNum + ":" + result);
        }
    }
}
