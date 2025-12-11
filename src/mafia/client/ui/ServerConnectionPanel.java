package mafia.client.ui;

import mafia.client.Client;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

public class ServerConnectionPanel extends JPanel {

    private JTextField nicknameField;
    private JTextField ipAddressField;
    private JTextField portNumberField;
    private JButton connectButton;

    public ServerConnectionPanel(Client client) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        nicknameField = new JTextField("P" + (int)(Math.random() * 1000), 10);
        ipAddressField = new JTextField("127.0.0.1", 10);
        portNumberField = new JTextField("9090", 10);

        connectButton = new JButton("서버 연결");
        connectButton.addActionListener(e -> {
            try {
                String nick = getNickname();
                String ip = getIpAddress();
                int port = getPort();
                client.connectToServer(nick, ip, port);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "포트 번호가 올바르지 않습니다.", "오류", JOptionPane.ERROR_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "연결 실패: " + ex.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
            }
        });

        add(new JLabel("닉네임:"));
        add(nicknameField);
        add(Box.createVerticalStrut(5));

        add(new JLabel("IP 주소:"));
        add(ipAddressField);
        add(Box.createVerticalStrut(5));

        add(new JLabel("포트 넘버:"));
        add(portNumberField);
        add(Box.createVerticalStrut(15));

        add(connectButton);
        add(Box.createVerticalStrut(10));

        JLabel descLabel = new JLabel("<html>서버 연결 후<br>게임 참여 가능</html>", SwingConstants.CENTER);
        descLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        add(descLabel);

        add(Box.createVerticalGlue());
    }

    public String getNickname() {
        return nicknameField.getText().trim();
    }

    public String getIpAddress() {
        return ipAddressField.getText().trim();
    }

    public int getPort() {
        return Integer.parseInt(portNumberField.getText().trim());
    }
}
