import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;

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

    public Client() {
        frame = new JFrame("마피아 게임 클라이언트");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 700);

        // 패널 생성 (this를 전달)
        // [수정] 아래 클래스는 외부에 정의되어 있어야 합니다.
        connectionPanel = new ServerConnectionPanel(this);
        waitingGamePanel = new WaitingGamePanel(this);
        gamePanel = new GamePanel(this);

        frame.getContentPane().add(connectionPanel);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /**
     * 서버에 접속하고 리스너 스레드를 시작한다.
     * nickname: 플레이어 닉네임
     * host, port: 서버 정보
     */
    public void connectToServer(String nickname, String host, int port) throws IOException {
        this.host = host;
        this.port = port;

        try {
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

            // 서버에 닉네임 전송 (서버에서 첫 라인을 닉네임으로 받도록 설계)
            out.println(nickname);

            // 수신 쓰레드 시작
            new Thread(this::listenForMessages).start();

            // UI 전환: 대기실
            SwingUtilities.invokeLater(this::showWaitingPanel);

        } catch (IOException e) {
            // 소켓/스트림 열린 경우 닫기
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
                        // TIMER:PHASE:SECONDS_LEFT 형식
                        String[] parts = msg.substring(6).split(":");
                        if (parts.length == 2) {
                            String phase = parts[0];
                            int secondsLeft = Integer.parseInt(parts[1]);
                            // GamePanel의 updateTimer 메서드 호출
                            gamePanel.updateTimer(phase, secondsLeft);
                        }
                        return; // [수정] 메시지 처리 후 종료 (로그 출력 방지)
                    }

                    // 2. PLAYERS_LIST: (프로토콜 메시지)
                    else if (msg.startsWith("PLAYERS_LIST:")) {
                        String list = msg.substring(13);
                        List<String> players = Arrays.asList(list.split(","));
                        if (!inGame) {
                            waitingGamePanel.updatePlayerList(players);
                            waitingGamePanel.enableStartButton();
                        } else {
                            gamePanel.updatePlayerList(players);
                        }
                        return; // [수정] 메시지 처리 후 종료 (로그 출력 방지)
                    }

                    // 3. START_GAME:
                    else if (msg.startsWith("START_GAME")) {
                        inGame = true;
                        showGamePanel();
                        gamePanel.appendChatMessage("게임이 시작되었습니다.");
                        waitingGamePanel.disableStartButton();
                        return; // [수정] 메시지 처리 후 종료
                    }

                    // 4. ROLE:
                    else if (msg.startsWith("ROLE:")) {
                        myRole = msg.substring(5);
                        gamePanel.appendChatMessage("[역할] 당신은 '" + myRole + "' 입니다.");
                        return; // [수정] 메시지 처리 후 종료
                    }

                    // 5. CHAT: (일반 채팅 메시지)
                    else if (msg.startsWith("CHAT:")) {
                        // [수정] 채팅 메시지를 게임 상태에 따라 라우팅
                        String chatMsg = msg.substring(5);
                        if (!inGame) {
                            waitingGamePanel.appendChatMessage(chatMsg);
                        } else {
                            gamePanel.appendChatMessage(chatMsg);
                        }
                        return; // [수정] 메시지 처리 후 종료
                    }

                    // 6. YOU_DIED:
                    else if (msg.equals("YOU_DIED")) {
                        isAlive = false;
                        gamePanel.appendChatMessage("⚠ 당신은 사망했습니다. 관전자 모드로 전환됩니다.");
                        return; // [수정] 메시지 처리 후 종료
                    }

                    // 7. GAME_OVER:
                    else if (msg.startsWith("GAME_OVER")) {
                        gamePanel.appendChatMessage("[게임 종료] " + msg.substring("GAME_OVER".length()).trim());
                        JOptionPane.showMessageDialog(frame, "게임이 종료되었습니다: " + msg.substring("GAME_OVER".length()).trim());
                        resetToLobby();
                        return; // [수정] 메시지 처리 후 종료
                    }

                    // 8. SYSTEM: (능력 응답, 입장/퇴장/인원 부족 알림)
                    else if (msg.startsWith("SYSTEM:")) {
                        String systemMsg = msg.substring("SYSTEM:".length()).trim();
                        // [수정] 시스템 메시지를 게임 상태에 따라 라우팅
                        if (!inGame) {
                            waitingGamePanel.appendChatMessage("[시스템] " + systemMsg);
                        } else {
                            gamePanel.appendChatMessage("[시스템] " + systemMsg);
                        }
                        return; // [수정] 메시지 처리 후 종료
                    }

                    // 9. TIME:
                    else if (msg.startsWith("TIME:")) {
                        gamePanel.appendChatMessage("[시간] " + msg.substring(5));
                        return; // [수정] 메시지 처리 후 종료
                    }

                    // 10. DETECTIVE_RESULT:
                    else if (msg.startsWith("DETECTIVE_RESULT")) {
                        gamePanel.appendChatMessage("[조사 결과] " + msg.substring("DETECTIVE_RESULT".length()).trim());
                        return; // [수정] 메시지 처리 후 종료
                    }

                    // 11. VOTE_UPDATE:
                    else if (msg.startsWith("VOTE_UPDATE")) {
                        gamePanel.appendChatMessage("[투표] " + msg.substring("VOTE_UPDATE".length()).trim());
                        return; // [수정] 메시지 처리 후 종료
                    }

                    // 12. 기타 메시지(포맷에 없으면 그대로 출력)
                    else {
                        gamePanel.appendChatMessage(msg);
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

    /**
     * 서버로 메시지 전송 (명령 또는 일반 메시지)
     */
    public void sendMessage(String msg) {
        if (out == null) return;
        if (msg == null) return;
        msg = msg.trim();
        if (msg.isEmpty()) return;

        // 일반 텍스트는 MSG: prefix로 전송하는 서버 구현을 사용한다면 사용
        // 하지만 본 서버 예시에서는 그냥 명령( /vote /skill /start ) 또는 CHAT 텍스트를 직접 처리하므로
        // 클라이언트는 명확한 프로토콜을 사용한다. 여기선 서버가 비슬래시 아닌 텍스트도 CHAT으로 처리하도록 해놨으므로:
        if (msg.startsWith("/")) {
            out.println(msg);
        } else {
            // 일반 채팅 메시지
            // [수정] 서버가 MSG: prefix를 기대하지 않으므로 그대로 전송 (이 메시지는 Server.java의 else if(message.startsWith("MSG:")) 블록으로 들어갑니다.)
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

    // 게임이 끝나면 상태 리셋하고 로비로 돌아간다
    private void resetToLobby() {
        inGame = false;
        isAlive = true;
        myRole = "";

        SwingUtilities.invokeLater(() -> {
            gamePanel.clearGameState();

            showWaitingPanel();
            waitingGamePanel.clearPlayerList();
            waitingGamePanel.enableStartButton(); // 버튼 항시 활성화 유지
        });
    }

    // 간단한 main 실행기 (테스트용)
    public static void main(String[] args) {
        SwingUtilities.invokeLater(Client::new);
    }
}