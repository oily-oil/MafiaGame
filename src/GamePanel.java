import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

public class GamePanel extends JPanel {

    private Image backgroundImage;

    // [수정] messageContainerPanel 대신 contentPanel을 JScrollPane의 뷰포트에 직접 사용
    private JPanel contentPanel; // [수정] 실제 메시지 행을 담을 패널
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

        // [수정] messageContainerPanel 제거, contentPanel을 사용
        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setAlignmentY(Component.TOP_ALIGNMENT);
        contentPanel.setOpaque(false);

        chatScrollPane = new JScrollPane(contentPanel); // [수정] contentPanel을 JScrollPane에 연결
        chatScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        chatScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        chatScrollPane.setOpaque(false);
        chatScrollPane.getViewport().setOpaque(false);

        // [수정] appendChatMessage 인자 수정
        appendChatMessage("시스템", "게임 시작을 기다립니다...", false);

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
                // [수정] appendChatMessage 인자 수정
                appendChatMessage("시스템", "[투표] P" + playerNumber + " 에게 투표했습니다.", false);
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

    // [수정] appendChatMessage 메서드 오버로드 제거 및 구조 변경
    public void appendChatMessage(String sender, String message, boolean isMyMessage) {
        // [수정] 시스템 메시지 처리
        if (sender.equals("시스템")) {
            JLabel systemLabel = new JLabel("<html><font color='gray'>[시스템] " + message + "</font></html>");
            systemLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
            systemLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

            JPanel rowPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
            rowPanel.setOpaque(false);
            rowPanel.add(systemLabel);

            contentPanel.add(rowPanel); // [수정] contentPanel에 추가

            // 시스템 메시지는 별도의 재계산이 필요 없음
            contentPanel.revalidate();
            contentPanel.repaint();

            if (chatScrollPane != null) {
                SwingUtilities.invokeLater(() -> {
                    JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
                    vertical.setValue(vertical.getMaximum());
                });
            }
        } else {
            // [수정] ChatMessagePanel을 사용하여 말풍선 형태로 표시
            ChatMessagePanel chatRow = new ChatMessagePanel(sender, message, isMyMessage);

            // [수정] 메시지 행을 담을 FlowLayout 패널을 생성하여 정렬 담당
            JPanel alignmentRow = new JPanel();
            alignmentRow.setLayout(new FlowLayout(isMyMessage ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
            alignmentRow.setOpaque(false);

            // [수정] ChatMessagePanel의 최대 너비를 제한하여 내용물 크기 이상으로 늘어나지 않도록 함
            int maxChatWidth = (int) (chatScrollPane.getWidth() * 0.70);
            chatRow.setMaximumSize(new Dimension(maxChatWidth, Integer.MAX_VALUE));

            alignmentRow.add(chatRow); // ChatMessagePanel을 FlowLayout 패널에 추가
            contentPanel.add(alignmentRow); // FlowLayout 패널을 BoxLayout(Y_AXIS)에 추가

            // [수정] ⚠️ 최종 안정화 로직: 메시지를 추가한 후, UI 큐의 맨 뒤에서 크기 계산 및 적용을 강제합니다.
            SwingUtilities.invokeLater(() -> {
                // 1. contentPanel을 유효화하여 chatRow의 preferredSize를 확정합니다.
                contentPanel.revalidate();

                // 2. ChatMessagePanel의 최종 선호 크기를 얻어 maximumSize로 재설정
                Dimension preferredSize = chatRow.getPreferredSize();
                int finalWidth = Math.min(preferredSize.width, maxChatWidth);

                // 높이도 preferredSize를 따르도록 설정
                chatRow.setMaximumSize(new Dimension(finalWidth, preferredSize.height));

                // 3. 크기 제한을 적용하기 위해 다시 한번 유효화합니다. (매우 중요)
                contentPanel.revalidate();

                // 4. 스크롤 최하단 이동
                if (chatScrollPane != null) {
                    JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
                    vertical.setValue(vertical.getMaximum());
                }
            });
        }
    }

    // [수정] 인자가 4개였던 오버로드 메서드 제거, 인자 1개 오버로드 메서드도 3개 인자 버전으로 변경
    public void appendChatMessage(String message) {
        appendChatMessage("시스템", message, false);
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
        contentPanel.removeAll(); // [수정] messageContainerPanel 대신 contentPanel 사용
        // [수정] appendChatMessage 인자 수정
        appendChatMessage("시스템", "게임 시작을 기다립니다...", false);
        contentPanel.revalidate();
        contentPanel.repaint();

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

    // [수정] 내부 클래스: BubblePanel
    class BubblePanel extends JPanel {
        private final boolean isMyMessage;

        // 꼬리 크기
        private static final int TAIL_SIZE = 5;
        // 둥근 모서리 반지름
        private static final int ARC = 15;

        public BubblePanel(boolean isMyMessage) {
            super(new FlowLayout(isMyMessage ? FlowLayout.RIGHT : FlowLayout.LEFT));
            this.isMyMessage = isMyMessage;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(4, TAIL_SIZE + 5, 4, TAIL_SIZE + 5));
        }

        // [수정] getPreferredSize 오버라이드 유지 (내부 레이아웃 크기 계산)
        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            d.width += TAIL_SIZE;
            return d;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            // 배경색 설정 (말풍선 색상)
            if (isMyMessage) {
                g2.setColor(new Color(200, 230, 255)); // 내가 보낸 메시지
            } else {
                g2.setColor(Color.WHITE); // 상대방 메시지
            }

            Shape bubble;

            if (isMyMessage) {
                bubble = new RoundRectangle2D.Double(0, 0, w - TAIL_SIZE, h, ARC, ARC);

                int[] xPoints = {w - TAIL_SIZE, w - TAIL_SIZE, w};
                int[] yPoints = {h - 1 - TAIL_SIZE * 2, h - 1, h - 1};
                g2.fillPolygon(xPoints, yPoints, 3);
            } else {
                bubble = new RoundRectangle2D.Double(TAIL_SIZE, 0, w - TAIL_SIZE, h, ARC, ARC);

                int[] xPoints = {0, TAIL_SIZE, TAIL_SIZE};
                int[] yPoints = {h - 1, h - 1 - TAIL_SIZE * 2, h - 1};
                g2.fillPolygon(xPoints, yPoints, 3);
            }

            g2.fill(bubble);
            g2.dispose();
        }
    }

    // [수정] 내부 클래스: ChatMessagePanel
    class ChatMessagePanel extends JPanel {

        private static final ImageIcon DEFAULT_ICON = loadDefaultIcon();

        private static ImageIcon loadDefaultIcon() {
            try {
                java.net.URL imageUrl = GamePanel.class.getResource("/default.png");
                if (imageUrl != null) {
                    Image img = ImageIO.read(imageUrl).getScaledInstance(40, 40, Image.SCALE_SMOOTH);
                    return new ImageIcon(img);
                }
            } catch (IOException e) {
                System.err.println("기본 프로필 이미지 로드 실패: /default.png");
            }
            // 로드 실패 시 대체 아이콘 (빈 이미지) 반환
            return new ImageIcon(new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB));
        }

        public ChatMessagePanel(String sender, String message, boolean isMyMessage) {
            // [수정] 레이아웃을 FlowLayout으로 변경하여 내용물에 따라 좌우로 밀착되도록 강제
            setLayout(new FlowLayout(isMyMessage ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0)); // 전체 패널의 좌우 여백 제거

            // 1. 전체 메시지 컨테이너 (닉네임 + 말풍선)
            JPanel messageBubbleContainer = new JPanel();
            messageBubbleContainer.setOpaque(false);
            // 수직으로 닉네임과 말풍선을 배치
            messageBubbleContainer.setLayout(new BoxLayout(messageBubbleContainer, BoxLayout.Y_AXIS));

            // 2. 프로필 이미지
            JLabel profileLabel = new JLabel(DEFAULT_ICON);
            profileLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
            profileLabel.setAlignmentY(Component.BOTTOM_ALIGNMENT); // 프로필을 하단에 정렬

            // 3. 닉네임 라벨 (상단)
            JLabel senderLabel = new JLabel(sender);
            senderLabel.setFont(new Font("맑은 고딕", Font.BOLD, 10));
            senderLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 2, 5));
            senderLabel.setAlignmentX(isMyMessage ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);

