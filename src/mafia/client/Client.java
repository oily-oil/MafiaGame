package mafia.client;

import mafia.Enum.GamePhase;
import mafia.Enum.Role;
import mafia.server.protocol.Message;
import mafia.server.protocol.MessageCodec;
import mafia.Enum.MessageType;

import javax.swing.*;
import java.io.*;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;

public class Client {

    private String host;
    private int port;
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    private final ClientGameState gameState;
    private final ClientUI ui;

    public Client() {
        this.gameState = new ClientGameState();
        this.ui = new ClientUI(this, gameState);
    }

    public void connectToServer(String nickname, String host, int port) throws IOException {
        this.host = host;
        this.port = port;

        gameState.resetForNewConnection(nickname);

        try {
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

            // 서버에 닉네임 전달
            out.println("NICKNAME:" + nickname);
            gameState.setMyNickname(nickname);

            // 서버 수신 스레드 시작
            new Thread(this::listenForMessages, "mafia.client.Client-Receive-Thread").start();

            SwingUtilities.invokeLater(ui::showWaitingPanel);

        } catch (IOException e) {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    /**
     * 서버 메시지 수신 루프
     */
    private void listenForMessages() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                final Message msg = MessageCodec.parseServerToClient(line);
                SwingUtilities.invokeLater(() -> handleServerMessage(msg));
            }
        } catch (Exception e) {
            System.out.println("서버 수신 루프 종료: " + e.getMessage());
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(ui.getFrame(), "서버 연결이 끊겼습니다.");
                resetToLobby();
            });
        }
    }

    /**
     * 서버 → 클라이언트 메시지를 타입별로 처리
     */
    private void handleServerMessage(Message message) {
        MessageType type = message.getType();

        switch (type) {
            case PLAYER_NUM:
                handlePlayerNum(message.getPayload());
                break;

            case TIMER:
                handleTimer(message.getPayload());
                break;

            case PLAYERS_LIST:
                handlePlayersList(message.getPayload());
                break;

            case START_GAME:
                handleStartGame(message.getPayload());
                break;

            case ROLE:
                // 현재는 레거시 ROLE: 메시지를 사용하지 않음
                break;

            case YOU_DIED:
                handleYouDied();
                break;

            case GAME_OVER:
                handleGameOver(message.getPayload());
                break;

            case SYSTEM:
                handleSystemMessage(message.getPayload().trim());
                break;

            case CHAT:
            case CHAT_MAFIA:
            case CHAT_DEAD:
                handleChatMessage(message);
                break;

            case MARK_TARGET:
                handleMarkTarget(message.getPayload());
                break;

            case MARK_ROLE:
                handleMarkRole(message.getPayload());
                break;

            case UNKNOWN:
            default:
                handleGeneralMessage(message.getRaw());
                break;
        }
    }

    // ===== 개별 타입 핸들러 =====

    private void handlePlayerNum(String payload) {
        try {
            int num = Integer.parseInt(payload.trim());
            gameState.setMyPlayerNumber(num);
        } catch (NumberFormatException ignored) {}
    }

    private void handleTimer(String payload) {
        // "PHASE:SECONDS" 예: "DAY:48"
        String[] parts = payload.split(":");
        if (parts.length == 2) {
            String phaseStr = parts[0];
            GamePhase phase;
            try {
                phase = GamePhase.valueOf(phaseStr);
            } catch (IllegalArgumentException e) {
                phase = GamePhase.WAITING;
            }

            try {
                int secondsLeft = Integer.parseInt(parts[1]);
                gameState.setCurrentPhase(phase);
                ui.getGamePanel().updateTimer(phase, secondsLeft);
            } catch (NumberFormatException ignored) {}
        }
    }

    private void handlePlayersList(String payload) {
        List<String> players = payload.isEmpty()
                ? List.of()
                : Arrays.asList(payload.split(","));

        if (!gameState.isInGame()) {
            ui.getWaitingGamePanel().updatePlayerList(players);
        } else {
            ui.getGamePanel().updatePlayerList(players);
            ui.getGamePanel().updatePlayerMarks();
        }
    }

    private void handleStartGame(String payload) {
        gameState.setInGame(true);
        gameState.setMarkedPlayer("");
        gameState.getInvestigatedRoles().clear();
        ui.showGamePanel();
        ui.getGamePanel().appendChatMessage("시스템", "게임이 시작되었습니다.", false);
    }

    private void handleYouDied() {
        gameState.setAlive(false);
        ui.getGamePanel().appendChatMessage("시스템", "⚠ 당신은 사망했습니다. 관전자 모드로 전환됩니다.", false);
    }

    private void handleGameOver(String payload) {
        String content = payload.trim();
        ui.getGamePanel().appendChatMessage("시스템", "[게임 종료] " + content, false);
        JOptionPane.showMessageDialog(ui.getFrame(), "게임이 종료되었습니다: " + content);
        resetToLobby();
    }

    private void handleSystemMessage(String systemMsg) {
        // 방장 / 게스트 권한
        if (systemMsg.equals("HOST_GRANTED")) {
            gameState.setHost(true);
            gameState.setReady(true);
            ui.getWaitingGamePanel().updateButtons(true, true);
        } else if (systemMsg.equals("GUEST_GRANTED")) {
            gameState.setHost(false);
            gameState.setReady(false);
            ui.getWaitingGamePanel().updateButtons(false, false);
        }

        // 역할 배정 메시지
        if (systemMsg.startsWith("[역할] 당신은 '")) {
            int start = systemMsg.indexOf("'") + 1;
            int end = systemMsg.lastIndexOf("'");
            if (start > 0 && end > start) {
                String roleName = systemMsg.substring(start, end).toUpperCase();
                Role role;
                try {
                    role = Role.valueOf(roleName);
                } catch (IllegalArgumentException e) {
                    role = Role.NONE;
                }
                gameState.setMyRole(role);
                ui.getGamePanel().updateMyRoleDisplay(role);
            }
        }

        if (!gameState.isInGame()) {
            ui.getWaitingGamePanel().appendChatMessage(systemMsg);
        } else {
            ui.getGamePanel().appendChatMessage("시스템", systemMsg, false);
            ui.getGamePanel().updatePlayerMarks();
        }
    }

    private void handleChatMessage(Message message) {
        String content = message.getPayload();
        int colonIndex = content.indexOf(':');

        if (colonIndex <= 0) {
            handleGeneralMessage(message.getRaw());
            return;
        }

        String sender = content.substring(0, colonIndex).trim();
        String msgText = content.substring(colonIndex + 1).trim();

        boolean isMyMessage = sender.equals(gameState.getMyNickname());

        String chatType;
        if (message.getType() == MessageType.CHAT_MAFIA) {
            chatType = "MAFIA";
        } else if (message.getType() == MessageType.CHAT_DEAD) {
            chatType = "DEAD";
        } else {
            chatType = "NORMAL";
        }

        if (!gameState.isInGame()) {
            ui.getWaitingGamePanel().appendChatMessage(msgText);
        } else {
            ui.getGamePanel().appendChatMessage(sender, msgText, isMyMessage, chatType);
        }
    }

    private void handleMarkTarget(String payload) {
        String target = payload.trim(); // 예: "P2"
        gameState.setMarkedPlayer(target);
        ui.getGamePanel().updatePlayerMarks();
    }

    private void handleMarkRole(String payload) {
        if (gameState.getMyRole() != Role.POLICE) {
            return;
        }

        String[] parts = payload.split(":");
        if (parts.length == 2) {
            gameState.getInvestigatedRoles().put(parts[0], parts[1]);
            ui.getGamePanel().updatePlayerMarks();
        }
    }

    private void handleGeneralMessage(String msg) {
        if (!gameState.isInGame()) {
            ui.getWaitingGamePanel().appendChatMessage(msg);
        } else {
            ui.getGamePanel().appendChatMessage("시스템", msg, false);
        }
    }

    // ===== 버튼/명령 처리 =====

    public void handleReadyClick() {
        if (!gameState.isHost()) {
            sendMessage("/ready");
            gameState.setReady(!gameState.isReady());
            ui.getWaitingGamePanel().updateButtons(gameState.isHost(), gameState.isReady());
        } else {
            System.out.println("방장은 준비 상태를 변경할 수 없습니다.");
        }
    }

    public void handleStartClick() {
        if (gameState.isHost()) {
            sendMessage("/start");
        } else {
            System.out.println("방장만 게임을 시작할 수 있습니다.");
        }
    }

    public void sendMessage(String msg) {
        if (out == null) return;
        if (msg == null) return;
        msg = msg.trim();
        if (msg.isEmpty()) return;

        // 슬래시로 시작하면 서버 명령
        if (msg.startsWith("/")) {
            out.println(msg);
            return;
        }

        String chatPrefix;
        if (!gameState.isAlive()) {
            chatPrefix = "CHAT_DEAD:";
        } else if (gameState.isInGame() && gameState.getCurrentPhase() == GamePhase.NIGHT) {
            if (gameState.getMyRole() == Role.MAFIA) {
                chatPrefix = "CHAT_MAFIA:";
            } else {
                ui.getGamePanel().appendChatMessage("시스템", "경고: 밤에는 마피아만 대화 가능합니다.", false);
                return;
            }
        } else {
            chatPrefix = "CHAT:";
        }

        String fullMessage = chatPrefix + gameState.getMyNickname() + ":" + msg;
        out.println(fullMessage);

        String localType;
        if (chatPrefix.equals("CHAT_DEAD:")) {
            localType = "DEAD";
        } else if (chatPrefix.equals("CHAT_MAFIA:")) {
            localType = "MAFIA";
        } else {
            localType = "NORMAL";
        }

        if (!gameState.isInGame()) {
            ui.getWaitingGamePanel().appendChatMessage(msg);
        } else {
            ui.getGamePanel().appendChatMessage(gameState.getMyNickname(), msg, true, localType);
        }
    }

    // ===== 로비로 리셋 =====

    private void resetToLobby() {
        boolean wasHost = gameState.isHost();
        gameState.resetForLobbyAfterGame(wasHost);

        SwingUtilities.invokeLater(() -> {
            ui.getGamePanel().clearGameState();
            ui.getGamePanel().updateMyRoleDisplay(Role.NONE);

            ui.showWaitingPanel();
            ui.getWaitingGamePanel().clearDisplay();
            ui.getWaitingGamePanel().updateButtons(wasHost, gameState.isReady());
        });
    }

    // ===== 유틸: "P2 - 닉네임"에서 숫자 추출 =====

    public String extractPlayerNumber(String playerString) {
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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Client::new);
    }
}
