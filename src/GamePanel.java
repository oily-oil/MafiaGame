import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Map;

public class GamePanel extends JPanel {

    private Image backgroundImage;
    private JPanel contentPanel;
    private JScrollPane chatScrollPane;

    private JTextField inputField;
    private JPanel playerButtonPanel;
    private JButton voteButton;
    private JButton skillButton;

    private JLabel timerLabel;

    private JLabel myRoleIconLabel;

    private List<JButton> playerButtons = new ArrayList<>();
    private String selectedPlayer = null;

    private final Client client;

    private String currentPhase = "WAITING";

    private static final int PROFILE_ICON_SIZE = 50;
    private static final int ROLE_ICON_SIZE = 40;

    // [수정] loadProfileImage: 버튼 선택/마크 이미지 로드 로직 제거됨
    private BufferedImage loadProfileImage(String playerInfo) {
        String playerNumber = extractPlayerNumber(playerInfo);
        String imageName = "unknown.png";

        String investigatedRole = client.getInvestigatedRoles().get("P" + playerNumber);

        if (investigatedRole != null) {
            if (investigatedRole.equals("MAFIA")) {
                imageName = "mafia.png";
            } else {
                imageName = "citizen.png";
            }
        }
        else {
            String myRole = client.getMyRole();
            String myPlayerInfo = "P" + client.getMyPlayerNumber() + " -";

            if (playerInfo.startsWith(myPlayerInfo) && !myRole.isEmpty()) {
                imageName = myRole.toLowerCase() + ".png";
            }
        }

        String path = "/" + imageName;

        try {
            java.net.URL imageUrl = getClass().getResource(path);
            if (imageUrl != null) {
                BufferedImage original = ImageIO.read(imageUrl);
                BufferedImage scaled = new BufferedImage(PROFILE_ICON_SIZE, PROFILE_ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2d = scaled.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.drawImage(original, 0, 0, PROFILE_ICON_SIZE, PROFILE_ICON_SIZE, null);
                g2d.dispose();
                return scaled;
            }
        } catch (IOException e) {
            System.err.println("경고: 기본/역할 이미지 에셋을 찾을 수 없습니다: " + path);
        }
        return new BufferedImage(PROFILE_ICON_SIZE, PROFILE_ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
    }

    // [제거] loadMarkImage 함수는 필요 없지만, 하위 클래스에서 사용될 수 있으므로 임시로 남겨두거나 삭제해야 합니다.
    // 여기서는 사용하지 않도록 처리하고, loadProfileImage를 기본으로 사용합니다.

    public ImageIcon loadRoleIcon(String role) {
        String imagePath = "/" + role.toLowerCase() + ".png";
        try {
            java.net.URL imageUrl = getClass().getResource(imagePath);
            if (imageUrl != null) {
                Image img = ImageIO.read(imageUrl).getScaledInstance(
                        ROLE_ICON_SIZE, ROLE_ICON_SIZE, Image.SCALE_SMOOTH);
                return new ImageIcon(img);
            }
        } catch (IOException e) {
            System.err.println("경고: 역할 이미지 로드 실패: " + imagePath);
        }
        return new ImageIcon(new BufferedImage(ROLE_ICON_SIZE, ROLE_ICON_SIZE, BufferedImage.TYPE_INT_ARGB));
    }

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

        JPanel newHeaderPanel = new JPanel(new BorderLayout());
        newHeaderPanel.setOpaque(false);

        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        titlePanel.add(new JLabel("게임 대화창"));
        titlePanel.setOpaque(false);
        newHeaderPanel.add(titlePanel, BorderLayout.WEST);

        timerLabel = new JLabel("현재 단계: 대기 중", SwingConstants.RIGHT);
        timerLabel.setFont(new Font("맑은 고딕", Font.BOLD, 14));
        timerLabel.setForeground(Color.BLUE);
        timerLabel.setOpaque(false);

        myRoleIconLabel = new JLabel();
        myRoleIconLabel.setPreferredSize(new Dimension(ROLE_ICON_SIZE, ROLE_ICON_SIZE));

        JPanel rightHeaderPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        rightHeaderPanel.setOpaque(false);
        rightHeaderPanel.add(myRoleIconLabel);
        rightHeaderPanel.add(timerLabel);

        newHeaderPanel.add(rightHeaderPanel, BorderLayout.EAST);

        add(newHeaderPanel, BorderLayout.NORTH);

        contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setAlignmentY(Component.TOP_ALIGNMENT);
        contentPanel.setOpaque(false);

        chatScrollPane = new JScrollPane(contentPanel);
        chatScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        chatScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        chatScrollPane.setOpaque(false);
        chatScrollPane.getViewport().setOpaque(false);

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

    // ⭐️ [수정] updateButtonIcon: isSelected 인자 제거, 버튼 선택 시 이미지 변경 로직 삭제
    private void updateButtonIcon(JButton btn, String playerInfo) {
        BufferedImage baseImage = loadProfileImage(playerInfo);

        btn.setText(null);
        btn.setPreferredSize(new Dimension(PROFILE_ICON_SIZE + 20, PROFILE_ICON_SIZE + 20));

        String playerNumber = extractPlayerNumber(playerInfo);

        // 버튼 선택 여부와 관계없이 마크 오버레이는 적용
        Image finalImage = applyMarkAndRole(baseImage, playerNumber);
        btn.setIcon(new ImageIcon(finalImage));

        // ⭐️ [신규] 버튼이 현재 selectedPlayer와 일치하면 배경색을 변경
        String currentInfo = (String) btn.getClientProperty("PlayerInfo");
        if (currentInfo != null && currentInfo.equals(selectedPlayer)) {
            btn.setBackground(Color.BLACK);
            btn.setForeground(Color.WHITE);
        } else {
            btn.setBackground(null);
            btn.setForeground(Color.BLACK);
        }
    }

    // [수정] applyMarkAndRole: isSelected 인자 제거 및 로직 간소화
    private Image applyMarkAndRole(BufferedImage baseImage, String playerNumber) {

        BufferedImage overlaidImage = new BufferedImage(
                baseImage.getWidth(), baseImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = overlaidImage.createGraphics();
        g2d.drawImage(baseImage, 0, 0, null);

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        String markedPlayer = client.getMarkedPlayer();

        // 밤 능력 대상 마크 적용
        if (markedPlayer.equals("P" + playerNumber) && currentPhase.equals("NIGHT")) {
            String targetPath = "/mark_target.png";
            try {
                java.net.URL targetUrl = getClass().getResource(targetPath);
                if (targetUrl != null) {
                    BufferedImage targetMark = ImageIO.read(targetUrl);
                    g2d.drawImage(targetMark, 0, 0, PROFILE_ICON_SIZE, PROFILE_ICON_SIZE, null);
                }
            } catch (IOException e) {
                System.err.println("경고: 대상 마크 이미지 로드 실패: " + targetPath);
            }
        }

        g2d.dispose();
        return overlaidImage;
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

    public void appendChatMessage(String sender, String message, boolean isMyMessage, String type) {
        if (sender.equals("시스템")) {
            JLabel systemLabel = new JLabel("<html><font color='gray'>[시스템] " + message + "</font></html>");
            systemLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
            systemLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

            JPanel rowPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
            rowPanel.setOpaque(false);
            rowPanel.add(systemLabel);

            contentPanel.add(rowPanel);

            contentPanel.revalidate();
            contentPanel.repaint();

            if (chatScrollPane != null) {
                SwingUtilities.invokeLater(() -> {
                    JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
                    vertical.setValue(vertical.getMaximum());
                });
            }
        } else {
            ChatMessagePanel chatRow = new ChatMessagePanel(sender, message, isMyMessage, type);

            JPanel alignmentRow = new JPanel();
            alignmentRow.setLayout(new FlowLayout(isMyMessage ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
            alignmentRow.setOpaque(false);

            int maxChatWidth = (int) (chatScrollPane.getWidth() * 0.70);
            chatRow.setMaximumSize(new Dimension(maxChatWidth, Integer.MAX_VALUE));

            alignmentRow.add(chatRow);
            contentPanel.add(alignmentRow);

            SwingUtilities.invokeLater(() -> {
                contentPanel.revalidate();
                Dimension preferredSize = chatRow.getPreferredSize();
                int finalWidth = Math.min(preferredSize.width, maxChatWidth);

                chatRow.setMaximumSize(new Dimension(finalWidth, preferredSize.height));

                contentPanel.revalidate();

                if (chatScrollPane != null) {
                    JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
                    vertical.setValue(vertical.getMaximum());
                }
            });
        }
    }

    public void appendChatMessage(String sender, String message, boolean isMyMessage) {
        appendChatMessage(sender, message, isMyMessage, "NORMAL");
    }

    public void updatePlayerMarks() {
        for (JButton btn : playerButtons) {
            String playerInfo = (String) btn.getClientProperty("PlayerInfo");

            if (playerInfo != null) {
                // ⭐️ [수정] updateButtonIcon 호출 시 isSelected 인자 제거
                updateButtonIcon(btn, playerInfo);
            }
        }
        playerButtonPanel.revalidate();
        playerButtonPanel.repaint();
    }

    public void updateMyRoleDisplay(String role) {
        if (role == null || role.equals("UNKNOWN")) {
            myRoleIconLabel.setIcon(null);
            myRoleIconLabel.setToolTipText(null);
        } else {
            ImageIcon icon = loadRoleIcon(role);
            myRoleIconLabel.setIcon(icon);
            myRoleIconLabel.setToolTipText("나의 역할: " + role);
        }
    }

    public void updatePlayerList(List<String> players) {
        playerButtonPanel.removeAll();
        playerButtons.clear();
        selectedPlayer = null;

        for (String p : players) {

            JButton btn = new JButton();
            btn.putClientProperty("PlayerInfo", p);
            btn.setToolTipText(p);

            // ⭐️ [수정] updateButtonIcon 호출 시 isSelected 인자 제거
            updateButtonIcon(btn, p);

            btn.setFocusable(false);

            btn.addActionListener(e -> {
                String currentInfo = (String) btn.getClientProperty("PlayerInfo");
                if (currentInfo.equals(selectedPlayer)) {
                    selectedPlayer = null;
                } else {
                    selectedPlayer = currentInfo;
                }
                highlightSelectedButton(btn);
            });

            playerButtons.add(btn);
            playerButtonPanel.add(btn);
        }

        playerButtonPanel.revalidate();
        playerButtonPanel.repaint();
    }

    public void clearGameState() {
        contentPanel.removeAll();
        appendChatMessage("시스템", "게임 시작을 기다립니다...", false);
        contentPanel.revalidate();
        contentPanel.repaint();

        updateTimer("WAITING", 0);
        updateMyRoleDisplay("UNKNOWN");

        selectedPlayer = null;
        for (JButton btn : playerButtons) {
            btn.setBackground(null);
            btn.setForeground(Color.BLACK);
        }
    }

    private void highlightSelectedButton(JButton selected) {
        // 모든 버튼을 원래 상태(배경색 없음)로 복원하고 아이콘을 재할당
        for (JButton btn : playerButtons) {
            String playerInfo = (String) btn.getClientProperty("PlayerInfo");

            // ⭐️ [수정] 아이콘과 배경색을 초기 상태로 되돌림
            updateButtonIcon(btn, playerInfo);

            btn.setBackground(null);
            btn.setForeground(Color.BLACK);
        }

        if (selectedPlayer != null) {
            // 새로 선택된 버튼을 검은색으로 변경
            selected.setBackground(Color.BLACK);
            selected.setForeground(Color.WHITE);

            // 아이콘은 updateButtonIcon에서 처리했으므로 이미지 관련 로직은 제거
        }
    }

    public void updateTimer(String phase, int secondsLeft) {
        this.currentPhase = phase;
        String phaseText = "";

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

    class BubblePanel extends JPanel {
        private final boolean isMyMessage;
        private final String type;

        private static final int TAIL_SIZE = 5;
        private static final int ARC = 15;

        public BubblePanel(boolean isMyMessage, String type) {
            super(new FlowLayout(isMyMessage ? FlowLayout.RIGHT : FlowLayout.LEFT));
            this.isMyMessage = isMyMessage;
            this.type = type;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(4, TAIL_SIZE + 5, 4, TAIL_SIZE + 5));
        }

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

            Color bubbleColor;
            if (isMyMessage) {
                switch (type) {
                    case "MAFIA":
                        bubbleColor = new Color(255, 150, 150);
                        break;
                    case "DEAD":
                        bubbleColor = new Color(220, 220, 220);
                        break;
                    default:
                        bubbleColor = new Color(200, 230, 255);
                        break;
                }
            } else {
                switch (type) {
                    case "MAFIA":
                        bubbleColor = new Color(255, 200, 200);
                        break;
                    case "DEAD":
                        bubbleColor = new Color(240, 240, 240);
                        break;
                    default:
                        bubbleColor = Color.WHITE;
                        break;
                }
            }
            g2.setColor(bubbleColor);

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

    class ChatMessagePanel extends JPanel {

        private static final ImageIcon Unknown_ICON = loadUnknownIcon();

        private static ImageIcon loadUnknownIcon() {
            try {
                java.net.URL imageUrl = GamePanel.class.getResource("/unknown.png");
                if (imageUrl != null) {
                    Image img = ImageIO.read(imageUrl).getScaledInstance(40, 40, Image.SCALE_SMOOTH);
                    return new ImageIcon(img);
                }
            } catch (IOException e) {
                System.err.println("기본 프로필 이미지 로드 실패: /unknown.png");
            }
            return new ImageIcon(new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB));
        }

        public ChatMessagePanel(String sender, String message, boolean isMyMessage, String type) {
            setLayout(new FlowLayout(isMyMessage ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));

            JPanel messageBubbleContainer = new JPanel();
            messageBubbleContainer.setOpaque(false);
            messageBubbleContainer.setLayout(new BoxLayout(messageBubbleContainer, BoxLayout.Y_AXIS));

            JLabel profileLabel = new JLabel(Unknown_ICON);
            profileLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
            profileLabel.setAlignmentY(Component.BOTTOM_ALIGNMENT);

            JLabel senderLabel = new JLabel(sender);
            senderLabel.setFont(new Font("맑은 고딕", Font.BOLD, 10));
            senderLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 2, 5));
            senderLabel.setAlignmentX(isMyMessage ? Component.RIGHT_ALIGNMENT : Component.LEFT_ALIGNMENT);

            if ("MAFIA".equals(type)) {
                senderLabel.setForeground(new Color(150, 0, 0));
            } else if ("DEAD".equals(type)) {
                senderLabel.setForeground(Color.GRAY);
            } else {
                senderLabel.setForeground(Color.BLACK);
            }

            BubblePanel bubblePanel = new BubblePanel(isMyMessage, type);

            JLabel messageLabel = new JLabel("<html>" + message + "</html>");
            messageLabel.setFont(new Font("맑은 고딕", Font.PLAIN, 10));
            messageLabel.setForeground(isMyMessage ? Color.BLACK : Color.DARK_GRAY);
            messageLabel.setOpaque(false);

            bubblePanel.add(messageLabel);
            messageBubbleContainer.add(senderLabel);
            messageBubbleContainer.add(bubblePanel);

            if (isMyMessage) {
                add(messageBubbleContainer);
                add(profileLabel);
            } else {
                add(profileLabel);
                add(messageBubbleContainer);
            }
        }

        @Override
        public float getAlignmentX() {
            FlowLayout layout = (FlowLayout) getLayout();
            if (layout.getAlignment() == FlowLayout.RIGHT) {
                return Component.RIGHT_ALIGNMENT;
            } else {
                return Component.LEFT_ALIGNMENT;
            }
        }

        @Override
        public Dimension getMaximumSize() {
            Container parent = getParent();
            if (parent != null && parent.getParent() != null) {
                int maxChatWidth = (int) (parent.getParent().getWidth() * 0.70);
                int preferredWidth = super.getPreferredSize().width;
                int finalWidth = Math.min(preferredWidth, maxChatWidth);

                return new Dimension(finalWidth, super.getPreferredSize().height);
            }
            return super.getPreferredSize();
        }
        @Override
        public Dimension getPreferredSize() {
            return super.getPreferredSize();
        }
    }
}