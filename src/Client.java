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
                        // [수정] appendChatMessage 인자 수정
                        gamePanel.appendChatMessage("시스템", "게임이 시작되었습니다.", false);
                        return;
                    }

                    // 4. ROLE:
                    else if (msg.startsWith("ROLE:")) {
                        myRole = msg.substring(5);
                        // [수정] appendChatMessage 인자 수정
                        gamePanel.appendChatMessage("시스템", "[역할] 당신은 '" + myRole + "' 입니다.", false);
                        return;
                    }

                    // 5. YOU_DIED:
                    else if (msg.equals("YOU_DIED")) {
                        isAlive = false;
                        // [수정] appendChatMessage 인자 수정
                        gamePanel.appendChatMessage("시스템", "⚠ 당신은 사망했습니다. 관전자 모드로 전환됩니다.", false);
                        return;
                    }

                    // 6. GAME_OVER:
                    else if (msg.startsWith("GAME_OVER")) {
                        String content = msg.substring("GAME_OVER".length()).trim();
                        // [수정] appendChatMessage 인자 수정
                        gamePanel.appendChatMessage("시스템", "[게임 종료] " + content, false);
                        JOptionPane.showMessageDialog(frame, "게임이 종료되었습니다: " + content);

                        // [수정] resetToLobby()를 호출하여 로비로 돌아가는 모든 로직을 처리합니다.
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
                            // [수정] appendChatMessage 인자 수정
                            gamePanel.appendChatMessage("시스템", systemMsg, false);
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

                            boolean isMyMessage = sender.equals(myNickname); // [수정] 내가 보낸 메시지인지 확인

                            if (!inGame) {
                                waitingGamePanel.appendChatMessage(message);
                            } else {
                                // [수정] appendChatMessage 인자 수정
                                gamePanel.appendChatMessage(sender, message, isMyMessage);
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

                // [수정] 연결 끊김 시에도 방장 상태를 유지한 채 로비로 돌아가도록 resetToLobby() 호출
                resetToLobby();
            });
        }
    }

    private void handleGeneralMessage(String msg) {
        if (!inGame) {
            waitingGamePanel.appendChatMessage(msg);
        } else {
            // [수정] appendChatMessage 인자 수정
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
            // [수정] 1. 사망자 처리 (사망자 채팅은 언제나 가능하며, role 검사 없이 통과)
            if (!isAlive) {
                out.println("CHAT:" + myNickname + ":" + msg);

                if (!inGame) {
                    waitingGamePanel.appendChatMessage(msg);
                } else {
                    // [수정] appendChatMessage 인자 수정
                    gamePanel.appendChatMessage(myNickname, msg, true);
                }
                return;
            }

            // [수정] 2. 생존자 밤 채팅 통제
            if (inGame && gamePanel.getCurrentPhase().equals("NIGHT")) {
                if (!myRole.equals("MAFIA")) {
                    JOptionPane.showMessageDialog(frame, "밤에는 마피아만 대화 가능합니다.", "경고", JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }

            // [수정] 3. 생존자 채팅 전송 및 즉시 화면 표시
            out.println("CHAT:" + myNickname + ":" + msg);

            if (!inGame) {
                waitingGamePanel.appendChatMessage(msg);
            } else {
                // [수정] appendChatMessage 인자 수정
                gamePanel.appendChatMessage(myNickname, msg, true);
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
        // ⭐️ [수정] 현재 isHost 상태를 저장하고, isReady 상태만 변경합니다.
        boolean wasHost = this.isHost;

        inGame = false;
        isAlive = true;
        myRole = "";

        // isHost는 그대로 두고, isReady만 방장이었으면 true로, 아니면 false로 초기화
        this.isReady = wasHost;

        // Note: this.isHost = false; 로직은 제거되어 방장 권한이 유지됩니다.

        SwingUtilities.invokeLater(() -> {
            gamePanel.clearGameState();

            showWaitingPanel();

            // [수정] 게임 종료 후 패널 내용 초기화 (WaitingGamePanel에 clearDisplay()가 있다고 가정)
            // waitingGamePanel.clearPlayerList(); // <- 이 대신
            waitingGamePanel.clearDisplay();

            // ⭐️ [수정] 저장된 wasHost 상태를 기반으로 버튼을 업데이트합니다.
            // 방장이었으면 '게임 시작' 버튼이 보이게 됩니다.
            waitingGamePanel.updateButtons(wasHost, this.isReady);
        });
    }

    public boolean hasAbility() {
        return "MAFIA".equals(myRole) || "POLICE".equals(myRole) || "DOCTOR".equals(myRole);
    }

    public boolean isAlive() {
        return isAlive;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Client::new);
    }
}