import javax.swing.*;
import java.awt.*;
import java.util.List;

public class WaitingGamePanel extends JPanel {

    private JTextArea displayArea;

    private JButton startGameButton;
    private JButton readyButton;

    private final Client client;

    public WaitingGamePanel(Client client) {
        this.client = client;

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("로비 상태 및 채팅:"));
        add(topPanel, BorderLayout.NORTH);

        displayArea = new JTextArea("서버에 연결하세요...");
        displayArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(displayArea);
        add(scrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));

        startGameButton = new JButton("게임 시작 (4명 이상)");
        startGameButton.setVisible(false);
        startGameButton.addActionListener(e -> client.handleStartClick());
        bottomPanel.add(startGameButton);

        readyButton = new JButton("준비");
        readyButton.setVisible(false);
        readyButton.addActionListener(e -> client.handleReadyClick());
        bottomPanel.add(readyButton);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    public void updatePlayerList(List<String> players) {
        if (players == null || players.isEmpty()) {
            displayArea.append("\n--- 참가자 (0명) ---\n참가자가 없습니다.\n");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n--- 참가자 (").append(players.size()).append("명) ---\n");
        for (String p : players) {
            sb.append(p).append("\n");
        }

        displayArea.append(sb.toString());
        displayArea.setCaretPosition(displayArea.getDocument().getLength());
    }

    public void appendChatMessage(String message) {
        displayArea.append(message + "\n");
        displayArea.setCaretPosition(displayArea.getDocument().getLength());
    }

    public void updateButtons(boolean isHost, boolean isReady) {
        startGameButton.setVisible(isHost);
        readyButton.setVisible(!isHost);

        if (isHost) {
            startGameButton.setText("게임 시작 (4명 이상)");
            startGameButton.setEnabled(true);
            readyButton.setText("준비 완료 (방장)");
            readyButton.setEnabled(false);
        } else {
            readyButton.setText(isReady ? "준비 취소" : "준비");
            readyButton.setEnabled(true);
            startGameButton.setEnabled(false);
        }

        revalidate();
        repaint();
    }

    public void clearDisplay() {
        displayArea.setText("--- 게임이 종료되었습니다. 로비 상태로 돌아왔습니다. ---\n");
        displayArea.setCaretPosition(displayArea.getDocument().getLength());
    }
}
