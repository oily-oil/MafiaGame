package mafia.client;

import mafia.Enum.GamePhase;
import mafia.Enum.MessageType;
import mafia.Enum.Role;
import mafia.client.ui.GamePanel;
import mafia.client.ui.ServerConnectionPanel;
import mafia.client.ui.WaitingGamePanel;
import mafia.server.protocol.Message;
import mafia.server.protocol.MessageCodec;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 클라이언트 메인 클래스.
 *  - 서버와의 네트워크 연결
 *  - MessageCodec.parseServerToClient 로 서버 메시지 해석
 *  - UI 패널과 상태 연동
 *
 *  상태의 실제 소스는 ClientGameState 에 둔다.
 */
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

    private final ClientGameState gameState;

    // UI/부가 상태
    private String myNickname = "";

    private boolean isHost = false;
    private boolean isReady = false;

    // 현재 방 이름 (자동 접속 Lobby 가 기본)
    private String currentRoomName = "Lobby";

    public Client() {
        frame = new JFrame("마피아 게임 클라이언트");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 700);

        this.gameState = new ClientGameState();

        connectionPanel = new ServerConnectionPanel(this);
        waitingGamePanel = new WaitingGamePanel(this);
        waitingGamePanel.setCurrentRoomName(currentRoomName);
        gamePanel = new GamePanel(this, gameState);

        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(connectionPanel, BorderLayout.CENTER);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // ==================== 서버 연결 ====================

    public void connectToServer(String nickname, String host, int port) throws IOException {
        this.host = host;
        this.port = port;
        this.myNickname = nickname;

        // 클라이언트 게임 상태 초기화
        gameState.resetForNewConnection(nickname);

        this.isHost = false;
        this.isReady = false;
        this.currentRoomName = "Lobby";
        waitingGamePanel.setCurrentRoomName(currentRoomName);

        try {
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            // 닉네임 전송
            out.println("NICKNAME:" + nickname);

            // 수신 스레드
            new Thread(this::listenForMessages, "Client-Listen-Thread").start();

            SwingUtilities.invokeLater(this::showWaitingPanel);

            // 🔹 Lobby 자동 입장 후, 한 번 방 목록 요청
            requestRoomList();

        } catch (IOException e) {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    private void listenForMessages() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                final String raw = line;
                System.out.println("[SERVER] " + raw);

                SwingUtilities.invokeLater(() -> {
                    Message msg = MessageCodec.parseServerToClient(raw);
                    handleServerMessage(msg);
                });
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

    private void handleServerMessage(Message msg) {
        MessageType type = msg.getType();

        switch (type) {
            case PLAYER_NUM:
                if (msg.getPlayerNumber() != null) {
                    gameState.setMyPlayerNumber(msg.getPlayerNumber());
                }
                break;

            case TIMER:
                if (msg.getPhase() != null && msg.getSeconds() != null) {
                    gamePanel.updateTimer(msg.getPhase(), msg.getSeconds());
                }
                break;

            case PLAYERS_LIST:
                List<String> players = msg.getPlayers();
                if (!gameState.isInGame()) {
                    waitingGamePanel.updatePlayerList(players);
                } else {
                    gamePanel.updatePlayerList(players);
                    gamePanel.updatePlayerMarks();
                }
                break;

            case START_GAME:
                // 게임 시작 상태 세팅
                gameState.setInGame(true);
                gameState.setAlive(true);
                gameState.setMarkedPlayer("");
                gameState.getInvestigatedRoles().clear();

                showGamePanel();
                gamePanel.appendChatMessage("시스템", "게임이 시작되었습니다.", false);
                break;

            case YOU_DIED:
                gameState.setAlive(false);
                gamePanel.appendChatMessage("시스템", "⚠ 당신은 사망했습니다. 관전자 모드로 전환됩니다.", false);
                break;

            case GAME_OVER:
                String content = msg.getText() != null ? msg.getText() : "";
                gamePanel.appendChatMessage("시스템", "[게임 종료] " + content, false);
                JOptionPane.showMessageDialog(frame, "게임이 종료되었습니다: " + content);
                resetToLobby();
                break;

            case SYSTEM:
                handleSystemMessage(msg.getText());
                break;

            case CHAT:
            case CHAT_MAFIA:
            case CHAT_DEAD:
                handleChatMessageFromServer(msg);
                break;

            case MARK_TARGET:
                if (msg.getPlayerNumber() != null && msg.getPlayerNumber() > 0) {
                    String p = "P" + msg.getPlayerNumber();
                    gameState.setMarkedPlayer(p);
                    gamePanel.updatePlayerMarks();
                }
                break;

            case MARK_ROLE:
                // 🔹 경찰에게만 조사 결과 반영
                if (gameState.getMyRole() == Role.POLICE &&
                        msg.getPlayerNumber() != null &&
                        msg.getRole() != null) {

                    String key = "P" + msg.getPlayerNumber();
                    String value = (msg.getRole() == Role.MAFIA) ? "MAFIA" : "CITIZEN";

                    gameState.getInvestigatedRoles().put(key, value);
                    gamePanel.updatePlayerMarks();
                }
                break;

            case ROLE_INFO:
            case UNKNOWN:
            default:
                handleGeneralMessage(msg.getRaw());
                break;
        }
    }

    private void handleSystemMessage(String systemMsg) {
        if (systemMsg == null) systemMsg = "";

        // --- 방 목록(ROOM_LIST) 처리 ---
        // (이 부분은 이전에 이미 잘 작동하고 있다고 하셨으니 그대로 유지)
        if (systemMsg.startsWith("[ROOM_LIST]")) {
            String payload = systemMsg.substring("[ROOM_LIST]".length()).trim();
            List<String> rooms = new ArrayList<>();
            if (!payload.isEmpty()) {
                String[] tokens = payload.split(",");
                for (String t : tokens) {
                    String s = t.trim();
                    if (!s.isEmpty()) rooms.add(s);
                }
            }
            waitingGamePanel.updateRoomList(rooms);
            return;
        }

        // --- 방 이동 메시지 처리 ---
        // 예: [방이동] 'Room1' 방에 입장했습니다.
        if (systemMsg.startsWith("[방이동]")) {
            int s = systemMsg.indexOf('\'');
            int e = (s >= 0) ? systemMsg.indexOf('\'', s + 1) : -1;
            if (s >= 0 && e > s) {
                currentRoomName = systemMsg.substring(s + 1, e);
            }
            waitingGamePanel.setCurrentRoomName(currentRoomName);
            waitingGamePanel.updateButtons(isHost, isReady, isInLobby());
            waitingGamePanel.appendChatMessage(systemMsg);

            // 방 이동 직후, 내 기준으로 방 목록 다시 요청 → 내 방 버튼 비활성/표시 동기화
            requestRoomList();
            return;
        }

        // --- HOST / GUEST 권한 부여 ---
        if (systemMsg.equals("HOST_GRANTED")) {
            isHost = true;
            isReady = true;
            gameState.setHost(true);
            gameState.setReady(true);
            waitingGamePanel.updateButtons(true, true, isInLobby());
        } else if (systemMsg.equals("GUEST_GRANTED")) {
            isHost = false;
            isReady = false;
            gameState.setHost(false);
            gameState.setReady(false);
            waitingGamePanel.updateButtons(false, false, isInLobby());
        }

        // --- 역할 안내: [역할] 당신은 'MAFIA'입니다. ---
        if (systemMsg.startsWith("[역할] 당신은 '")) {
            int start = systemMsg.indexOf('\'') + 1;
            int end   = systemMsg.lastIndexOf('\'');
            if (start > 0 && end > start) {
                String roleName = systemMsg.substring(start, end).toUpperCase();
                Role role;
                try {
                    role = Role.valueOf(roleName);
                } catch (IllegalArgumentException e) {
                    role = Role.NONE;
                }

                // 🔹 상태를 ClientGameState 에 반영
                gameState.setMyRole(role);
                gamePanel.updateMyRoleDisplay(role);
            }
        }

        // --- 실제 메시지 출력 ---
        if (!gameState.isInGame()) {
            waitingGamePanel.appendChatMessage(systemMsg);
        } else {
            gamePanel.appendChatMessage("시스템", systemMsg, false);
            gamePanel.updatePlayerMarks();
        }
    }

    private void handleChatMessageFromServer(Message msg) {
        String sender  = msg.getSender() != null ? msg.getSender() : "";
        String message = msg.getText()   != null ? msg.getText()   : "";
        boolean isMyMessage = sender.equals(myNickname);

        String chatType;
        if (msg.getType() == MessageType.CHAT_MAFIA)      chatType = "MAFIA";
        else if (msg.getType() == MessageType.CHAT_DEAD)  chatType = "DEAD";
        else                                              chatType = "NORMAL";

        if (!gameState.isInGame()) {
            waitingGamePanel.appendChatMessage(message);
        } else {
            gamePanel.appendChatMessage(sender, message, isMyMessage, chatType);
        }
    }

    private void handleGeneralMessage(String raw) {
        if (!gameState.isInGame()) {
            waitingGamePanel.appendChatMessage(raw);
        } else {
            gamePanel.appendChatMessage("시스템", raw, false);
        }
    }

    // ==================== 명령 / 채팅 전송 ====================

    public void handleReadyClick() {
        if (!isHost) {
            sendMessage("/ready");
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

    /**
     * 공용 전송 함수.
     *  - "/" 로 시작하면 그대로 명령
     *  - 이외는 CHAT/CHAT_MAFIA/CHAT_DEAD 로 래핑
     */
    public void sendMessage(String msg) {
        if (out == null) return;
        if (msg == null) return;
        msg = msg.trim();
        if (msg.isEmpty()) return;

        // 명령
        if (msg.startsWith("/")) {
            out.println(msg);
            return;
        }

        // 채팅
        String chatPrefix;
        if (!gameState.isAlive()) {
            // 사망자 채팅
            chatPrefix = "CHAT_DEAD:";
        } else {
            GamePhase phase = gamePanel.getCurrentPhase();
            if (gameState.isInGame() && phase == GamePhase.NIGHT) {
                // 밤 채팅 → 마피아만
                if (gameState.getMyRole() == Role.MAFIA) {
                    chatPrefix = "CHAT_MAFIA:";
                } else {
                    gamePanel.appendChatMessage("시스템", "경고: 밤에는 마피아만 대화 가능합니다.", false);
                    return;
                }
            } else {
                // 일반 낮/대기 채팅
                chatPrefix = "CHAT:";
            }
        }

        String fullMessage = chatPrefix + myNickname + ":" + msg;
        out.println(fullMessage);

        String localType;
        if (chatPrefix.equals("CHAT_DEAD:"))       localType = "DEAD";
        else if (chatPrefix.equals("CHAT_MAFIA:")) localType = "MAFIA";
        else                                       localType = "NORMAL";

        if (!gameState.isInGame()) {
            waitingGamePanel.appendChatMessage(msg);
        } else {
            gamePanel.appendChatMessage(myNickname, msg, true, localType);
        }
    }

    // ====== 방 관련 명령 ======

    /** 방 목록 요청: 서버에 "/room list" 전송 */
    public void requestRoomList() {
        sendMessage("/room list");
    }

    /** 특정 방으로 이동 */
    public void joinRoom(String roomName) {
        if (roomName == null) return;
        roomName = roomName.trim();
        if (roomName.isEmpty()) return;
        sendMessage("/room join " + roomName);
    }

    public void createRoom(String roomName) {
        if (roomName == null) return;
        roomName = roomName.trim();
        if (roomName.isEmpty()) return;
        sendMessage("/room create " + roomName);
    }

    public boolean isInLobby() {
        return "Lobby".equals(currentRoomName);
    }

    // ==================== 화면 전환 & 리셋 ====================

    public void showWaitingPanel() {
        frame.getContentPane().removeAll();
        frame.getContentPane().add(waitingGamePanel, BorderLayout.CENTER);
        frame.revalidate();
        frame.repaint();
    }

    public void showGamePanel() {
        frame.getContentPane().removeAll();
        frame.getContentPane().add(gamePanel, BorderLayout.CENTER);
        frame.revalidate();
        frame.repaint();
    }

    /**
     * 게임이 종료되었을 때 호출.
     *  - Lobby 로 강제 이동시키지 않고, 서버에서 유지하는 "현재 방"을 그대로 사용
     *  - 클라이언트 UI만 대기 화면으로 되돌린다.
     */
    private void resetToLobby() {
        boolean wasHost = this.isHost;

        // ClientGameState 쪽으로 전체 초기화 위임
        gameState.resetForLobbyAfterGame(wasHost);

        // host/ready 플래그는 gameState 기준으로 다시 동기화
        this.isHost = gameState.isHost();
        this.isReady = gameState.isReady();

        SwingUtilities.invokeLater(() -> {
            gamePanel.clearGameState();
            gamePanel.updateMyRoleDisplay(Role.NONE);

            showWaitingPanel();
            waitingGamePanel.clearDisplay();
            waitingGamePanel.setCurrentRoomName(currentRoomName);
            waitingGamePanel.updateButtons(isHost, isReady, isInLobby());
        });
    }

    // ==================== GamePanel / 외부용 helper ====================

    public boolean hasAbility() {
        return gameState.hasAbility();
    }

    public boolean isAlive() {
        return gameState.isAlive();
    }

    public int getMyPlayerNumber() {
        return gameState.getMyPlayerNumber();
    }

    public String getMarkedPlayer() {
        return gameState.getMarkedPlayer();
    }

    public Map<String, String> getInvestigatedRoles() {
        return gameState.getInvestigatedRoles();
    }

    public Role getMyRole() {
        return gameState.getMyRole();
    }

    /** "P3 - 닉네임 ..." 에서 3 추출 */
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
