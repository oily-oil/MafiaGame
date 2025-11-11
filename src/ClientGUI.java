//JavaChatClientView.java
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

public class ClientGUI extends JFrame {
    private JPanel contentPane;
    private JTextField txtInput;
    private String UserName;
    private JButton btnSend;
    private JTextArea textArea;
    private static final int BUF_LEN = 128; // Windows 처럼 BUF_LEN 을 정의
    private Socket socket; // 연결소켓
    private InputStream is;
    private OutputStream os;
    private DataInputStream dis;
    private DataOutputStream dos;
    private JLabel lblUserName;

    /**
     * Create the frame.
     */
    public ClientGUI(String username, String ip_addr, String port_no) {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setBounds(100, 100, 392, 462);
        contentPane = new JPanel();
        contentPane.setBorder(new EmptyBorder(5, 5, 5, 5));
        setContentPane(contentPane);
        contentPane.setLayout(null);

        JScrollPane scrollPane = new JScrollPane();
        scrollPane.setBounds(12, 10, 352, 340);
        contentPane.add(scrollPane);

        textArea = new JTextArea();
        textArea.setEditable(false);
        scrollPane.setViewportView(textArea);

        txtInput = new JTextField();
        txtInput.setBounds(91, 365, 185, 40);
        contentPane.add(txtInput);
        txtInput.setColumns(10);

        btnSend = new JButton("Send");
        btnSend.setBounds(288, 364, 76, 40);
        contentPane.add(btnSend);

        lblUserName = new JLabel("Name");
        lblUserName.setHorizontalAlignment(SwingConstants.CENTER);
        lblUserName.setBounds(12, 364, 67, 40);
        contentPane.add(lblUserName);
        setVisible(true);

        AppendText("User " + username + " connecting " + ip_addr + " " + port_no + "\n");
        UserName = username;
        lblUserName.setText(username + ">");

        try {
            socket = new Socket(ip_addr, Integer.parseInt(port_no));
            is = socket.getInputStream();
            dis = new DataInputStream(is);
            os = socket.getOutputStream();
            dos = new DataOutputStream(os);

            SendMessage("/login " + UserName);
            ListenNetwork net = new ListenNetwork();
            net.start();
            Myaction action = new Myaction();
            btnSend.addActionListener(action);
            txtInput.addActionListener(action);
            txtInput.requestFocus();
        } catch (NumberFormatException | IOException e) {
            e.printStackTrace();
            AppendText("connect error");
        }
    }

    // Server Message를 수신해서 화면에 표시
    class ListenNetwork extends Thread {
        public void run() {
            while (true) {
                try {
                    // Use readUTF to read messages
                    String msg = dis.readUTF();
                    AppendText(msg);
                } catch (IOException e) {
                    AppendText("dis.read() error");
                    try {
                        dos.close();
                        dis.close();
                        socket.close();
                        break;
                    } catch (Exception ee) {
                        break;
                    }
                }
            }
        }
    }

    // 메시지를 입력 후 Send 버튼 또는  keyboard enter key를 치면 서버로(다른 사용자에게) 전송
    class Myaction implements ActionListener // 내부클래스로 액션 이벤트 처리 클래스
    {
        @Override
        public void actionPerformed(ActionEvent e) {
            // Send button을 누르거나 메시지 입력하고 Enter key 치면
            if (e.getSource() == btnSend || e.getSource() == txtInput) {
                String msg = null;
                msg = String.format("[%s] %s\n", UserName, txtInput.getText());
                SendMessage(msg);
                txtInput.setText(""); // 메세지를 보내고 나면 메세지 쓰는창을 비운다.
                txtInput.requestFocus(); // 메세지를 보내고 커서를 다시 텍스트 필드로 위치시킨다
                if (msg.contains("/exit")) // 종료 처리
                    System.exit(0);
                // 지금은 system.exit로 창까지 닫았으나(이 방법은 테스트용으로 쓰는 것은 괜찮으나),
                // 실무적으로는 소켓만 닫고(단톡방 퇴장) GUI는 사용자가 닫게 하거나,
                // "/exit"를 입력하는 순간, 로그 메시지를 내보내서 GUI 종료를 유도하는 방법이 보통의 표준적인 패턴
            }
        }
    }

    // 화면에 출력
    public void AppendText(String msg) {
        textArea.append(msg);
        textArea.setCaretPosition(textArea.getText().length());
    }


    // Server에게 network로 전송
    public void SendMessage(String msg) {
        try {
            // Use writeUTF to send messages
            dos.writeUTF(msg);
        } catch (IOException e) {
            AppendText("dos.write() error");
            try {
                dos.close();
                dis.close();
                socket.close();
            } catch (IOException e1) {
                e1.printStackTrace();
                System.exit(0);
            }
        }
    }
}
