import javax.swing.*;
import java.io.*;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class Client {

    private String host;
    private int port;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    private JFrame frame;

    private ServerConnectionPanel connectionPanel;
    private WaitingGamePanel waitingGamePanel;
    private GamePanel gamePanel;

    private volatile boolean inGame = false;
    private volatile boolean isAlive = true;

    // ✅ 문자열이 아니라 Role enum 으로 관리
    private Role myRole = Role.NONE;

    private String myNickname = "";
    private int myPlayerNumber = 0;

    private boolean isHost = false;
    private boolean isReady = false;

    // 마피아/의사가 지목한 대상 (서버에서 MARK_TARGET: 으로 내려줌)
    private volatile String markedPlayer = "";

    // 경찰이 조사해서 알게 된 플레이어 역할 정보 (key: "P2", value: "MAFIA"/"CITIZEN")
    private final Map<String, String> investigatedRoles = new HashMap<>();

    public Client() {
        frame = new JFrame("마피아 게임 클라이언트");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 700);

        connectionPanel = new ServerConnectionPanel(this);
        waitingGamePanel = new WaitingGamePanel(this);
        gamePanel = new GamePanel(this);

        frame.getContentPane().add(connectionPanel);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public void connectToServer(String nickname, String host, int port) throws IOException {
        this.host = host;
        this.port = port;
        this.myNickname = nickname;

        this.isHost = false;
        this.isReady = false;

        this.investigatedRoles.clear();
        this.markedPlayer = "";
        this.myRole = Role.NONE;
        this.inGame = false;
        this.isAlive = true;

        try {
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

            // 서버에 닉네임 전달
            out.println("NICKNAME:" + nickname);

            // 서버 수신 스레드 시작
            new Thread(this::listenForMessages, "Client-Receive-Thread").start();

            SwingUtilities.invokeLater(this::showWaitingPanel);

        } catch (IOException e) {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    /**
     * 서버 메시지 수신 루프
     */
    private void listenForMessages() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                final Message msg = MessageCodec.parseServerToClient(line);
                SwingUtilities.invokeLater(() -> handleServerMessage(msg));
            }
        } catch (Exception e) {
            System.out.println("서버 수신 루프 종료: " + e.getMessage());
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(frame, "서버 연결이 끊겼습니다.");
                resetToLobby();
            });
        }
    }

    /**
     * 서버 → 클라이언트 메시지를 타입별로 처리
     */
    private void handleServerMessage(Message message) {
        MessageType type = message.getType();

        switch (type) {
            case PLAYER_NUM:
                handlePlayerNum(message.getPayload());
                break;

            case TIMER:
                handleTimer(message.getPayload());
                break;

            case PLAYERS_LIST:
                handlePlayersList(message.getPayload());
                break;

            case START_GAME:
                handleStartGame(message.getPayload());
                break;

            case ROLE:
                // 현재는 레거시 ROLE: 메시지를 사용하지 않음 (SERVER에서 SYSTEM:[역할]… 으로 역할 전달)
                break;

            case YOU_DIED:
                handleYouDied();
                break;

            case GAME_OVER:
                handleGameOver(message.getPayload());
                break;

            case SYSTEM:
                handleSystemMessage(message.getPayload().trim());
                break;

            case CHAT:
            case CHAT_MAFIA:
            case CHAT_DEAD:
                handleChatMessage(message);
                break;

            case MARK_TARGET:
                handleMarkTarget(message.getPayload());
                break;

            case MARK_ROLE:
                handleMarkRole(message.getPayload());
                break;

            case UNKNOWN:
            default:
                handleGeneralMessage(message.getRaw());
                break;
        }
    }

    // ===== 개별 타입 핸들러들 =====

    private void handlePlayerNum(String payload) {
        try {
            this.myPlayerNumber = Integer.parseInt(payload.trim());
        } catch (NumberFormatException ignored) {}
    }

    private void handleTimer(String payload) {
        // payload 형식: PHASE:SECONDS  예) "DAY:48"
        String[] parts = payload.split(":");
        if (parts.length == 2) {
            String phaseStr = parts[0];
            GamePhase phase;
            try {
                phase = GamePhase.valueOf(phaseStr);
            } catch (IllegalArgumentException e) {
                phase = GamePhase.WAITING;
            }

            try {
                int secondsLeft = Integer.parseInt(parts[1]);
                // ✅ GamePanel 의 시그니처에 맞게 GamePhase enum 전달
                gamePanel.updateTimer(phase, secondsLeft);
            } catch (NumberFormatException ignored) {}
        }
    }

    private void handlePlayersList(String payload) {
        String list = payload;
        List<String> players = list.isEmpty()
                ? List.of()
                : Arrays.asList(list.split(","));

        if (!inGame) {
            waitingGamePanel.updatePlayerList(players);
        } else {
            gamePanel.updatePlayerList(players);
            gamePanel.updatePlayerMarks();
        }
    }

    private void handleStartGame(String payload) {
        inGame = true;
        markedPlayer = "";
        investigatedRoles.clear();
        showGamePanel();
        gamePanel.appendChatMessage("시스템", "게임이 시작되었습니다.", false);
    }

    private void handleYouDied() {
        isAlive = false;
        gamePanel.appendChatMessage("시스템", "⚠ 당신은 사망했습니다. 관전자 모드로 전환됩니다.", false);
    }

    private void handleGameOver(String payload) {
        String content = payload.trim();
        gamePanel.appendChatMessage("시스템", "[게임 종료] " + content, false);
        JOptionPane.showMessageDialog(frame, "게임이 종료되었습니다: " + content);
        resetToLobby();
    }

    private void handleSystemMessage(String systemMsg) {
        // 방장/게스트 권한 갱신
        if (systemMsg.equals("HOST_GRANTED")) {
            isHost = true;
            isReady = true;
            waitingGamePanel.updateButtons(true, true);
        } else if (systemMsg.equals("GUEST_GRANTED")) {
            isHost = false;
            isReady = false;
            waitingGamePanel.updateButtons(false, false);
        }

        // 역할 배정 메시지 처리
        // 예시: "[역할] 당신은 'MAFIA'입니다."
        if (systemMsg.startsWith("[역할] 당신은 '")) {
            int start = systemMsg.indexOf("'") + 1;
            int end = systemMsg.lastIndexOf("'");
            if (start > 0 && end > start) {
                String roleName = systemMsg.substring(start, end).toUpperCase();
                try {
                    myRole = Role.valueOf(roleName);
                } catch (IllegalArgumentException e) {
                    myRole = Role.NONE;
                }
                // ✅ GamePanel 의 시그니처: Role 사용
                gamePanel.updateMyRoleDisplay(myRole);
            }
        }

        if (!inGame) {
            waitingGamePanel.appendChatMessage(systemMsg);
        } else {
            gamePanel.appendChatMessage("시스템", systemMsg, false);
            gamePanel.updatePlayerMarks();
        }
    }

    private void handleChatMessage(Message message) {
        // payload 형식: "닉네임:내용"
        String content = message.getPayload();
        int colonIndex = content.indexOf(':');

        if (colonIndex <= 0) {
            handleGeneralMessage(message.getRaw());
            return;
        }

        String sender = content.substring(0, colonIndex).trim();
        String msgText = content.substring(colonIndex + 1).trim();

        boolean isMyMessage = sender.equals(myNickname);

        String chatType;
        if (message.getType() == MessageType.CHAT_MAFIA) {
            chatType = "MAFIA";
        } else if (message.getType() == MessageType.CHAT_DEAD) {
            chatType = "DEAD";
        } else {
            chatType = "NORMAL";
        }

        if (!inGame) {
            // 로비에서는 기존과 동일하게 "내용만" 표시
            waitingGamePanel.appendChatMessage(msgText);
        } else {
            // 인게임에선 GamePanel 말풍선 UI 사용
            gamePanel.appendChatMessage(sender, msgText, isMyMessage, chatType);
        }
    }

    private void handleMarkTarget(String payload) {
        // payload 예: "P2"
        markedPlayer = payload.trim();
        gamePanel.updatePlayerMarks();
    }

    private void handleMarkRole(String payload) {
        // 서버에서 내려오는 형식: "P2:MAFIA" or "P3:CITIZEN"
        // ✅ 이제 myRole 이 Role enum 이므로, POLICE 체크도 enum 비교로
        if (myRole != Role.POLICE) {
            return;
        }

        String data = payload;
        String[] parts = data.split(":");
        if (parts.length == 2) {
            investigatedRoles.put(parts[0], parts[1]); // key: "P2"
            gamePanel.updatePlayerMarks();
        }
    }

    private void handleGeneralMessage(String msg) {
        if (!inGame) {
            waitingGamePanel.appendChatMessage(msg);
        } else {
            gamePanel.appendChatMessage("시스템", msg, false);
        }
    }

    // ===== 버튼/명령 관련 =====

    public void handleReadyClick() {
        if (!isHost) {
            sendMessage("/ready");
            isReady = !isReady;
            waitingGamePanel.updateButtons(isHost, isReady);
        } else {
            System.out.println("방장은 준비 상태를 변경할 수 없습니다.");
        }
    }

    public void handleStartClick() {
        if (isHost) {
            sendMessage("/start");
        } else {
            System.out.println("방장만 게임을 시작할 수 있습니다.");
        }
    }

    public void sendMessage(String msg) {
        if (out == null) return;
        if (msg == null) return;
        msg = msg.trim();
        if (msg.isEmpty()) return;

        // 슬래시 명령은 그대로 서버로
        if (msg.startsWith("/")) {
            out.println(msg);
            return;
        }

        // 일반 채팅 → prefix + 닉네임:내용 형태
        String chatPrefix;

        if (!isAlive) {
            chatPrefix = "CHAT_DEAD:";
        }
        else if (inGame && gamePanel.getCurrentPhase() == GamePhase.NIGHT) {
            if (myRole == Role.MAFIA) {
                chatPrefix = "CHAT_MAFIA:";
            } else {
                gamePanel.appendChatMessage("시스템", "경고: 밤에는 마피아만 대화 가능합니다.", false);
                return;
            }
        }
        else {
            chatPrefix = "CHAT:";
        }

        String fullMessage = chatPrefix + myNickname + ":" + msg;
        out.println(fullMessage);

        // 클라이언트 측 로컬 표시 타입
        String localType;
        if (chatPrefix.equals("CHAT_DEAD:")) {
            localType = "DEAD";
        } else if (chatPrefix.equals("CHAT_MAFIA:")) {
            localType = "MAFIA";
        } else {
            localType = "NORMAL";
        }

        if (!inGame) {
            // 로비 채팅: 기존과 동일하게 내용만 표시
            waitingGamePanel.appendChatMessage(msg);
        } else {
            // 인게임 채팅: 말풍선 UI
            gamePanel.appendChatMessage(myNickname, msg, true, localType);
        }
    }

    // ===== 화면 전환 / 상태 초기화 =====

    public void showWaitingPanel() {
        frame.getContentPane().removeAll();
        frame.getContentPane().add(waitingGamePanel);
        frame.revalidate();
        frame.repaint();
    }

    public void showGamePanel() {
        frame.getContentPane().removeAll();
        frame.getContentPane().add(gamePanel);
        frame.revalidate();
        frame.repaint();
    }

    private void resetToLobby() {
        boolean wasHost = this.isHost;

        inGame = false;
        isAlive = true;
        myRole = Role.NONE;
        markedPlayer = "";
        investigatedRoles.clear();
        this.myPlayerNumber = 0;

        this.isReady = wasHost;

        SwingUtilities.invokeLater(() -> {
            gamePanel.clearGameState();
            // ✅ GamePanel 의 시그니처에 맞춰 Role.NONE 전달
            gamePanel.updateMyRoleDisplay(Role.NONE);

            showWaitingPanel();
            waitingGamePanel.clearDisplay();
            waitingGamePanel.updateButtons(wasHost, this.isReady);
        });
    }

    // ===== GamePanel / WaitingGamePanel 이 사용하는 getter 들 =====

    public boolean hasAbility() {
        return myRole == Role.MAFIA || myRole == Role.POLICE || myRole == Role.DOCTOR;
    }

    public boolean isAlive() {
        return isAlive;
    }

    public int getMyPlayerNumber() {
        return myPlayerNumber;
    }

    public String getMarkedPlayer() {
        return markedPlayer;
    }

    public Map<String, String> getInvestigatedRoles() {
        return investigatedRoles;
    }

    // ✅ 이제 Role 타입 반환
    public Role getMyRole() {
        return myRole;
    }

    /**
     * "P2 - 닉네임 (생존)" 같은 문자열에서 2를 추출하는 메서드
     * GamePanel / WaitingGamePanel 에서 그대로 사용하므로 시그니처 유지
     */
    public String extractPlayerNumber(String playerString) {
        try {
            if (playerString.startsWith("P")) {
                int dashIndex = playerString.indexOf(" -");
                if (dashIndex != -1) {
                    return playerString.substring(1, dashIndex);
                }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Client::new);
    }
}
