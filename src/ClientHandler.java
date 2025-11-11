import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

// 이 스레드는 ClientHandler로 이름이 변경되어야 함
public class ClientHandler extends Thread {
    private final Socket clientSocket;
    private final MafiaServer server;
    private PrintWriter out;
    private BufferedReader in;

    private String nickname; // 클라이언트 닉네임
    private String currentRoomId; // 현재 참여하고 있는 방 ID (null이면 로비)

    public ClientHandler(Socket socket, MafiaServer server) {
        this.clientSocket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(clientSocket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

            // 1. 닉네임 설정 (가정)
            // 실제로는 닉네임을 클라이언트로부터 입력받는 로직이 필요합니다.
            this.nickname = "User_" + clientSocket.getPort();
            sendMessage("SERVER: 닉네임이 " + this.nickname + "으로 설정되었습니다.");

            // 2. 명령어 처리 루프
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                if (inputLine.equalsIgnoreCase("/quit")) {
                    break;
                }
                parseCommand(inputLine);
            }
        } catch (IOException e) {
            // 클라이언트 연결 끊김 (예외사항 처리: 사망으로 처리) [cite: 8]
            System.out.println("클라이언트 " + nickname + "가 연결을 종료했습니다. (사망 처리) [cite: 8]");
        } finally {
            cleanup();
        }
    }

    // 클라이언트로부터 받은 명령어를 분석하고 처리
    private void parseCommand(String command) {
        String[] parts = command.trim().split(" ", 2);
        String cmd = parts[0].toUpperCase();
        String args = parts.length > 1 ? parts[1] : "";

        if (cmd.equals("LIST")) {
            sendMessage(server.getRoomManager().getRoomList());
        } else if (cmd.equals("CREATE")) {
            // 예시: CREATE 방제목 8
            String[] createArgs = args.split(" ");
            if (createArgs.length >= 2) {
                String title = createArgs[0];
                int max = Integer.parseInt(createArgs[1]);
                String newId = server.getRoomManager().createRoom(title, max);
                // 방 생성 후 바로 입장
                server.getRoomManager().enterRoom(newId, nickname, this);
                this.currentRoomId = newId;
            } else {
                sendMessage("SERVER: 사용법: CREATE [제목] [최대인원]");
            }
        } else if (cmd.equals("ENTER")) {
            // 예시: ENTER ROOM_1
            if (currentRoomId != null) {
                sendMessage("SERVER: 이미 방에 입장해 있습니다. 먼저 /LEAVE 하세요.");
                return;
            }
            if (server.getRoomManager().enterRoom(args, nickname, this)) {
                this.currentRoomId = args;
            }
        } else if (cmd.equals("START")) {
            // 게임 시작 (처음 입장한 사람만 가능) [cite: 8]
            Room room = server.getRoomManager().getRoom(currentRoomId);
            // 권한 확인 로직 필요
            if (room != null) {
                room.startGame(nickname);
            }
        } else if (cmd.equals("CHAT")) {
            // 채팅 시스템 구현 (방 입장 시에만)
            if (currentRoomId != null) {
                Room room = server.getRoomManager().getRoom(currentRoomId);
                if (room != null) {
                    room.broadcast(nickname + ": " + args);
                }
            } else {
                sendMessage("SERVER: 채팅을 위해 방에 입장해주세요.");
            }
        }
        // * 능력 사용 (VOTE, KILL, SAVE, CHECK) 로직은 GameEngine과 연결되어야 함.
    }

    // 클라이언트에게 메시지를 전송
    public void sendMessage(String msg) {
        if (out != null) {
            out.println(msg);
        }
    }

    // 연결 종료 시 정리 작업
    private void cleanup() {
        if (currentRoomId != null) {
            server.getRoomManager().leaveRoom(currentRoomId, nickname);
        }
        server.removeClientHandler(this); // MafiaServer 목록에서 제거
        try {
            clientSocket.close();
        } catch (IOException e) {
            System.err.println("소켓 닫기 오류: " + e.getMessage());
        }
    }

    // Getter methods
    public String getNickname() { return nickname; }
    public String getCurrentRoomId() { return currentRoomId; }
}