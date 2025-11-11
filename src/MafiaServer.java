import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class MafiaServer {
    private static final int PORT = 12345;
    private final RoomManager roomManager;
    // 모든 활성 클라이언트 핸들러 목록
    private final List<ClientHandler> handlers;

    public MafiaServer() {
        this.roomManager = new RoomManager();
        this.handlers = new ArrayList<>();
    }

    public void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("마피아 게임 서버가 포트 " + PORT + "에서 시작되었습니다.");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("새로운 클라이언트 연결 수락: " + clientSocket.getInetAddress());

                ClientHandler handler = new ClientHandler(clientSocket, this);
                handlers.add(handler);
                handler.start();
            }
        } catch (IOException e) {
            System.err.println("서버 소켓 오류: " + e.getMessage());
        }
    }

    // ClientHandler가 연결 종료 시 호출하여 목록에서 제거
    public void removeClientHandler(ClientHandler handler) {
        handlers.remove(handler);
    }

    // Getter
    public RoomManager getRoomManager() {
        return roomManager;
    }

    public static void main(String[] args) {
        MafiaServer server = new MafiaServer();
        server.startServer();
    }
}