package mafia.client.ui;

import mafia.client.Client;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class WaitingGamePanel extends JPanel {

    private final Client client;

    // 로비/채팅 표시 영역
    private JTextArea displayArea;

    // 기존 버튼들
    private JButton startGameButton;
    private JButton readyButton;

    // 방 목록 관련 UI
    private JPanel roomListPanel;          // 방 버튼들이 들어갈 패널
    private JScrollPane roomListScroll;    // 방 목록 스크롤
    private JTextField roomNameField;      // 새 방 이름 입력
    private JButton createRoomButton;      // 방 생성/입장 버튼

    // 방 목록 자동 갱신 타이머
    private Timer roomListTimer;

    public WaitingGamePanel(Client client) {
        this.client = client;

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // ===== 상단 타이틀 =====
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("로비 상태 및 채팅 / 방 목록:"));
        add(topPanel, BorderLayout.NORTH);

        // ===== 중앙: 왼쪽 채팅 + 오른쪽 방 목록 =====
        // 왼쪽: 채팅 / 플레이어 목록 텍스트
        displayArea = new JTextArea("서버에 연결하세요...");
        displayArea.setEditable(false);
        JScrollPane chatScrollPane = new JScrollPane(displayArea);

        // 오른쪽: 방 목록
        roomListPanel = new JPanel();
        roomListPanel.setLayout(new BoxLayout(roomListPanel, BoxLayout.Y_AXIS));
        roomListPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        roomListScroll = new JScrollPane(roomListPanel);
        roomListScroll.setPreferredSize(new Dimension(200, 0));

        JPanel centerPanel = new JPanel(new BorderLayout(10, 0));
        centerPanel.add(chatScrollPane, BorderLayout.CENTER);

        // 방 목록 상단 라벨
        JPanel roomListHeader = new JPanel(new BorderLayout());
        JLabel roomListLabel = new JLabel("방 목록", SwingConstants.CENTER);
        roomListHeader.add(roomListLabel, BorderLayout.NORTH);
        roomListHeader.add(roomListScroll, BorderLayout.CENTER);

        centerPanel.add(roomListHeader, BorderLayout.EAST);

        add(centerPanel, BorderLayout.CENTER);

        // ===== 하단: 준비/시작 + 방 생성 =====
        JPanel bottomPanel = new JPanel(new BorderLayout());
        add(bottomPanel, BorderLayout.SOUTH);

        // (1) 준비 / 시작 버튼 영역
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));

        startGameButton = new JButton("게임 시작 (4명 이상)");
        startGameButton.setVisible(false);
        startGameButton.addActionListener(e -> client.handleStartClick());
        controlPanel.add(startGameButton);

        readyButton = new JButton("준비");
        readyButton.setVisible(false);
        readyButton.addActionListener(e -> client.handleReadyClick());
        controlPanel.add(readyButton);

        bottomPanel.add(controlPanel, BorderLayout.NORTH);

        // (2) 방 생성 / 입장 영역
        JPanel roomCreatePanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        roomCreatePanel.add(new JLabel("방 이름:"));

        roomNameField = new JTextField(10);
        roomCreatePanel.add(roomNameField);

        createRoomButton = new JButton("방 입장");
        createRoomButton.addActionListener(e -> {
            String name = roomNameField.getText().trim();
            if (!name.isEmpty()) {
                client.joinRoom(name);
            }
        });
        roomCreatePanel.add(createRoomButton);
        createRoomButton = new JButton("방 생성");
        createRoomButton.addActionListener(e -> {
            String name = roomNameField.getText().trim();
            if (!name.isEmpty()) {
                client.createRoom(name);
            }
        });
        roomCreatePanel.add(createRoomButton);

        bottomPanel.add(roomCreatePanel, BorderLayout.SOUTH);

        // ===== 방 목록 자동 갱신 타이머 =====
        roomListTimer = new Timer(3000, e -> {
            // 이 패널이 현재 화면에 표시되고 있을 때만 요청
            if (isShowing()) {
                client.requestRoomList();
            }
        });
        roomListTimer.start();
    }

    // ===================== 플레이어 목록 / 채팅 =====================

    public void updatePlayerList(List<String> players) {
        if (players == null || players.isEmpty()) {
            displayArea.append("\n--- 참가자 (0명) ---\n참가자가 없습니다.\n");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("\n--- 참가자 (").append(players.size()).append("명) ---\n");
            for (String p : players) {
                sb.append(p).append("\n");
            }
            displayArea.append(sb.toString());
        }
        displayArea.setCaretPosition(displayArea.getDocument().getLength());
    }

    public void appendChatMessage(String message) {
        displayArea.append(message + "\n");
        displayArea.setCaretPosition(displayArea.getDocument().getLength());
    }

    /**
     * 버튼 상태 업데이트
     *
     * @param isHost  방장 여부
     * @param isReady 준비 여부
     * @param isLobby 현재 방이 Lobby 인지 여부
     */
    public void updateButtons(boolean isHost, boolean isReady, boolean isLobby) {
        if (isLobby) {
            // 로비에서는 게임 시작 버튼 숨김
            startGameButton.setVisible(false);

            // 방장은 항상 준비 상태, 버튼 비활성화
            if (isHost) {
                readyButton.setVisible(true);
                readyButton.setText("로비 (방장)");
                readyButton.setEnabled(false);
            } else {
                readyButton.setVisible(true);
                readyButton.setText(isReady ? "준비 취소" : "준비");
                readyButton.setEnabled(true);
            }
        } else {
            // 일반 방에서는 기존 로직 유지
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
        }

        revalidate();
        repaint();
    }

    public void clearDisplay() {
        displayArea.setText("--- 게임이 종료되었습니다. 로비 상태로 돌아왔습니다. ---\n");
        displayArea.setCaretPosition(displayArea.getDocument().getLength());
    }

    // ===================== 방 목록 UI =====================

    /**
     * 서버에서 받은 방 목록 문자열 리스트를 이용해
     * 오른쪽 방 버튼 목록을 갱신한다.
     *
     * 예: items = ["Lobby (2명)", "Room1 (3명)"]
     */
    public void updateRoomList(List<String> items) {
        roomListPanel.removeAll();

        if (items == null || items.isEmpty()) {
            JLabel noRoomLabel = new JLabel("방이 없습니다.", SwingConstants.CENTER);
            noRoomLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            roomListPanel.add(noRoomLabel);
        } else {
            for (String info : items) {
                String displayText = info.trim();
                if (displayText.isEmpty()) continue;

                // 방 이름은 "이름 (인원)" 형태에서 앞부분만 사용
                String roomName = displayText;
                int idx = displayText.indexOf(" (");
                if (idx > 0) {
                    roomName = displayText.substring(0, idx);
                }

                JButton roomButton = new JButton(displayText);
                roomButton.setAlignmentX(Component.CENTER_ALIGNMENT);
                roomButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

                final String finalRoomName = roomName;
                roomButton.addActionListener(e -> client.joinRoom(finalRoomName));

                roomListPanel.add(roomButton);
                roomListPanel.add(Box.createVerticalStrut(5));
            }
        }

        roomListPanel.revalidate();
        roomListPanel.repaint();
    }
}
