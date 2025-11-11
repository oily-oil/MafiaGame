import java.util.*;

public class GameEngine {
    private final Room room;
    private final List<Player> players;
    private final Timer timer;

    private enum Phase { DAY, NIGHT }
    private Phase currentPhase;

    public GameEngine(Room room) {
        this.room = room;
        this.players = new ArrayList<>();
        // Room의 ClientHandler 목록을 Player 객체로 변환
        for (String nickname : room.getClients().keySet()) {
            players.add(new Player(nickname));
        }
        this.timer = new Timer(this);
    }

    // 게임 시작 (Room에서 호출됨)
    public void startGame() {
        distributeRoles();
        room.broadcast("SERVER: 직업 분배 완료!");

        // 각 플레이어에게 자신의 직업을 개별적으로 알림
        sendRolesToPlayers();

        startDayPhase();
    }
    
    // 인원에 맞는 직업 분배 [cite: 1]
    private void distributeRoles() {
        int count = players.size();
        List<Role> roles = new ArrayList<>();

        if (count >= 8) { // 8명 [cite: 1]
            roles.addAll(Arrays.asList(Role.MAFIA, Role.MAFIA, Role.POLICE, Role.DOCTOR,
                    Role.CITIZEN, Role.CITIZEN, Role.CITIZEN, Role.CITIZEN));
        }else if (count == 7) { // 7명 [cite: 1]
            roles.addAll(Arrays.asList(Role.MAFIA, Role.MAFIA, Role.POLICE, Role.DOCTOR,
                    Role.CITIZEN, Role.CITIZEN, Role.CITIZEN));
        }else if (count >= 6) { // 6명 [cite: 1]
            roles.addAll(Arrays.asList(Role.MAFIA, Role.MAFIA, Role.POLICE, Role.DOCTOR,
                    Role.CITIZEN, Role.CITIZEN));
        }else if (count == 5) { // 5명 [cite: 1]
            roles.addAll(Arrays.asList(Role.MAFIA, Role.POLICE, Role.CITIZEN, Role.CITIZEN, Role.CITIZEN));
        }else if (count == 4) { // 4명 [cite: 1]
            roles.addAll(Arrays.asList(Role.MAFIA, Role.POLICE, Role.CITIZEN, Role.CITIZEN));
        } else {
            //[cite_start] 인원수 4명 이하일 경우 시작 불가능 [cite: 8] (Room에서 이미 검사해야 함)
            return;
        }

        Collections.shuffle(roles);
        for (int i = 0; i < count; i++) {
            players.get(i).setRole(roles.get(i));
        }
    }

    // 각 플레이어에게 자신의 직업을 알려줌
    private void sendRolesToPlayers() {
        Map<String, ClientHandler> handlers = room.getClients();
        for (Player player : players) {
            ClientHandler handler = handlers.get(player.getNickname());
            if (handler != null) {
                handler.sendMessage("SERVER: 당신의 직업은 **" + player.getRole().getKoreanName() + "** 입니다.");
            }
        }
    }

    // 낮 시작 (3분) [cite: 2]
    public void startDayPhase() {
        currentPhase = Phase.DAY;
        room.broadcast("\n☀️ **[낮]**이 되었습니다. (3분) 🗣️ 토론을 시작하고 투표해주세요.");
        // 투표 명령 안내: VOTE [닉네임]
        timer.startTimer(3 * 60);
    }

    // 밤 시작 (2분) [cite: 2]
    public void startNightPhase() {
        currentPhase = Phase.NIGHT;
        room.broadcast("\n🌙 **[밤]**이 되었습니다. (2분) 모든 플레이어는 눈을 감고 직업 능력을 사용해주세요.");
        // 직업 능력 사용 안내: KILL [닉네임], SAVE [닉네임], CHECK [닉네임]
        // 마피아 끼리 밤에 채팅이 가능하다[cite: 4]. (ClientHandler에서 밤에 마피아 채팅 중계 로직 필요)
        timer.startTimer(2 * 60);
    }

    // 낮/밤 단계 종료 시 호출됨
    public void endPhase() {
        if (currentPhase == Phase.DAY) {
            processDayVoting();
            checkWinCondition();
            startNightPhase();
        } else { // Night Phase
            processNightActions();
            checkWinCondition();
            startDayPhase();
        }
    }

    // 낮 투표 처리 로직 (가장 많은 표를 받은 사람을 사망 처리)
    private void processDayVoting() {
        // 투표 처리 로직 구현 (과반수 투표, 최다 득표자 결정 등)
        room.broadcast("SERVER: 낮 투표 결과 발표!");
        // ... (사망 처리 로직) ...
    }

