import javax.swing.*;
import java.awt.*;

public class GamePanel extends JPanel {

    private JTextArea chatArea;
    private JTextField inputField;

    public GamePanel(Client client) {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("게임 대화창"));
        add(topPanel, BorderLayout.NORTH);

        chatArea = new JTextArea("게임 시작을 기다립니다...");
        chatArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(chatArea);
        add(scrollPane, BorderLayout.CENTER);

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputField = new JTextField();
        inputPanel.add(new JLabel("(입력)"), BorderLayout.WEST);
        inputPanel.add(inputField, BorderLayout.CENTER);
        add(inputPanel, BorderLayout.SOUTH);

        inputField.addActionListener(e -> client.sendMessage(inputField.getText()));
    }

    public void appendChatMessage(String message) {
        chatArea.append(message + "\n");
    }

    public void enableInputField(boolean enable) {
        inputField.setEnabled(enable);
    }
}
