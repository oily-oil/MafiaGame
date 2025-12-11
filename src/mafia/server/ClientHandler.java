package mafia.server;

import mafia.Enum.GamePhase;
import mafia.Enum.MessageType;
import mafia.Enum.PlayerStatus;
import mafia.Enum.Role;
import mafia.server.protocol.Message;
import mafia.server.protocol.MessageCodec;
import mafia.server.room.GameRoom;
import mafia.server.room.PlayerSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * 클라이언트별 소켓 연결 담당.
 *  - 접속 직후 NICKNAME: 을 받고 GameServer에 등록
 *  - 이후 들어오는 모든 문자열을 MessageCodec.parseClientToServer 로 해석
 *  - 해석된 MessageType 에 따라 GameRoom / GameServer 로 위임
 */
public class ClientHandler implements Runnable {

    private final GameServer server;
    private final Socket socket;

    private PrintWriter out;
    private BufferedReader in;

    private PlayerSession session;
    private GameRoom room;

    public ClientHandler(GameServer server, Socket socket) {
        this.server = server;
        this.socket = socket;
    }

    public void setRoomAndSession(GameRoom room, PlayerSession session) {
        this.room = room;
        this.session = session;
    }

    public GameRoom getRoom() {
        return room;
    }

    public PlayerSession getSession() {
        return session;
    }

    public void sendMessage(String message) {
        if (out != null) {
            out.println(message);
        }
    }

    @Override
    public void run() {
        try {
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new PrintWriter(socket.getOutputStream(), true);

            // 1) 최초 닉네임 수신
            String firstLine = in.readLine();
            if (firstLine == null || !firstLine.startsWith("NICKNAME:")) {
                sendMessage("SYSTEM:잘못된 접속입니다. (닉네임 없음)");
                return;
            }
            String nickname = firstLine.substring("NICKNAME:".length()).trim();
            if (nickname.isEmpty()) {
                nickname = "플레이어";
            }

            // 2) 서버에 등록 + 기본 방(Lobby)에 입장
            session = server.registerNewClient(this, nickname);
            room = getRoom();

            // 3) 메시지 루프
            String line;
            while ((line = in.readLine()) != null) {
                final String raw = line;
                Message msg = MessageCodec.parseClientToServer(raw);
                MessageType type = msg.getType();

                if (type == MessageType.UNKNOWN && raw.trim().isEmpty()) {
                    continue; // 빈 줄 무시
                }

                // 사망자는 /ready, 사망자 채팅만 허용
                if (session.getStatus() == PlayerStatus.DEAD &&
                        type != MessageType.CMD_READY &&
                        type != MessageType.CHAT_DEAD) {
                    sendMessage("SYSTEM:당신은 죽었습니다. 채팅(사망자 채팅) 외의 행동은 할 수 없습니다.");
                    continue;
                }

                // ==== 방/서버 전역 명령 ====
                if (type == MessageType.CMD_ROOMS) {
                    server.sendRoomListTo(this);
                    continue;
                }
                if (type == MessageType.CMD_JOIN) {
                    String roomName = msg.getRoomName();
                    server.joinRoom(this, roomName != null ? roomName : "");
                    continue;
                }

                // ==== 이 아래부터는 반드시 방이 있어야 함 ====
                if (room == null) {
                    sendMessage("SYSTEM:어느 방에도 속해있지 않습니다. /join 명령으로 방에 입장하세요.");
                    continue;
                }

                switch (type) {
                    case CMD_READY:
                        room.handleReady(session);
                        break;

                    case CMD_START:
                        room.startGame(session);
                        break;

                    case CMD_VOTE:
                        if (room.getCurrentPhase() == GamePhase.DAY) {
                            // GameRoom은 원래 /vote 2 형식의 원본 문자열을 기대하므로 raw 그대로 넘김
                            room.handleVote(session, msg.getRaw());
                        } else {
                            sendMessage("SYSTEM:투표는 낮에만 할 수 있습니다.");
                        }
                        break;

                    case CMD_SKILL:
                        if (room.getCurrentPhase() != GamePhase.NIGHT) {
                            sendMessage("SYSTEM:능력은 밤에만 사용할 수 있습니다.");
                            break;
                        }
                        Role role = session.getRole();
                        switch (role) {
                            case POLICE:
                                room.handleInvestigate(session, msg.getRaw());
                                break;
                            case DOCTOR:
                                room.handleSave(session, msg.getRaw());
                                break;
                            case MAFIA:
                                room.handleKillCommand(session, msg.getRaw());
                                break;
                            case CITIZEN:
                                sendMessage("SYSTEM:시민은 능력을 사용할 수 없습니다.");
                                break;
                            default:
                                sendMessage("SYSTEM:능력을 사용할 수 없는 상태입니다.");
                                break;
                        }
                        break;

                    case CHAT:
                    case CHAT_MAFIA:
                    case CHAT_DEAD:
                        room.handleChat(session, msg.getRaw());
                        break;

                    case UNKNOWN:
                    default:
                        sendMessage("SYSTEM:알 수 없는 명령어입니다.");
                        break;
                }
            }

        } catch (IOException e) {
            System.out.println("[SERVER] 클라이언트 연결 종료 (IOException): " + e.getMessage());
        } catch (Exception e) {
            System.out.println("[SERVER] 클라이언트 처리 중 오류: " + e.getMessage());
            e.printStackTrace();
        } finally {
            server.onClientDisconnected(this);
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {}
        }
    }
}
