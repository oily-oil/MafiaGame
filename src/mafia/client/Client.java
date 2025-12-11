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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // 🔸 클라이언트 상태 분리 객체 (GamePanel 생성자에 같이 넘겨줌)
    private final ClientGameState gameState;

    // 클라이언트 로컬 상태 (네트워크/로직에서 사용)
    private volatile boolean inGame = false;
    private volatile boolean alive = true;
    private Role myRole = Role.NONE;

    private String myNickname = "";
    private int myPlayerNumber = 0;

    private boolean isHost = false;
    private boolean isReady = false;

    // MARK_TARGET:P3 처리를 위한 상태
    private volatile String markedPlayer = ""; // "P3" 형태
    // MARK_ROLE:P3:MAFIA -> 경찰 전용 조사 결과
    private final Map<String, String> investigatedRoles = new HashMap<>();

    public Client() {
        frame = new JFrame("마피아 게임 클라이언트");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 700);

        // 🔸 ClientGameState 생성
        this.gameState = new ClientGameState();

        connectionPanel = new ServerConnectionPanel(this);
        waitingGamePanel = new WaitingGamePanel(this);

        // 🔸 GamePanel에 Client와 gameState 둘 다 전달
        gamePanel = new GamePanel(this, gameState);

        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(connectionPanel, BorderLayout.CENTER);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // 초기 state 동기화
        syncAllStateToGameState();
    }

    // ==================== 서버 연결 ====================

    public void connectToServer(String nickname, String host, int port) throws IOException {
        this.host = host;
        this.port = port;
        this.myNickname = nickname;

        this.isHost = false;
        this.isReady = false;
        this.investigatedRoles.clear();

        // 기본 상태 초기화
        inGame = false;
        alive = true;
        myRole = Role.NONE;
        myPlayerNumber = 0;
        markedPlayer = "";
        syncAllStateToGameState();

        try {
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            // 닉네임 전송
            out.println("NICKNAME:" + nickname);

            // 수신 스레드 시작
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
                    gameState.setMyPlayerNumber(this.myPlayerNumber);
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
                // 🔸 gameState도 같이 초기화
                gameState.setMarkedPlayer("");
                gameState.getInvestigatedRoles().clear();
                gameState.setAlive(true);
                gameState.setCurrentPhase(GamePhase.NIGHT); // 곧 TIMER로 덮어씌워질 것

                showGamePanel();
                gamePanel.appendChatMessage("시스템", "게임이 시작되었습니다.", false);
                break;

            case YOU_DIED:
                alive = false;
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
                    markedPlayer = "P" + msg.getPlayerNumber();
                    gameState.setMarkedPlayer(markedPlayer);
                    gamePanel.updatePlayerMarks();
                }
                break;

            case MARK_ROLE:
                if (myRole == Role.POLICE &&
                        msg.getPlayerNumber() != null &&
                        msg.getRole() != null) {

                    String key = "P" + msg.getPlayerNumber();
                    String value = (msg.getRole() == Role.MAFIA) ? "MAFIA" : "CITIZEN";

                    // 클라이언트 로컬 + gameState 모두 반영
                    investigatedRoles.put(key, value);
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

        if (systemMsg.equals("HOST_GRANTED")) {
            isHost = true;
            isReady = true;
            waitingGamePanel.updateButtons(true, true);
        } else if (systemMsg.equals("GUEST_GRANTED")) {
            isHost = false;
            isReady = false;
            waitingGamePanel.updateButtons(false, false);
        }

        // [역할] 당신은 'MAFIA'입니다. 형식
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

                // 🔸 gameState에도 역할 반영
                gameState.setMyRole(myRole);

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

    private void handleChatMessageFromServer(Message msg) {
        String sender  = msg.getSender() != null ? msg.getSender() : "";
        String message = msg.getText()   != null ? msg.getText()   : "";
        boolean isMyMessage = sender.equals(myNickname);

        String chatType;
        if (msg.getType() == MessageType.CHAT_MAFIA)      chatType = "MAFIA";
        else if (msg.getType() == MessageType.CHAT_DEAD)  chatType = "DEAD";
        else                                              chatType = "NORMAL";

        if (!inGame) {
            // 로비에서는 내용만 보여줌
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
     * UI에서 사용하는 공용 전송 함수.
     *  - "/" 로 시작하면 그대로 명령 (/ready, /start, /vote 2 ...)
     *  - 나머지는 상태에 따라 CHAT:/CHAT_MAFIA:/CHAT_DEAD: 로 래핑해서 전송
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
        if (chatPrefix.equals("CHAT_DEAD:"))      localType = "DEAD";
        else if (chatPrefix.equals("CHAT_MAFIA:")) localType = "MAFIA";
        else                                      localType = "NORMAL";

        if (!inGame) {
            waitingGamePanel.appendChatMessage(msg);
        } else {
            gamePanel.appendChatMessage(myNickname, msg, true, localType);
        }
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

        // 🔸 gameState 초기화
        syncAllStateToGameState();
        gameState.setCurrentPhase(GamePhase.WAITING);

        SwingUtilities.invokeLater(() -> {
            gamePanel.clearGameState();
            gamePanel.updateMyRoleDisplay(Role.NONE);

            showWaitingPanel();
            waitingGamePanel.clearDisplay();
            waitingGamePanel.updateButtons(wasHost, this.isReady);
        });
    }

    // ==================== GamePanel 에서 사용하는 helper ====================

    // 아래 메서드들은 GamePanel이 아니라 다른 곳에서 쓸 수도 있어 그대로 둠
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

    /**
     * "P3 - 닉네임 ..." 형 문자열에서 3을 추출
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

    // 🔸 Client 내부 상태를 ClientGameState에 한번에 반영하는 유틸
    private void syncAllStateToGameState() {
        gameState.setAlive(alive);
        gameState.setMyRole(myRole);
        gameState.setMyPlayerNumber(myPlayerNumber);
        gameState.setMarkedPlayer(markedPlayer);

        // Map은 공유보다는 복사 쪽이 안전하지만, 여기선 간단히 동기화만
        gameState.getInvestigatedRoles().clear();
        gameState.getInvestigatedRoles().putAll(investigatedRoles);

        // currentPhase 는 GamePanel.updateTimer 에서 setCurrentPhase 해줌
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Client::new);
    }
}
