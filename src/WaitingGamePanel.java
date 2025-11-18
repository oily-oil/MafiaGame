import javax.swing.*;
import java.awt.*;
import java.util.List;

public class WaitingGamePanel extends JPanel {

    private JTextArea roomListArea;
    private JButton startGameButton;
    private final Client client;

    public WaitingGamePanel(Client client) {
        this.client = client;

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

    /**
     * 서버로부터 받은 플레이어 목록(리스트 형태)을 사용해 표시한다.
     */
    public void updatePlayerList(java.util.List<String> players) {
        if (players == null || players.isEmpty()) {
            roomListArea.setText("참가자가 없습니다.");
            disableStartButton();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("--- 참가자 (").append(players.size()).append("명) ---\n");
        for (String p : players) {
            sb.append(p).append("\n");
        }
        roomListArea.setText(sb.toString());
    }

    /**
     * 기존 문자열 포맷("a,b,c")이 왔을 때를 위한 호환 메서드
     */
    public void updateRoomList(String playerListCsv) {
        if (playerListCsv == null || playerListCsv.trim().isEmpty()) {
            roomListArea.setText("참가자가 없습니다.");
            disableStartButton();
            return;
        }
        String[] arr = playerListCsv.split(",");
        updatePlayerList(java.util.Arrays.asList(arr));
    }

    public void enableStartButton() {
        startGameButton.setEnabled(true);
    }

    public void disableStartButton() {
        startGameButton.setEnabled(false);
    }

    public void clearPlayerList() {
        roomListArea.setText("서버에 연결하세요...");
        disableStartButton();
    }
}
