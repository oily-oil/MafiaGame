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

    // 🌟 [유지] 방장/준비 상태 변수
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
                            // 🌟 [수정 핵심]: 목록을 받았을 때, 방장이 아니라면 isReady 상태를 갱신합니다.
                            // 목록에는 자신의 준비 상태가 포함되어 있으므로, 이 시점에 UI를 한 번 더 갱신합니다.
                            // 하지만 isReady 상태는 SYSTEM: 메시지에서 갱신하는 것이 더 명확합니다.
                            // **여기서는 목록 업데이트만 하고, 버튼 상태는 명확한 SYSTEM 메시지에만 의존합니다.**
                        } else {
                            gamePanel.updatePlayerList(players);
                        }
                        return;
                    }

                    // 3. START_GAME:
                    else if (msg.startsWith("START_GAME")) {
                        inGame = true;
                        showGamePanel();
                        gamePanel.appendChatMessage("게임이 시작되었습니다.");
                        return;
                    }

                    // 4. ROLE:
                    else if (msg.startsWith("ROLE:")) {
                        myRole = msg.substring(5);
                        gamePanel.appendChatMessage("[역할] 당신은 '" + myRole + "' 입니다.");
                        return;
                    }

                    // 5. YOU_DIED:
                    else if (msg.equals("YOU_DIED")) {
                        isAlive = false;
                        gamePanel.appendChatMessage("⚠ 당신은 사망했습니다. 관전자 모드로 전환됩니다.");
                        return;
                    }

                    // 6. GAME_OVER:
                    else if (msg.startsWith("GAME_OVER")) {
                        gamePanel.appendChatMessage("[게임 종료] " + msg.substring("GAME_OVER".length()).trim());
                        JOptionPane.showMessageDialog(frame, "게임이 종료되었습니다: " + msg.substring("GAME_OVER".length()).trim());
                        resetToLobby();
                        return;
                    }

                    // 7. SYSTEM: (방장/준비, 능력 응답, 입장/퇴장/인원 부족 알림 등)
                    else if (msg.startsWith("SYSTEM:")) {
                        String systemMsg = msg.substring("SYSTEM:".length()).trim();

                        // 🌟 [수정 핵심]: **오직 명시적인 권한 부여 메시지**에 대해서만 isHost/isReady 상태를 변경하고 updateButtons()를 호출합니다.
                        // 준비/취소 완료 메시지(SYSTEM:준비 완료되었습니다.)는 채팅으로만 출력하고, 버튼 상태 변경 로직에서 제외하여 오류를 방지합니다.

                        if (systemMsg.equals("HOST_GRANTED")) {
                            isHost = true;
                            isReady = true; // 방장은 항상 준비 상태
                            waitingGamePanel.updateButtons(true, true);
                        }
                        else if (systemMsg.equals("GUEST_GRANTED")) {
                            isHost = false;
                            isReady = false; // 일반 참여자는 초기 미준비 상태
                            waitingGamePanel.updateButtons(false, false);
                        }
                        // 🚨 [제거]: "준비 완료되었습니다." 메시지를 통한 isReady/updateButtons() 호출 로직을 제거
                        // isReady 상태 변경은 이제 /ready 명령을 보낼 때만 클라이언트 측에서 미리 반영합니다.

                        // [수정] 시스템 메시지를 게임 상태에 따라 라우팅
                        if (!inGame) {
                            waitingGamePanel.appendChatMessage("[시스템] " + systemMsg);
                        } else {
                            gamePanel.appendChatMessage("[시스템] " + systemMsg);
                        }
                        return;
                    }

                    // 8. 기타 메시지(채팅으로 간주)
                    else {
                        if (!inGame) {
                            waitingGamePanel.appendChatMessage(msg);
                        } else {
                            gamePanel.appendChatMessage(msg);
                        }
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

    // 🌟 [수정]: /ready 명령 전송 시 클라이언트의 isReady 상태를 먼저 업데이트합니다.
    public void handleReadyClick() {
        if (!isHost) {
            sendMessage("/ready");
            // 🌟 [핵심 수정]: 서버 응답을 기다리지 않고, 클라이언트에서 먼저 상태를 토글하고 UI를 업데이트합니다.
            // 서버는 이 상태를 확인하는 용도로만 사용됩니다.
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
            out.println("MSG:" + msg);
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

    public String getRoleCommand() {
        return "/skill ";
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Client::new);
    }
}