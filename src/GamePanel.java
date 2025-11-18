import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class GamePanel extends JPanel {

    private JTextArea chatArea;
    private JTextField inputField;
    private JPanel playerButtonPanel;
    private JButton voteButton;
    private JButton skillButton;

    private List<JButton> playerButtons = new ArrayList<>();
    private String selectedPlayer = null;

    private final Client client;

    public GamePanel(Client client) {
        this.client = client;

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setPreferredSize(new Dimension(500, 600));

        // 상단 타이틀
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("게임 대화창"));
        add(topPanel, BorderLayout.NORTH);

        // 채팅 영역
        chatArea = new JTextArea("게임 시작을 기다립니다...");
        chatArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(chatArea);
        add(scrollPane, BorderLayout.CENTER);

        // 하단 영역 전체 패널
        JPanel bottomPanel = new JPanel(new BorderLayout());
        add(bottomPanel, BorderLayout.SOUTH);

        // 입력창
        JPanel inputPanel = new JPanel(new BorderLayout());
        inputField = new JTextField();
        inputPanel.add(new JLabel("(입력)"), BorderLayout.WEST);
        inputPanel.add(inputField, BorderLayout.CENTER);
        bottomPanel.add(inputPanel, BorderLayout.NORTH);

        // 엔터 입력 → 서버로 메시지 전송
        inputField.addActionListener(e -> {
            client.sendMessage(inputField.getText());
            inputField.setText("");
        });

        // 플레이어 버튼 영역
        playerButtonPanel = new JPanel();
        playerButtonPanel.setLayout(new GridLayout(2, 5, 5, 5));  // 최대 10명 기준
        bottomPanel.add(playerButtonPanel, BorderLayout.CENTER);

        // 투표 / 능력 버튼
        JPanel actionPanel = new JPanel(new FlowLayout());

        voteButton = new JButton("투표");
        skillButton = new JButton("능력");

        actionPanel.add(voteButton);
        actionPanel.add(skillButton);

        bottomPanel.add(actionPanel, BorderLayout.SOUTH);

        // 투표 버튼 동작
        voteButton.addActionListener(e -> {
            if (selectedPlayer != null) {
                client.sendMessage("/vote " + selectedPlayer);
                appendChatMessage("[투표] " + selectedPlayer + " 에게 투표했습니다.");
            } else {
                JOptionPane.showMessageDialog(this, "플레이어를 먼저 선택하세요.");
            }
        });

        // 능력 사용 버튼 동작
        skillButton.addActionListener(e -> {
            if (selectedPlayer != null) {
                client.sendMessage("/skill " + selectedPlayer);
                appendChatMessage("[능력 사용] 대상: " + selectedPlayer);
            } else {
                JOptionPane.showMessageDialog(this, "플레이어를 먼저 선택하세요.");
            }
        });
    }

    /** 채팅 메시지 추가 */
    public void appendChatMessage(String message) {
        chatArea.append(message + "\n");
    }

    /** 입력창 활성/비활성 */
    public void enableInputField(boolean enable) {
        inputField.setEnabled(enable);
    }

    /** ▶ 서버에서 받은 플레이어 리스트로 버튼 업데이트 */
    public void updatePlayerList(List<String> players) {

        playerButtonPanel.removeAll();
        playerButtons.clear();
        selectedPlayer = null;

        for (String p : players) {
            JButton btn = new JButton(p);
            btn.setFocusable(false);

            btn.addActionListener(e -> {
                selectedPlayer = p;
                highlightSelectedButton(btn);
            });

            playerButtons.add(btn);
            playerButtonPanel.add(btn);
        }

        playerButtonPanel.revalidate();
        playerButtonPanel.repaint();
    }

    /** ▶ 선택된 버튼 강조 */
    private void highlightSelectedButton(JButton selected) {
        for (JButton btn : playerButtons) {
            btn.setBackground(null);
            btn.setForeground(Color.BLACK);
        }

        selected.setBackground(Color.BLACK);
        selected.setForeground(Color.WHITE);
    }
}