            // 4. 메시지 말풍선 패널 (하단)
            BubblePanel bubblePanel = new BubblePanel(isMyMessage);

            // 메시지 내용 라벨
            JLabel messageLabel = new JLabel("<html>" + message + "</html>");
            messageLabel.setFont(new Font("맑은 고딕", Font.PLAIN, 10));
            messageLabel.setForeground(isMyMessage ? Color.BLACK : Color.DARK_GRAY);
            messageLabel.setOpaque(false);

            bubblePanel.add(messageLabel);

            // 닉네임과 말풍선 컨테이너에 추가
            messageBubbleContainer.add(senderLabel);
            messageBubbleContainer.add(bubblePanel);

            // 5. FlowLayout에 컴포넌트 추가
            if (isMyMessage) {
                // 내가 보낸 메시지: [내용물] [프로필]
                add(messageBubbleContainer);
                add(profileLabel);
            } else {
                // 상대방 메시지: [프로필] [내용물]
                add(profileLabel);
                add(messageBubbleContainer);
            }

            // [수정] getAlignmentX 오버라이드로 정렬을 강제합니다.
        }

        // [수정] ⚠️ 최종 수정: getAlignmentX 오버라이드
        @Override
        public float getAlignmentX() {
            // FlowLayout의 정렬 속성을 직접 확인하여 BoxLayout에서 정렬을 강제합니다.
            FlowLayout layout = (FlowLayout) getLayout();
            if (layout.getAlignment() == FlowLayout.RIGHT) {
                return Component.RIGHT_ALIGNMENT; // 1.0f
            } else {
                return Component.LEFT_ALIGNMENT; // 0.0f
            }
        }

        // [수정] getMaximumSize 오버라이드: 강제 최대 너비 설정
        @Override
        public Dimension getMaximumSize() {
            Container parent = getParent();
            if (parent != null && parent.getParent() != null) {
                // chatScrollPane의 너비(parent.getParent())를 기준으로 최대 너비를 설정
                int maxChatWidth = (int) (parent.getParent().getWidth() * 0.70);
                // preferredSize가 계산된 이후에 호출되므로, 정확한 preferredSize를 얻습니다.
                int preferredWidth = super.getPreferredSize().width;

                // 너비는 maxChatWidth와 preferredWidth 중 작은 값으로, 높이는 유연하게 preferredSize로 설정
                int finalWidth = Math.min(preferredWidth, maxChatWidth);

                return new Dimension(finalWidth, super.getPreferredSize().height);
            }
            // 부모가 아직 없거나 크기를 알 수 없을 때는 preferredSize를 사용
            return super.getPreferredSize();
        }

        // [수정] getPreferredSize 오버라이드
        @Override
        public Dimension getPreferredSize() {
            // FlowLayout을 사용하므로, super.getPreferredSize()를 사용하여 내용물 크기에 따라 정확히 계산됩니다.
            return super.getPreferredSize();
        }
    }
}