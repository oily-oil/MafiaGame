import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import javax.swing.SwingUtilities;

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
    private String myRole = "";

    private String myNickname = "";

    private int myPlayerNumber = 0;

    private boolean isHost = false;
    private boolean isReady = false;

    private volatile String markedPlayer = "";

    // [제거] private volatile String mafiaSelectedPlayer = "";

    private Map<String, String> investigatedRoles = new HashMap<>();


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

        try {
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

            out.println("NICKNAME:" + nickname);

            new Thread(this::listenForMessages).start();

            SwingUtilities.invokeLater(this::showWaitingPanel);

        } catch (IOException e) {
            try { if (socket != null) socket.close(); } catch (Exception ignored){}
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
                final String msg = line;
                System.out.println("[SERVER] " + msg);

                SwingUtilities.invokeLater(() -> {

                    // 0. PLAYER_NUM: (접속 시 자신의 번호 수신)
                    if (msg.startsWith("PLAYER_NUM:")) {
                        try {
                            this.myPlayerNumber = Integer.parseInt(msg.substring(11).trim());
                        } catch (NumberFormatException ignored) {}
                        return;
                    }

                    // 1. TIMER: (프로토콜 메시지)
                    if (msg.startsWith("TIMER:")) {
                        String[] parts = msg.substring(6).split(":");
                        if (parts.length == 2) {
                            String phase = parts[0];
                            int secondsLeft = Integer.parseInt(parts[1]);
                            gamePanel.updateTimer(phase, secondsLeft);
                        }
                        return;
                    }

                    // 2. PLAYERS_LIST: (프로토콜 메시지)
                    else if (msg.startsWith("PLAYERS_LIST:")) {
                        String list = msg.substring(13);
                        List<String> players = Arrays.asList(list.split(","));
                        if (!inGame) {
                            waitingGamePanel.updatePlayerList(players);
                        } else {
                            gamePanel.updatePlayerList(players);
                            gamePanel.updatePlayerMarks();
                        }
                        return;
                    }

                    // 3. START_GAME:
                    else if (msg.startsWith("START_GAME")) {
                        inGame = true;
                        markedPlayer = "";
                        investigatedRoles.clear();
                        showGamePanel();
                        gamePanel.appendChatMessage("시스템", "게임이 시작되었습니다.", false);
                        return;
                    }

                    // 4. ROLE: (현재 사용되지 않는 레거시 메시지)
                    else if (msg.startsWith("ROLE:")) {
                        return;
                    }

                    // 5. YOU_DIED:
                    else if (msg.equals("YOU_DIED")) {
                        isAlive = false;
                        gamePanel.appendChatMessage("시스템", "⚠ 당신은 사망했습니다. 관전자 모드로 전환됩니다.", false);
                        return;
                    }

                    // 6. GAME_OVER:
                    else if (msg.startsWith("GAME_OVER")) {
                        String content = msg.substring("GAME_OVER".length()).trim();
                        gamePanel.appendChatMessage("시스템", "[게임 종료] " + content, false);
                        JOptionPane.showMessageDialog(frame, "게임이 종료되었습니다: " + content);

                        resetToLobby();
                        return;
                    }

                    // 7. SYSTEM: (방장/준비, 능력 응답, 입장/퇴장/인원 부족 알림 등)
                    else if (msg.startsWith("SYSTEM:")) {
                        String systemMsg = msg.substring("SYSTEM:".length()).trim();

                        if (systemMsg.equals("HOST_GRANTED")) {
                            isHost = true;
                            isReady = true;
                            waitingGamePanel.updateButtons(true, true);
                        }
                        else if (systemMsg.equals("GUEST_GRANTED")) {
                            isHost = false;
                            isReady = false;
                            waitingGamePanel.updateButtons(false, false);
                        }

                        // 역할 배정 메시지 처리
                        else if (systemMsg.startsWith("[역할] 당신은 '")) {
                            int start = systemMsg.indexOf("'") + 1;
                            int end = systemMsg.lastIndexOf("'");
                            if (start > 0 && end > start) {
                                myRole = systemMsg.substring(start, end).toUpperCase();
                                gamePanel.updateMyRoleDisplay(myRole);
                            }
                        }

                        if (!inGame) {
                            waitingGamePanel.appendChatMessage(systemMsg);
                        } else {
                            gamePanel.appendChatMessage("시스템", systemMsg, false);
                            gamePanel.updatePlayerMarks();
                        }
                        return;
                    }

                    // 8. 채팅 메시지 처리 (서버가 보내는 CHAT_DEAD:, CHAT_MAFIA:, CHAT: 형식)
                    else if (msg.startsWith("CHAT:") || msg.startsWith("CHAT_MAFIA:") || msg.startsWith("CHAT_DEAD:")) {

                        String chatType = "NORMAL";
                        String content;

                        if (msg.startsWith("CHAT_MAFIA:")) {
                            chatType = "MAFIA";
                            content = msg.substring("CHAT_MAFIA:".length()).trim();
                        } else if (msg.startsWith("CHAT_DEAD:")) {
                            chatType = "DEAD";
                            content = msg.substring("CHAT_DEAD:".length()).trim();
                        } else { // CHAT:
                            content = msg.substring("CHAT:".length()).trim();
                        }

                        int colonIndex = content.indexOf(':');

                        if (colonIndex > 0) {
                            String sender = content.substring(0, colonIndex).trim();
                            String message = content.substring(colonIndex + 1).trim();

                            boolean isMyMessage = sender.equals(myNickname);

                            if (!inGame) {
                                waitingGamePanel.appendChatMessage(message);
                            } else {
                                gamePanel.appendChatMessage(sender, message, isMyMessage, chatType);
                            }
                        } else {
                            handleGeneralMessage(msg);
                        }
                        return;
                    }

                    // 밤 능력 대상 마크 (MAFIA, DOCTOR 대상 지목 시)
                    else if (msg.startsWith("MARK_TARGET:")) {
                        markedPlayer = msg.substring("MARK_TARGET:".length()).trim();
                        gamePanel.updatePlayerMarks();
                        return;
                    }

                    // 경찰 조사 결과 마크 (POLICE 클라이언트만 정보 저장)
                    else if (msg.startsWith("MARK_ROLE:")) {
                        if ("POLICE".equals(myRole)) {
                            String data = msg.substring("MARK_ROLE:".length());
                            String[] parts = data.split(":");

                            if (parts.length == 2) {
                                investigatedRoles.put(parts[0], parts[1]);
                                gamePanel.updatePlayerMarks();
                            }
                        }
                        return;
                    }

                    // [제거] MAFIA_SELECT 핸들러 제거


                    // 9. 기타 메시지(Fallback)
                    else {
                        handleGeneralMessage(msg);
                        return;
                    }
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

    private void handleGeneralMessage(String msg) {
        if (!inGame) {
            waitingGamePanel.appendChatMessage(msg);
        } else {
            gamePanel.appendChatMessage("시스템", msg, false);
        }
    }


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

        if (msg.startsWith("/")) {
            out.println(msg);

        } else {
            String chatPrefix;

            if (!isAlive) {
                chatPrefix = "CHAT_DEAD:";
            }
            else if (inGame && gamePanel.getCurrentPhase().equals("NIGHT")) {
                if ("MAFIA".equals(myRole)) {
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


            String localType;
            if (chatPrefix.equals("CHAT_DEAD:")) {
                localType = "DEAD";
            } else if (chatPrefix.equals("CHAT_MAFIA:")) {
                localType = "MAFIA";
            } else {
                localType = "NORMAL";
            }

            if (!inGame) {
                waitingGamePanel.appendChatMessage(msg);
            } else {
                gamePanel.appendChatMessage(myNickname, msg, true, localType);
            }
        }
    }

    // [제거] public void sendMafiaSelectionState(String playerID) {} 메서드 제거

    // ---------------- GUI 전환 유틸 ----------------
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
        myRole = "";
        markedPlayer = "";
        // [수정] mafiaSelectedPlayer 필드 제거에 따른 초기화 로직 제거
        investigatedRoles.clear();
        this.myPlayerNumber = 0;

        this.isReady = wasHost;

        SwingUtilities.invokeLater(() -> {
            gamePanel.clearGameState();
            gamePanel.updateMyRoleDisplay("UNKNOWN");

            showWaitingPanel();
            waitingGamePanel.clearDisplay();
            waitingGamePanel.updateButtons(wasHost, this.isReady);
        });
    }

    public boolean hasAbility() {
        return "MAFIA".equals(myRole) || "POLICE".equals(myRole) || "DOCTOR".equals(myRole);
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

    // [제거] public String getMafiaSelectedPlayer() {} 메서드 제거

    public Map<String, String> getInvestigatedRoles() {
        return investigatedRoles;
    }

    public String getMyRole() {
        return myRole;
    }

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