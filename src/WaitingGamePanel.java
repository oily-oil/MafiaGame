import javax.swing.*;
import java.awt.*;

public class WaitingGamePanel extends JPanel {

    private JTextArea roomListArea;
    private JButton startGameButton;

    public WaitingGamePanel(Client client) {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("참가자 목록:"));
        add(topPanel, BorderLayout.NORTH);

        roomListArea = new JTextArea("서버에 연결하세요...");
        roomListArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(roomListArea);
        add(scrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        startGameButton = new JButton("게임 시작 (/start)");
        startGameButton.setEnabled(false);
        startGameButton.addActionListener(e -> client.sendMessage("/start"));
        bottomPanel.add(startGameButton);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    public void updateRoomList(String playerList) {
        roomListArea.setText("--- 참가자 (" + playerList.split(",").length + "명) ---\n" + playerList.replace(",", "\n"));
    }

    public void enableStartButton() {
        startGameButton.setEnabled(true);
    }

    public void disableStartButton() {
        startGameButton.setEnabled(false);
    }
}
