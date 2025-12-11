import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * 한 클라이언트 소켓을 담당하는 네트워크 레이어.
 * 문자열 프로토콜을 파싱해서 GameRoom 도메인에 위임.
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final GameServer server;
    private PrintWriter out;
    private BufferedReader in;

    private PlayerSession session;

    public ClientHandler(Socket socket, GameServer server) {
        this.socket = socket;
        this.server = server;
    }

    public PlayerSession getSession() {
        return session;
    }

    public void setSession(PlayerSession session) {
        this.session = session;
    }

    public void sendMessage(String message) {
        if (out != null) {
            out.println(message);
        }
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new PrintWriter(socket.getOutputStream(), true);

            GameRoom room = server.getGameRoom();

            // 1) 첫 메시지로 닉네임 수신 (Client에서 NICKNAME:xxx 를 먼저 보냄)
            String firstLine = in.readLine();
            String nickname = null;
            if (firstLine != null && firstLine.startsWith("NICKNAME:")) {
                nickname = firstLine.substring("NICKNAME:".length()).trim();
            } else if (firstLine != null && !firstLine.isBlank()) {
                // 혹시 다른 형식이 오면, 일단 닉네임 없이 그냥 진행하고
                // 첫 메시지는 아래 루프에서 다시 처리할 수는 없으므로 그냥 로그만 남김
                System.out.println("경고: 첫 메시지가 NICKNAME: 형식이 아닙니다. message=" + firstLine);
            }

            // 2) PlayerSession 생성 및 GameRoom 등록
            session = room.addPlayer(this, nickname);

            // 3) 메인 메시지 루프
            String line;
            while ((line = in.readLine()) != null) {
                String msg = line.trim();
                if (msg.isEmpty()) continue;

                // TIMER 메시지는 클라 → 서버로 올라오지 않지만, 혹시 모를 경우 무시
                if (msg.startsWith("TIMER:")) {
                    continue;
                }

                // 죽은 플레이어 제한
                if (session.getStatus() == PlayerStatus.DEAD
                        && !msg.startsWith("/ready")
                        && !msg.startsWith("CHAT_DEAD:")) {
                    session.send("SYSTEM:당신은 죽었습니다. 채팅 외의 행동은 할 수 없습니다.");
                    continue;
                }

                // 명령 처리
                if (msg.equalsIgnoreCase("/start")) {
                    System.out.println("P" + session.getPlayerNumber() + "로부터 /start 명령 수신");
                    room.handleStartGame(session);
                } else if (msg.equalsIgnoreCase("/ready")) {
                    System.out.println("P" + session.getPlayerNumber() + "로부터 /ready 명령 수신");
                    room.handleReady(session);
                } else if (msg.startsWith("/vote ")) {
                    String arg = msg.substring(6).trim();
                    room.handleVote(session, arg);
                } else if (msg.startsWith("/skill ")) {
                    String arg = msg.substring(7).trim();
                    room.handleSkill(session, arg);
                }

                // 채팅 처리
                else if (msg.startsWith("CHAT:") || msg.startsWith("CHAT_MAFIA:") || msg.startsWith("CHAT_DEAD:")) {
                    room.handleChat(session, msg);
                }

                // 그 외
                else {
                    session.send("SYSTEM:알 수 없는 명령어입니다.");
                }
            }
        } catch (IOException e) {
            System.out.println("P" + (session != null ? session.getPlayerNumber() : -1) + "의 연결이 끊겼습니다 (IOException): " + e.getMessage());
        } catch (Exception e) {
            System.out.println("P" + (session != null ? session.getPlayerNumber() : -1) + " 처리 중 예상치 못한 오류 발생: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (socket != null) socket.close();
            } catch (IOException ignore) {}

            GameRoom room = server.getGameRoom();
            room.handleDisconnect(session);
        }
    }
}
