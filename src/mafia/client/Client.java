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

    // 게임 상태
    private volatile boolean inGame = false;
    private volatile boolean alive = true;
    private Role myRole = Role.NONE;

    private String myNickname = "";
    private int myPlayerNumber = 0;

    private boolean isHost = false;
    private boolean isReady = false;

    // 현재 방 이름 (자동 접속 Lobby 가 기본)
    private String currentRoomName = "Lobby";

    // MARK_TARGET:P3
    private volatile String markedPlayer = "";
    // MARK_ROLE:P3:MAFIA -> 경찰 조사 결과
    private final Map<String, String> investigatedRoles = new HashMap<>();

    public Client() {
        frame = new JFrame("마피아 게임 클라이언트");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 700);

        this.gameState = new ClientGameState();

        connectionPanel = new ServerConnectionPanel(this);
        waitingGamePanel = new WaitingGamePanel(this);
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

        this.isHost = false;
        this.isReady = false;
        this.investigatedRoles.clear();
        this.currentRoomName = "Lobby";

        try {
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            // 닉네임 전송
            out.println("NICKNAME:" + nickname);

            // 수신 스레드
            new Thread(this::listenForMessages, "Client-Listen-Thread").start();

            SwingUtilities.invokeLater(this::showWaitingPanel);

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
                    this.myPlayerNumber = msg.getPlayerNumber();
                }
                break;

            case TIMER:
                if (msg.getPhase() != null && msg.getSeconds() != null) {
                    gamePanel.updateTimer(msg.getPhase(), msg.getSeconds());
                }
                break;

            case PLAYERS_LIST:
                List<String> players = msg.getPlayers();
                if (!inGame) {
                    waitingGamePanel.updatePlayerList(players);
                } else {
                    gamePanel.updatePlayerList(players);
                    gamePanel.updatePlayerMarks();
                }
                break;

            case START_GAME:
                inGame = true;
                markedPlayer = "";
                investigatedRoles.clear();
                showGamePanel();
                gamePanel.appendChatMessage("시스템", "게임이 시작되었습니다.", false);
                break;

            case YOU_DIED:
                alive = false;
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
                    markedPlayer = "P" + msg.getPlayerNumber();
                    gamePanel.updatePlayerMarks();
                }
                break;

            case MARK_ROLE:
                if (myRole == Role.POLICE &&
                        msg.getPlayerNumber() != null &&
                        msg.getRole() != null) {
                    String key = "P" + msg.getPlayerNumber();
                    String value = (msg.getRole() == Role.MAFIA) ? "MAFIA" : "CITIZEN";
                    investigatedRoles.put(key, value);
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
        // 예: [ROOM_LIST] Lobby (2명),Room1 (3명)
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
            // 채팅창에는 따로 안 찍음
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
            waitingGamePanel.updateButtons(isHost, isReady, isInLobby());
            // 채팅에도 보여주고 싶으면 아래 append 유지
            waitingGamePanel.appendChatMessage(systemMsg);
            return;
        }

        // --- HOST / GUEST 권한 부여 ---
        if (systemMsg.equals("HOST_GRANTED")) {
            isHost = true;
            isReady = true;
            waitingGamePanel.updateButtons(true, true, isInLobby());
        } else if (systemMsg.equals("GUEST_GRANTED")) {
            isHost = false;
            isReady = false;
            waitingGamePanel.updateButtons(false, false, isInLobby());
        }

        // --- 역할 안내: [역할] 당신은 'MAFIA'입니다. ---
        if (systemMsg.startsWith("[역할] 당신은 '")) {
            int start = systemMsg.indexOf('\'') + 1;
            int end   = systemMsg.lastIndexOf('\'');
            if (start > 0 && end > start) {
                String roleName = systemMsg.substring(start, end).toUpperCase();
                try {
                    myRole = Role.valueOf(roleName);
                } catch (IllegalArgumentException e) {
                    myRole = Role.NONE;
                }
                gamePanel.updateMyRoleDisplay(myRole);
            }
        }

        // --- 실제 메시지 출력 ---
        if (!inGame) {
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

        if (!inGame) {
            waitingGamePanel.appendChatMessage(message);
        } else {
            gamePanel.appendChatMessage(sender, message, isMyMessage, chatType);
        }
    }

    private void handleGeneralMessage(String raw) {
        if (!inGame) {
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

        if (msg.startsWith("/")) {
            out.println(msg);
            return;
        }

        String chatPrefix;
        if (!alive) {
            chatPrefix = "CHAT_DEAD:";
        } else {
            GamePhase phase = gamePanel.getCurrentPhase();
            if (inGame && phase == GamePhase.NIGHT) {
                if (myRole == Role.MAFIA) {
                    chatPrefix = "CHAT_MAFIA:";
                } else {
                    gamePanel.appendChatMessage("시스템", "경고: 밤에는 마피아만 대화 가능합니다.", false);
                    return;
                }
            } else {
                chatPrefix = "CHAT:";
            }
        }

        String fullMessage = chatPrefix + myNickname + ":" + msg;
        out.println(fullMessage);

        String localType;
        if (chatPrefix.equals("CHAT_DEAD:"))       localType = "DEAD";
        else if (chatPrefix.equals("CHAT_MAFIA:")) localType = "MAFIA";
        else                                       localType = "NORMAL";

        if (!inGame) {
            waitingGamePanel.appendChatMessage(msg);
        } else {
            gamePanel.appendChatMessage(myNickname, msg, true, localType);
        }
    }

    // ====== 방 관련 명령 ======

    /** 방 목록 요청: 서버에 "/room" 전송 */
    public void requestRoomList() {
        sendMessage("/room list");
    }

    /** 특정 방으로 이동(없으면 생성 후 입장) */
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

    private void resetToLobby() {
        boolean wasHost = this.isHost;

        inGame = false;
        alive = true;
        myRole = Role.NONE;
        markedPlayer = "";
        investigatedRoles.clear();
        this.myPlayerNumber = 0;

        this.isReady = wasHost;
        this.currentRoomName = "Lobby";

        SwingUtilities.invokeLater(() -> {
            gamePanel.clearGameState();
            gamePanel.updateMyRoleDisplay(Role.NONE);

            showWaitingPanel();
            waitingGamePanel.clearDisplay();
            waitingGamePanel.updateButtons(wasHost, this.isReady, true);
        });
    }

    // ==================== GamePanel 에서 사용하는 helper ====================

    public boolean hasAbility() {
        return myRole == Role.MAFIA || myRole == Role.POLICE || myRole == Role.DOCTOR;
    }

    public boolean isAlive() {
        return alive;
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

    public Role getMyRole() {
        return myRole;
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
