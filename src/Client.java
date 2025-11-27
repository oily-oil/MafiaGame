import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
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

    private boolean isHost = false;
    private boolean isReady = false;

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
                        }
                        return;
                    }

                    // 3. START_GAME:
                    else if (msg.startsWith("START_GAME")) {
                        inGame = true;
                        showGamePanel();
                        gamePanel.appendChatMessage("시스템", "게임이 시작되었습니다.", false, false);
                        return;
                    }

                    // 4. ROLE:
                    else if (msg.startsWith("ROLE:")) {
                        myRole = msg.substring(5);
                        gamePanel.appendChatMessage("시스템", "[역할] 당신은 '" + myRole + "' 입니다.", false, false);
                        return;
                    }

                    // 5. YOU_DIED:
                    else if (msg.equals("YOU_DIED")) {
                        isAlive = false;
                        gamePanel.appendChatMessage("시스템", "⚠ 당신은 사망했습니다. 관전자 모드로 전환됩니다.", false, false);
                        return;
                    }

                    // 6. GAME_OVER:
                    else if (msg.startsWith("GAME_OVER")) {
                        String content = msg.substring("GAME_OVER".length()).trim();
                        gamePanel.appendChatMessage("시스템", "[게임 종료] " + content, false, false);
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

                        if (!inGame) {
                            waitingGamePanel.appendChatMessage(systemMsg);
                        } else {
                            gamePanel.appendChatMessage("시스템", systemMsg, false, false);
                        }
                        return;
                    }

                    // 8. 채팅 메시지 처리 (서버가 보내는 CHAT:닉네임:내용 형식)
                    else if (msg.startsWith("CHAT:")) {
                        String chatContent = msg.substring("CHAT:".length()).trim();
                        int colonIndex = chatContent.indexOf(':');

                        if (colonIndex > 0) {
                            String sender = chatContent.substring(0, colonIndex).trim();
                            String message = chatContent.substring(colonIndex + 1).trim();

                            boolean isMyMessage = false;

                            // [수정] 마피아 채팅인지 판단하는 로직 (수신자가 마피아 역할이고 밤 단계일 때)
                            boolean isMafiaChat = inGame && gamePanel.getCurrentPhase().equals("NIGHT") && myRole.equals("MAFIA");

                            if (!inGame) {
                                waitingGamePanel.appendChatMessage(message);
                            } else {
                                gamePanel.appendChatMessage(sender, message, isMyMessage, isMafiaChat);
                            }
                        } else {
                            handleGeneralMessage(msg);
                        }
                        return;
                    }

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
            gamePanel.appendChatMessage("시스템", msg, false, false);
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
            // [수정] 1. 사망자 처리 (사망자 채팅은 언제나 가능하며, role 검사 없이 통과)
            if (!isAlive) {
                boolean isMafiaChat = false;

                out.println("CHAT:" + myNickname + ":" + msg);

                if (!inGame) {
                    waitingGamePanel.appendChatMessage(msg);
                } else {
                    gamePanel.appendChatMessage(myNickname, msg, true, isMafiaChat);
                }
                return;
            }

            // [수정] 2. 생존자 밤 채팅 통제
            if (inGame && gamePanel.getCurrentPhase().equals("NIGHT")) {
                // 생존자이면서 마피아가 아닌 경우 차단
                if (!myRole.equals("MAFIA")) {
                    JOptionPane.showMessageDialog(frame, "밤에는 마피아만 대화 가능합니다.", "경고", JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }

            // [수정] 3. 생존자 채팅 전송 및 즉시 화면 표시
            out.println("CHAT:" + myNickname + ":" + msg);

            boolean isMafiaChat = inGame && gamePanel.getCurrentPhase().equals("NIGHT") && myRole.equals("MAFIA");

            if (!inGame) {
                waitingGamePanel.appendChatMessage(msg);
            } else {
                gamePanel.appendChatMessage(myNickname, msg, true, isMafiaChat);
            }
        }
    }

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
        inGame = false;
        isAlive = true;
        myRole = "";

        this.isHost = false;
        this.isReady = false;

        SwingUtilities.invokeLater(() -> {
            gamePanel.clearGameState();

            showWaitingPanel();
            waitingGamePanel.clearPlayerList();
            waitingGamePanel.updateButtons(false, false);
        });
    }

    public boolean hasAbility() {
        return "MAFIA".equals(myRole) || "POLICE".equals(myRole) || "DOCTOR".equals(myRole);
    }

    public boolean isAlive() {
        return isAlive;
    }

    public String getRoleCommand() {
        return "/skill ";
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Client::new);
    }
}