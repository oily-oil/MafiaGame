import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

public class GamePanel extends JPanel {

    private Image backgroundImage;

    private JPanel messageContainerPanel;
    private JScrollPane chatScrollPane;

    private JTextField inputField;
    private JPanel playerButtonPanel;
    private JButton voteButton;
    private JButton skillButton;

    private JLabel timerLabel;

    private List<JButton> playerButtons = new ArrayList<>();
    private String selectedPlayer = null;

    private final Client client;

    private String currentPhase = "WAITING";

    public GamePanel(Client client) {
        this.client = client;

        try {
            java.net.URL imageUrl = getClass().getResource("/background.png");
            if (imageUrl != null) {
                backgroundImage = ImageIO.read(imageUrl);
            } else {
                System.err.println("경고: 클래스 경로에서 /background.png 이미지를 찾을 수 없습니다. (경로 확인 필요)");
            }
        } catch (IOException e) {
            System.err.println("배경 이미지 로드 중 오류 발생.");
            e.printStackTrace();
        }

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setPreferredSize(new Dimension(400, 600));

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setOpaque(false);

        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        titlePanel.add(new JLabel("게임 대화창"));
        titlePanel.setOpaque(false);
        headerPanel.add(titlePanel, BorderLayout.WEST);

        timerLabel = new JLabel("현재 단계: 대기 중", SwingConstants.RIGHT);
        timerLabel.setFont(new Font("맑은 고딕", Font.BOLD, 14));
        timerLabel.setForeground(Color.BLUE);
        timerLabel.setOpaque(false);
        headerPanel.add(timerLabel, BorderLayout.EAST);

        add(headerPanel, BorderLayout.NORTH);

        messageContainerPanel = new JPanel();
        messageContainerPanel.setLayout(new BoxLayout(messageContainerPanel, BoxLayout.Y_AXIS));
        messageContainerPanel.setAlignmentY(Component.TOP_ALIGNMENT);
        messageContainerPanel.setOpaque(false);

        chatScrollPane = new JScrollPane(messageContainerPanel);
        chatScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        chatScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        chatScrollPane.setOpaque(false);
        chatScrollPane.getViewport().setOpaque(false);

        appendChatMessage("시스템", "게임 시작을 기다립니다...", false, false);

        add(chatScrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setOpaque(false);
        add(bottomPanel, BorderLayout.SOUTH);

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setOpaque(false);
        inputField = new JTextField();
        inputPanel.add(new JLabel("(입력)"), BorderLayout.WEST);
        inputPanel.add(inputField, BorderLayout.CENTER);
        bottomPanel.add(inputPanel, BorderLayout.NORTH);

        inputField.addActionListener(e -> {
            client.sendMessage(inputField.getText());
            inputField.setText("");
        });

        playerButtonPanel = new JPanel();
        playerButtonPanel.setLayout(new GridLayout(2, 5, 5, 5));
        playerButtonPanel.setOpaque(false);
        bottomPanel.add(playerButtonPanel, BorderLayout.CENTER);

        JPanel actionPanel = new JPanel(new FlowLayout());
        actionPanel.setOpaque(false);

        voteButton = new JButton("투표");
        skillButton = new JButton("능력");

        voteButton.setVisible(false);
        skillButton.setVisible(false);

        actionPanel.add(voteButton);
        actionPanel.add(skillButton);

        bottomPanel.add(actionPanel, BorderLayout.SOUTH);

        voteButton.addActionListener(e -> {
            if (selectedPlayer != null) {
                String playerNumber = extractPlayerNumber(selectedPlayer);
                client.sendMessage("/vote " + playerNumber);
                appendChatMessage("시스템", "[투표] P" + playerNumber + " 에게 투표했습니다.", false, false);
            } else {
                JOptionPane.showMessageDialog(this, "투표 대상을 먼저 선택하세요.");
            }
        });

        skillButton.addActionListener(e -> {
            if (selectedPlayer != null) {
                String playerNumber = extractPlayerNumber(selectedPlayer);
                String command = "/skill " + playerNumber;

                client.sendMessage(command);
            } else {
                JOptionPane.showMessageDialog(this, "능력 대상을 먼저 선택하세요.");
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (backgroundImage != null) {
            g.drawImage(backgroundImage, 0, 0, getWidth(), getHeight(), this);
        }
    }

    public String getCurrentPhase() {
        return this.currentPhase;
    }


    private String extractPlayerNumber(String playerString) {
        try {
            if (playerString.startsWith("P")) {
                int dashIndex = playerString.indexOf(" -");
                if (dashIndex != -1) {
                    return playerString.substring(1, dashIndex);
                }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }
    public void appendChatMessage(String sender, String message, boolean isMyMessage, boolean isMafiaChat) {
        JLabel messageLabel = new JLabel("<html>" + sender + ": " + message + "</html>");

        JPanel messageRowPanel = new JPanel(isMyMessage ? new FlowLayout(FlowLayout.RIGHT) : new FlowLayout(FlowLayout.LEFT));
        messageRowPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, messageLabel.getPreferredSize().height + 10));
        messageRowPanel.setOpaque(false);

        messageLabel.setOpaque(true);
        if (isMafiaChat) {
            messageLabel.setBackground(new Color(255, 100, 100));
            messageLabel.setForeground(Color.WHITE);
        } else if (isMyMessage) {
            messageLabel.setBackground(new Color(200, 230, 255));
            messageLabel.setForeground(Color.BLACK);
        } else if (sender.equals("시스템")) {
            messageLabel.setForeground(Color.GRAY);
            messageLabel.setBackground(null);
            messageLabel.setOpaque(false);
        } else {
            messageLabel.setBackground(new Color(240, 240, 240, 180));
            messageLabel.setForeground(Color.BLACK);
        }
        messageLabel.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));

        messageRowPanel.add(messageLabel);

        messageContainerPanel.add(messageRowPanel);

        messageContainerPanel.revalidate();
        messageContainerPanel.repaint();

        if (chatScrollPane != null) {
            SwingUtilities.invokeLater(() -> {
                JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
                vertical.setValue(vertical.getMaximum());
            });
        }
    }

    public void appendChatMessage(String message) {
        appendChatMessage("시스템", message, false, false);
    }

    public void enableInputField(boolean enable) {
        inputField.setEnabled(enable);
    }

    public void updatePlayerList(List<String> players) {
        playerButtonPanel.removeAll();
        playerButtons.clear();
        selectedPlayer = null;

        for (String p : players) {
            JButton btn = new JButton(p);
            btn.setFocusable(false);

            btn.addActionListener(e -> {
                selectedPlayer = btn.getText();
                highlightSelectedButton(btn);
            });

            playerButtons.add(btn);
            playerButtonPanel.add(btn);
        }

        playerButtonPanel.revalidate();
        playerButtonPanel.repaint();
    }

    public void clearGameState() {
        messageContainerPanel.removeAll();
        appendChatMessage("시스템", "게임 시작을 기다립니다...", false, false);
        messageContainerPanel.revalidate();
        messageContainerPanel.repaint();

        updateTimer("WAITING", 0);

        selectedPlayer = null;
        for (JButton btn : playerButtons) {
            btn.setBackground(null);
            btn.setForeground(Color.BLACK);
        }
    }

    private void highlightSelectedButton(JButton selected) {
        for (JButton btn : playerButtons) {
            btn.setBackground(null);
            btn.setForeground(Color.BLACK);
        }

        selected.setBackground(Color.BLACK);
        selected.setForeground(Color.WHITE);
    }

    public void updateTimer(String phase, int secondsLeft) {
        this.currentPhase = phase;
        String phaseText = "";

        // Client 클래스에 있어야 하는 메서드 (가정)
        boolean isAbilityUser = client.hasAbility();
        boolean isClientAlive = client.isAlive();


        if (!isClientAlive) {
            voteButton.setVisible(false);
            skillButton.setVisible(false);
            inputField.setEnabled(true);
            voteButton.setEnabled(false);
            skillButton.setEnabled(false);
            phaseText = "사망 (관전자 모드)";
        } else {
            inputField.setEnabled(true);

            switch (phase) {
                case "WAITING":
                    phaseText = "대기 중";
                    voteButton.setVisible(false);
                    skillButton.setVisible(false);
                    break;
                case "DAY":
                    phaseText = "낮 (토론/투표)";
                    voteButton.setVisible(true);
                    skillButton.setVisible(false);
                    break;
                case "NIGHT":
                    phaseText = "밤 (능력 사용)";
                    voteButton.setVisible(false);
                    skillButton.setVisible(isAbilityUser);
                    break;
                default:
                    phaseText = "정보 없음";
                    voteButton.setVisible(false);
                    skillButton.setVisible(false);
            }

            voteButton.setEnabled(true);
            skillButton.setEnabled(true);
        }

        int minutes = secondsLeft / 60;
        int seconds = secondsLeft % 60;
        String timeString = String.format("%02d:%02d", minutes, seconds);

        String finalPhaseText = phaseText;
        SwingUtilities.invokeLater(() -> {
            timerLabel.setText("현재 단계: " + finalPhaseText + " (" + timeString + ")");

            if (phase.equals("DAY")) {
                timerLabel.setForeground(Color.RED);
            } else if (phase.equals("NIGHT")) {
                timerLabel.setForeground(Color.BLUE);
            } else {
                timerLabel.setForeground(Color.BLACK);
            }
        });
    }
}