    // 밤 능력 사용 처리 로직
    private void processNightActions() {
        //[cite_start] 1. 마피아가 죽일 사람 선택 [cite: 4]
        //[cite_start] 2. 의사가 살릴 사람 선택 [cite: 6]
        //[cite_start] 3. 경찰이 조사할 사람 선택 [cite: 5]

        // 4. 사망자 결정
        String mafiaTarget = getTargetByRole(Role.MAFIA);
        String doctorTarget = getTargetByRole(Role.DOCTOR);

        boolean saved = mafiaTarget != null && mafiaTarget.equals(doctorTarget);
        
        //[cite_start] 의사와 선택이 겹칠 경우, 그 사람은 죽지 않고 생존하였다는 메시지 출력[cite: 4].
        if (saved) {
            room.broadcast("SERVER: 의사의 활약으로 살해 시도가 무산되었습니다!");
        } else if (mafiaTarget != null) {
            handlePlayerDeath(mafiaTarget);
        }

        // 5. 경찰 결과 통보
        String policeTarget = getTargetByRole(Role.POLICE);
        if (policeTarget != null) {
            Player targetPlayer = getPlayerByNickname(policeTarget);
            String result = (targetPlayer != null && targetPlayer.getRole() == Role.MAFIA) ? "마피아입니다." : "시민입니다.";
            //[cite_start] 경찰에게만 개별 통보: "마피아입니다. 시민입니다." [cite: 5, 6]
            sendRoleMessage(Role.POLICE, policeTarget + "님은 " + result);
        }

        // 능력 사용 후 모든 플레이어의 Target 초기화
        players.forEach(Player::resetTarget);
    }

    // 특정 역할의 선택 대상 닉네임 반환
    private String getTargetByRole(Role role) {
        return players.stream()
                .filter(p -> p.getRole() == role && p.isAlive())
                .map(Player::getChosenTarget)
                .filter(Objects::nonNull)
                .findFirst() // 복수 마피아의 경우 투표를 통해 한 명의 타겟을 정해야 함
                .orElse(null);
    }

    // 특정 역할에게만 메시지 전송
    private void sendRoleMessage(Role role, String message) {
        for (Player player : players) {
            if (player.getRole() == role && player.isAlive()) {
                ClientHandler handler = room.getClients().get(player.getNickname());
                if (handler != null) {
                    handler.sendMessage("SERVER (비밀): " + message);
                }
            }
        }
    }

    // 플레이어 사망 처리
    private void handlePlayerDeath(String nickname) {
        Player p = getPlayerByNickname(nickname);
        if (p != null && p.isAlive()) {
            p.setDead();
            room.broadcast("SERVER: " + nickname + "님이 사망했습니다.");
            // 클라이언트 종료는 게임에서 사망으로 처리해야 합니다. 
            // 이 로직은 ClientHandler가 연결 종료 시 호출해야 합니다.
        }
    }
    
    //[cite_start] 승리 조건 확인 [cite: 3]
    private void checkWinCondition() {
        long aliveMafia = players.stream().filter(p -> p.isAlive() && p.getRole().getTeam() == Role.Team.MAFIA).count();
        long aliveCitizens = players.stream().filter(p -> p.isAlive() && p.getRole().getTeam() == Role.Team.CITIZEN).count();
        long totalAlive = aliveMafia + aliveCitizens;
        
        //[cite_start] 마피아 팀 승리: 남은 인원중 마피아 팀이 과반수 이상 [진행자의 절반]인 경우 승리[cite: 3].
        if (aliveMafia >= (totalAlive + 1) / 2.0) { // 과반수 확인 (절반 초과)
            room.broadcast("🎉 **마피아 팀 승리!** 🎉 남은 마피아 수: " + aliveMafia);
            endGame();
        } 
        //[cite_start] 시민 팀 승리: 모든 마피아팀을 제거한 경우 승리[cite: 3].
        else if (aliveMafia == 0) {
            room.broadcast("🎉 **시민 팀 승리!** 🎉 모든 마피아를 검거했습니다.");
            endGame();
        }
    }

    private void endGame() {
        room.broadcast("SERVER: 게임이 종료되었습니다. 🎮");
        timer.stopTimer();
        // RoomManager에게 방 종료/제거를 알리는 로직 필요
    }

    // 헬퍼 함수
    private Player getPlayerByNickname(String nickname) {
        return players.stream().filter(p -> p.getNickname().equals(nickname)).findFirst().orElse(null);
    }

    // 플레이어의 행동(투표/능력사용)을 처리하는 공개 함수 (ClientHandler에서 호출)
    public void handleAction(String nickname, String command, String targetNickname) {
        Player player = getPlayerByNickname(nickname);
        if (player == null || !player.isAlive()) return;

        if (currentPhase == Phase.DAY && command.equals("VOTE")) {
            // 투표 로직: player.setChosenTarget(targetNickname) 후 투표 수 집계
            player.setChosenTarget(targetNickname);
            room.broadcast("SERVER: " + nickname + "님이 투표를 완료했습니다.");
            // 과반수 찬성 시 Timer.requestSkip() 호출 로직 필요
        }
        // 밤 능력 사용 로직
        else if (currentPhase == Phase.NIGHT) {
            if (player.getRole() == Role.MAFIA && command.equals("KILL")) {
                player.setChosenTarget(targetNickname);
            } else if (player.getRole() == Role.DOCTOR && command.equals("SAVE")) {
                player.setChosenTarget(targetNickname);
            } else if (player.getRole() == Role.POLICE && command.equals("CHECK")) {
                player.setChosenTarget(targetNickname);
            }
        }
    }
}