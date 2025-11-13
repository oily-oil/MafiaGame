import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public class Client extends JFrame {

    // --- GUI 컴포넌트 선언 ---
    private JTextField nicknameField;
    private JTextField ipAddressField;
    private JTextField portNumberField;
    private JButton connectButton;
    private JTextArea roomListArea; // 게임 상태 및 참여자 목록 표시용
    private JButton startGameButton; // "/start" 명령 전송 버튼
    private JTextArea chatArea;
    private JTextField inputField;

    // --- 네트워크 및 게임 상태 변수 ---
    private Socket socket;
    private PrintWriter out;
    private Scanner in;

    private String myRole = "";
    private int myPlayerNumber = 0;
    private boolean isAlive = true;

    public Client() {
        setTitle("마피아 클라이언트 (연결 전)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // GUI 레이아웃 설정
        JPanel leftPanel = createLeftPanel();
        add(leftPanel, BorderLayout.WEST);

        JPanel centerPanel = createCenterPanel();
        add(centerPanel, BorderLayout.CENTER);

        JPanel rightPanel = createRightPanel();
        add(rightPanel, BorderLayout.EAST);

        // 초기 상태 설정
        startGameButton.setEnabled(false);
        inputField.setEnabled(false);

        pack();
        setLocationRelativeTo(null);
        setVisible(true);

        setupListeners();
    }

    // --- 1. 왼쪽 영역 생성 ---
    private JPanel createLeftPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); // 세로 정렬
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.setPreferredSize(new Dimension(180, 500));

        panel.add(new JLabel("닉네임:"));
        nicknameField = new JTextField("P" + (int)(Math.random() * 1000), 10);
        panel.add(nicknameField);
        panel.add(Box.createVerticalStrut(5));

        panel.add(new JLabel("IP 주소:"));
        ipAddressField = new JTextField("127.0.0.1", 10);
        panel.add(ipAddressField);
        panel.add(Box.createVerticalStrut(5));

        panel.add(new JLabel("포트 넘버:"));
        portNumberField = new JTextField("9090", 10);
        panel.add(portNumberField);
        panel.add(Box.createVerticalStrut(15));

        connectButton = new JButton("서버 연결");
        connectButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(connectButton);
        panel.add(Box.createVerticalStrut(10));

        JLabel descLabel = new JLabel("<html>서버 연결 후<br>게임 참여 가능</html>", SwingConstants.CENTER);
        descLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(descLabel);

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    // --- 2. 중앙 영역 생성 ---
    private JPanel createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.setPreferredSize(new Dimension(220, 500));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("참가자 목록:"));
        panel.add(topPanel, BorderLayout.NORTH);

        roomListArea = new JTextArea("서버에 연결하세요...");
        roomListArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(roomListArea);
        panel.add(scrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        startGameButton = new JButton("게임 시작 (/start)");
        bottomPanel.add(startGameButton);
        panel.add(bottomPanel, BorderLayout.SOUTH);

        return panel;
    }

    // --- 3. 오른쪽 영역 생성 ---
    private JPanel createRightPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.setPreferredSize(new Dimension(350, 500));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("게임 대화창"));
        panel.add(topPanel, BorderLayout.NORTH);

        chatArea = new JTextArea("게임 시작을 기다립니다...");
        chatArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(chatArea);
        panel.add(scrollPane, BorderLayout.CENTER);

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputField = new JTextField();
        inputPanel.add(new JLabel("(입력)"), BorderLayout.WEST);
        inputPanel.add(inputField, BorderLayout.CENTER);

        panel.add(inputPanel, BorderLayout.SOUTH);

        return panel;
    }

    // --- 리스너 설정 ---
    private void setupListeners() {
        connectButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    startClientConnection();
                    // 성공 시 연결 관련 컴포넌트 비활성화
                    connectButton.setEnabled(false);
                    ipAddressField.setEditable(false);
                    portNumberField.setEditable(false);
                    nicknameField.setEditable(false);
                    startGameButton.setEnabled(true);
                    inputField.setEnabled(true);
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(Client.this, "연결 실패: " + ex.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        ActionListener sendListener = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                String message;
                if (e.getSource() == startGameButton) {
                    message = "/start";
                } else {
                    message = inputField.getText();
                }

                if (out == null || message.isEmpty()) return;

                if (message.trim().equalsIgnoreCase("/start")) {
                    out.println(message.trim());
                }
                else if (isAlive && (
                        message.trim().startsWith("/kill ") ||
                                message.trim().startsWith("/vote ") ||
                                message.trim().startsWith("/investigate ") ||
                                message.trim().startsWith("/save ")
                )) {
                    out.println(message.trim());
                }
                else if (isAlive && !message.isEmpty()) {
                    out.println("MSG:" + message);
                }

                inputField.setText("");
            }
        };
        inputField.addActionListener(sendListener);
        startGameButton.addActionListener(sendListener);
    }

    // --- 서버 연결 및 리스너 스레드 시작 ---
    public void startClientConnection() throws IOException {
        String serverAddress = ipAddressField.getText();
        int port = Integer.parseInt(portNumberField.getText());
        String nickname = nicknameField.getText();

        socket = new Socket(serverAddress, port);
        in = new Scanner(socket.getInputStream());
        out = new PrintWriter(socket.getOutputStream(), true);

        // 닉네임 서버로 전송
        out.println("NICKNAME:" + nickname);

        Thread listenerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (in.hasNextLine()) {
                        String line = in.nextLine();
                        processServerMessage(line);
                    }
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> chatArea.append("\n!!! 서버 연결이 끊겼습니다: " + e.getMessage() + "\n"));
                } finally {
                    try { socket.close(); } catch (IOException e) {}
                }
            }
        });
        listenerThread.start();
    }

    // --- 서버로부터 받은 메시지 처리 ---
    private void processServerMessage(String line) {
        SwingUtilities.invokeLater(() -> {
            if (line.equals("GAME_OVER")) {
                myRole = "";
                isAlive = true;
                chatArea.append("\n--- 게임이 종료되었습니다. --- \n/start를 입력하여 새 게임을 시작할 수 있습니다.\n");
                inputField.setEnabled(true);
                startGameButton.setEnabled(true);
                updateTitle();
            }
            else if (line.startsWith("PLAYER_NUM:")) {
                myPlayerNumber = Integer.parseInt(line.substring(11));
                updateTitle();
            }
            else if (line.equals("YOU_DIED")) {
                isAlive = false;
                chatArea.append("\n!!! 당신은 죽었습니다 !!!\n");
                inputField.setEnabled(false);
                updateTitle();
            }
            else if (line.equals("START_GAME")) {
                chatArea.append("--- 게임이 시작되었습니다! ---\n");
                startGameButton.setEnabled(false);
            }
            else if (line.startsWith("ROLE:")) {
                String roleName = line.substring(5);
                if (roleName.equals("MAFIA")) myRole = "마피아";
                else if (roleName.equals("CITIZEN")) myRole = "시민";
                else if (roleName.equals("POLICE")) myRole = "경찰";
                else if (roleName.equals("DOCTOR")) myRole = "의사";
                else myRole = "알 수 없음";

                chatArea.append("\n!!! 당신의 직업은 '" + myRole + "'입니다 !!!\n\n");
                if (myRole.equals("경찰")) chatArea.append("-> 밤에 /investigate [번호] 로 조사.\n");
                else if (myRole.equals("의사")) chatArea.append("-> 밤에 /save [번호] 로 살리기.\n");
                else if (myRole.equals("마피아")) chatArea.append("-> 밤에 /kill [번호] 로 처형.\n");

                updateTitle();
            }
            // 시스템 메시지 (낮/밤 제어 포함)
            else if (line.startsWith("SYSTEM:")) {
                String systemMessage = line.substring(7);
                chatArea.append(systemMessage + "\n");

                if (isAlive) {
                    if (systemMessage.contains("밤이 되었습니다")) {
                        boolean enableInput = !("시민".equals(myRole));
                        inputField.setEnabled(enableInput);
                    } else if (systemMessage.contains("낮이 되었습니다")) {
                        inputField.setEnabled(true);
                    }
                }
            }
            // 참가자 목록 업데이트 메시지
            else if (line.startsWith("PLAYERS_LIST:")) {
                roomListArea.setText("--- 참가자 (" + line.substring(13).split(",").length + "명) ---\n" + line.substring(13).replace(",", "\n"));
            }
            // 일반 채팅 메시지
            else if (line.startsWith("P") && line.contains(": ") || line.startsWith("[마피아채팅]")) {
                chatArea.append(line + "\n");
            }
        });
    }

    private void updateTitle() {
        String title = "마피아 게임";
        if (myPlayerNumber > 0) {
            title += " - P" + myPlayerNumber;
        }
        if (!myRole.isEmpty()) {
            title += " (" + myRole + ")";
        }
        if (!isAlive) {
            title += " [사망]";
        }
        setTitle(title);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new Client();
        });
    }
}