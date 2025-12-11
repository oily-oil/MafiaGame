import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * 개별 클라이언트와의 연결을 처리하는 핸들러.
 * - 소켓 입출력
 * - 클라이언트에서 온 명령 파싱
 * - GameServer 인스턴스에 게임 로직 위임
 */
public class ClientHandler implements Runnable {

    private final GameServer server;
    private final Socket socket;
    PrintWriter out;
    BufferedReader in;

    final int playerNumber;
    String name;
    Role role = Role.NONE;
    PlayerStatus status = PlayerStatus.ALIVE;
    boolean isHost = false;
    boolean isReady = false;

    public ClientHandler(GameServer server, Socket socket, int playerNumber) {
        this.server = server;
        this.socket = socket;
        this.playerNumber = playerNumber;
        this.name = "플레이어 " + this.playerNumber;
    }

    public void sendMessage(String message) {
        if (out != null) {
            out.println(message);
        }
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            // 서버에 접속 알림 (방장/준비 상태 결정 등)
            server.onClientConnected(this);

            // 자신의 플레이어 번호 전송
            sendMessage("PLAYER_NUM:" + this.playerNumber);

            // 기존 조사 결과가 있다면 새로 접속한 클라이언트에게도 전송
            server.sendInvestigatedRolesTo(this);

            String line;
            while ((line = in.readLine()) != null) {
                final String message = line.trim();
                if (message.isEmpty()) {
                    continue;
                }

                if (message.startsWith("TIMER:")) {
                    continue;
                }

                if (status == PlayerStatus.DEAD &&
                        !message.startsWith("/ready") &&
                        !message.startsWith("CHAT_DEAD:")) {
                    sendMessage("SYSTEM:당신은 죽었습니다. 채팅 외의 행동은 할 수 없습니다.");
                    continue;
                }

                GameServer.GamePhase phase = server.getCurrentPhase();

                if (message.equalsIgnoreCase("/start")) {
                    System.out.println("P" + playerNumber + "로부터 /start 명령 수신");
                    server.startGame(this);
                }
                else if (message.equalsIgnoreCase("/ready")) {
                    System.out.println("P" + playerNumber + "로부터 /ready 명령 수신");
                    server.handleReady(this);
                }
                else if (message.startsWith("/skill ")) {
                    if (phase != GameServer.GamePhase.NIGHT) {
                        sendMessage("SYSTEM:능력은 밤에만 사용할 수 없습니다.");
                        continue;
                    }
                    switch (role) {
                        case POLICE:
                            server.handleInvestigate(this, message);
                            break;
                        case DOCTOR:
                            server.handleSave(this, message);
                            break;
                        case MAFIA:
                            server.handleKillCommand(this, message);
                            break;
                        case CITIZEN:
                            sendMessage("SYSTEM:시민은 능력을 사용할 수 없습니다.");
                            break;
                        default:
                            sendMessage("SYSTEM:능력을 사용할 수 없는 역할입니다.");
                            break;
                    }
                }
                else if (message.startsWith("/vote ")) {
                    if (phase == GameServer.GamePhase.DAY) {
                        server.handleVote(this, message);
                    } else {
                        sendMessage("SYSTEM:투표는 낮에만 할 수 있습니다.");
                    }
                }
                else if (message.startsWith("CHAT:") ||
                        message.startsWith("CHAT_MAFIA:") ||
                        message.startsWith("CHAT_DEAD:")) {

                    synchronized (server) {
                        String content = message;
                        if (message.startsWith("CHAT_MAFIA:")) {
                            content = message.substring("CHAT_MAFIA:".length());
                        } else if (message.startsWith("CHAT_DEAD:")) {
                            content = message.substring("CHAT_DEAD:".length());
                        } else if (message.startsWith("CHAT:")) {
                            content = message.substring("CHAT:".length());
                        }

                        String chatMessage = content;

                        if (this.status == PlayerStatus.DEAD) {
                            System.out.println("[사망자 채팅] " + chatMessage);
                            server.broadcastToDeadExceptSender("CHAT_DEAD:" + chatMessage, this);
                        } else {
                            GameServer.GamePhase chatPhase = server.getCurrentPhase();
                            if (chatPhase == GameServer.GamePhase.DAY ||
                                    chatPhase == GameServer.GamePhase.WAITING) {
                                System.out.println("[" + chatPhase.name() + "] " + chatMessage);
                                server.broadcastExceptSenderToAll("CHAT:" + chatMessage, this);
                            } else if (chatPhase == GameServer.GamePhase.NIGHT) {
                                if (role == Role.MAFIA && status == PlayerStatus.ALIVE) {
                                    System.out.println("[밤-마피아] " + chatMessage);
                                    server.broadcastToMafiaExceptSender("CHAT_MAFIA:" + chatMessage, this);
                                } else {
                                    System.out.println("[밤-시민팀 생존자] 메시지 차단");
                                    sendMessage("SYSTEM:밤에는 마피아만 대화 가능합니다.");
                                }
                            }
                        }
                    }
                }
                else {
                    sendMessage("SYSTEM:알 수 없는 명령어입니다.");
                }
            }
        } catch (IOException e) {
            System.out.println("P" + playerNumber + "의 연결이 끊겼습니다 (IOException): " + e.getMessage());
        } catch (Exception e) {
            System.out.println("P" + playerNumber + " 처리 중 예상치 못한 오류 발생: " + e.getMessage());
            e.printStackTrace();
        } finally {
            server.onClientDisconnected(this);
            try {
                if (socket != null) socket.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
