// Client.java
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;
import javax.swing.*;

public class Client {

    private JFrame frame = new JFrame("채팅 클라이언트");
    private JTextArea messageArea = new JTextArea(20, 50);
    private JTextField textField = new JTextField(40);
    private JButton sendButton = new JButton("전송");

    private Socket socket;
    private PrintWriter out;
    private Scanner in;

    private String myRole = "";
    private int myPlayerNumber = 0;
    private boolean isAlive = true;

    public Client() {
        messageArea.setEditable(false);
        frame.getContentPane().add(new JScrollPane(messageArea), BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel();
        bottomPanel.add(textField);
        bottomPanel.add(sendButton);
        frame.getContentPane().add(bottomPanel, BorderLayout.SOUTH);

        frame.pack();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);

        ActionListener sendListener = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                String message = textField.getText();

                // [수정] /start는 항상 가능
                if (message.trim().equalsIgnoreCase("/start")) {
                    out.println(message.trim());
                }
                // [수정] 살아있을 때만 명령어 전송 가능
                else if (isAlive && (
                        message.trim().startsWith("/kill ") ||
                                message.trim().startsWith("/vote ") ||
                                message.trim().startsWith("/investigate ") || // [신규]
                                message.trim().startsWith("/save ")         // [신규]
                )) {
                    out.println(message.trim());
                }
                // [수정] 살아있을 때만 채팅 전송 가능
                else if (isAlive && !message.isEmpty()) {
                    out.println("MSG:" + message);
                }

                textField.setText("");
            }
        };
        textField.addActionListener(sendListener);
        sendButton.addActionListener(sendListener);
    }

    public void start() throws IOException {
        String serverAddress = JOptionPane.showInputDialog(
                frame, "서버 IP 주소를 입력하세요:", "서버 접속", JOptionPane.QUESTION_MESSAGE
        );

        socket = new Socket(serverAddress, 9090);
        in = new Scanner(socket.getInputStream());
        out = new PrintWriter(socket.getOutputStream(), true);

        // 서버로부터 메시지를 받는 스레드
        Thread listenerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (in.hasNextLine()) {
                        String line = in.nextLine();

                        // 게임 종료 핸들러
                        if (line.equals("GAME_OVER")) {
                            myRole = "";
                            isAlive = true; // 다음 게임을 위해 리셋
                            SwingUtilities.invokeLater(() -> {
                                messageArea.append("\n--- 게임이 종료되었습니다. --- \n/start를 입력하여 새 게임을 시작할 수 있습니다.\n");
                                textField.setEditable(true); // /start를 칠 수 있게 활성화
                                sendButton.setEnabled(true);
                                updateTitle(); // 제목 리셋
                            });
                        }
                        // 플레이어 번호 수신
                        else if (line.startsWith("PLAYER_NUM:")) {
                            myPlayerNumber = Integer.parseInt(line.substring(11));
                            SwingUtilities.invokeLater(() -> {
                                updateTitle(); // 제목 업데이트
                            });
                        }
                        // 사망 메시지 수신
                        else if (line.equals("YOU_DIED")) {
                            isAlive = false;
                            SwingUtilities.invokeLater(() -> {
                                messageArea.append("\n!!! 당신은 죽었습니다 !!!\n");
                                textField.setEditable(false);
                                sendButton.setEnabled(false);
                                updateTitle(); // 제목 업데이트
                            });
                        }
                        // 게임 시작 공지
                        else if (line.equals("START_GAME")) {
                            SwingUtilities.invokeLater(() -> {
                                messageArea.append("--- 게임이 시작되었습니다! ---\n");
                            });
                        }
                        // [수정] 직업 할당 메시지
                        else if (line.startsWith("ROLE:")) {
                            String roleName = line.substring(5); // MAFIA, CITIZEN, POLICE, DOCTOR

                            // 영어 직업명을 한글로 변환
                            if (roleName.equals("MAFIA")) myRole = "마피아";
                            else if (roleName.equals("CITIZEN")) myRole = "시민";
                            else if (roleName.equals("POLICE")) myRole = "경찰";
                            else if (roleName.equals("DOCTOR")) myRole = "의사";
                            else myRole = "알 수 없음";

                            SwingUtilities.invokeLater(() -> {
                                messageArea.append("\n!!! 당신의 직업은 '" + myRole + "'입니다 !!!\n\n");
                                // 직업별 능력 힌트 추가
                                if (myRole.equals("경찰")) {
                                    messageArea.append("-> 밤에 /investigate [번호] 를 사용해 용의자를 조사할 수 있습니다.\n");
                                } else if (myRole.equals("의사")) {
                                    messageArea.append("-> 밤에 /save [번호] 를 사용해 1명을 살릴 수 있습니다.\n");
                                } else if (myRole.equals("마피아")) {
                                    messageArea.append("-> 밤에 /kill [번호] 를 사용해 1명을 처형할 수 있습니다.\n");
                                }
                                updateTitle();
                            });
                        }
                        // [수정] 서버 시스템 메시지 (낮/밤, 투표 결과 등)
                        else if (line.startsWith("SYSTEM:")) {
                            String systemMessage = line.substring(7);
                            SwingUtilities.invokeLater(() -> {
                                messageArea.append(systemMessage + "\n");

                                // GUI 활성화/비활성화 로직 (살아있을 때만)
                                if (isAlive) {
                                    if (systemMessage.contains("밤이 되었습니다")) {
                                        // [수정] 시민만 비활성화 (경찰, 의사, 마피아는 활성화)
                                        if ("시민".equals(myRole)) {
                                            textField.setEditable(false);
                                            sendButton.setEnabled(false);
                                        } else {
                                            // 경찰, 의사, 마피아는 능력 사용을 위해 활성화
                                            textField.setEditable(true);
                                            sendButton.setEnabled(true);
                                        }
                                    } else if (systemMessage.contains("낮이 되었습니다")) {
                                        // 낮은 모두 활성화
                                        textField.setEditable(true);
                                        sendButton.setEnabled(true);
                                    }
                                }
                            });
                        }
                        // 일반 채팅 메시지
                        else if (line.startsWith("P") && line.contains(": ") || line.startsWith("[마피아채팅]")) {
                            SwingUtilities.invokeLater(() -> {
                                messageArea.append(line + "\n");
                            });
                        }
                    }
                } catch (Exception e) {
                    System.out.println("서버 연결이 끊겼습니다: " + e.getMessage());
                } finally {
                    try { socket.close(); } catch (IOException e) {}
                }
            }
        });
        listenerThread.start();
    }

    // GUI 제목을 업데이트하는 헬퍼 메소드
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
        frame.setTitle(title);
    }

    public static void main(String[] args) throws IOException {
        Client client = new Client();
        client.start();
    }
}