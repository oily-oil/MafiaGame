import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public class Client extends JFrame {

    private ServerConnectionPanel connectionPanel;
    private WaitingGamePanel waitingGamePanel;
    private GamePanel gamePanel;

    private Socket socket;
    private PrintWriter out;
    private Scanner in;

    private String myRole = "";
    private int myPlayerNumber = 0;
    private boolean isAlive = true;

    private JTextField inputField;  // 메시지 입력 필드

    public Client() {
        setTitle("마피아 클라이언트 (연결 전)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());  // BorderLayout 사용

        // 패널 인스턴스화
        connectionPanel = new ServerConnectionPanel(this);
        waitingGamePanel = new WaitingGamePanel(this);
        gamePanel = new GamePanel(this);

        // 서버 연결 화면을 처음에 보여주기 위해 연결 화면 패널을 BorderLayout 중앙에 배치
        add(connectionPanel, BorderLayout.CENTER);

        // 입력 필드 추가
        inputField = new JTextField();
        inputField.addActionListener(e -> sendMessage(inputField.getText()));  // 메시지 전송 이벤트 리스너 추가
        add(inputField, BorderLayout.SOUTH);  // 화면 하단에 배치

        // 창 크기 설정 및 보기
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // 연결 화면 보이기
    public void showConnectionPanel() {
        removeAllPanels();
        add(connectionPanel, BorderLayout.CENTER);
        revalidate();  // 화면 갱신
        repaint();
    }

    // 대기 화면 보이기
    public void showWaitingPanel() {
        removeAllPanels();
        add(waitingGamePanel, BorderLayout.CENTER);
        revalidate();  // 화면 갱신
        repaint();
    }

    // 게임 시작 화면 보이기
    public void showGamePanel() {
        removeAllPanels();
        add(gamePanel, BorderLayout.CENTER);
        revalidate();  // 화면 갱신
        repaint();
    }

    // 모든 패널을 제거하는 메서드
    private void removeAllPanels() {
        getContentPane().removeAll();
    }

    // 서버 연결 처리
    public void startClientConnection() throws IOException {
        String serverAddress = connectionPanel.getIpAddress();
        int port = connectionPanel.getPort();
        String nickname = connectionPanel.getNickname();

        socket = new Socket(serverAddress, port);
        in = new Scanner(socket.getInputStream());
        out = new PrintWriter(socket.getOutputStream(), true);

        out.println("NICKNAME:" + nickname);

        // 서버 메시지 리스너 스레드 시작
        Thread listenerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (in.hasNextLine()) {
                        String line = in.nextLine();
                        processServerMessage(line);
                    }
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> gamePanel.appendChatMessage("\n!!! 서버 연결이 끊겼습니다: " + e.getMessage() + "\n"));
                } finally {
                    try { socket.close(); } catch (IOException e) {}
                }
            }
        });
        listenerThread.start();
    }

    // 서버 메시지 처리
    private void processServerMessage(String line) {
        SwingUtilities.invokeLater(() -> {
            if (line.startsWith("SYSTEM:")) {
                gamePanel.appendChatMessage(line.substring(7));
            }
            else if (line.startsWith("ROLE:")) {
                myRole = line.substring(5);
                gamePanel.appendChatMessage("당신의 직업: " + myRole);
                showGamePanel();  // 게임 패널로 전환
            }
            else if (line.startsWith("PLAYERS_LIST:")) {
                waitingGamePanel.updateRoomList(line.substring(13));
                waitingGamePanel.enableStartButton();
            }
        });
    }

    // 메시지를 서버로 전송하는 메서드
    public void sendMessage(String message) {
        if (message != null && !message.trim().isEmpty()) {
            // 메시지가 /start와 같은 특정 명령어일 경우 처리
            if (message.startsWith("/")) {
                out.println(message);
            } else {
                // 일반 메시지는 "MSG:" prefix를 붙여서 전송
                out.println("MSG:" + message);
            }
            inputField.setText("");  // 메시지 전송 후 입력 필드 비우기
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new Client();
        });
    }
}
