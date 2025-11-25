import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
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

        // 🌟 [수정] JTextArea 이름 변경 및 초기 설정
        displayArea = new JTextArea("서버에 연결하세요...");
        displayArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(displayArea);
        add(scrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));

        // 1. 게임 시작 버튼 (방장 전용)
        startGameButton = new JButton("게임 시작 (4명 이상)");
        startGameButton.setVisible(false); // 초기에는 숨김
        // 🌟 [수정] 클라이언트의 handleStartClick() 호출
        startGameButton.addActionListener(e -> client.handleStartClick());
        bottomPanel.add(startGameButton);

        // 2. 준비/취소 버튼 (일반 참여자 전용)
        readyButton = new JButton("준비");
        readyButton.setVisible(false); // 초기에는 숨김
        // 🌟 [추가] 클라이언트의 handleReadyClick() 호출
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
        displayArea.setCaretPosition(displayArea.getDocument().getLength()); // 스크롤 하단
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

    /**
     * 🌟 [수정] 목록 리셋 시 사용. 채팅 영역도 초기화.
     */
    public void clearPlayerList() {
        displayArea.setText("참가자 목록을 갱신 중입니다...\n");
    }

    // 🌟 [삭제] 기존의 enableStartButton(), disableStartButton() 함수는
    // updateButtons() 함수로 대체되어 삭제합니다.
}