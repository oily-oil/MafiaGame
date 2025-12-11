package mafia.server;

import mafia.server.room.GameRoom;
import mafia.server.room.PlayerSession;
import mafia.server.ui.ServerGUI;

import javax.swing.SwingUtilities;
import javax.swing.JOptionPane;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GameServer: 여러 개의 GameRoom을 관리하는 메인 서버.
 *  - 클라이언트 접속/해제 관리
 *  - 각 클라이언트를 방에 배치 (기본: Lobby)
 *  - /rooms, /join 명령 처리
 *
 * 실제 게임 규칙(낮/밤, 투표, 능력 등)은 GameRoom 이 담당.
 *
 * main() 을 포함하고 있어, 이 클래스만 실행해도
 * 서버 GUI(ServerGUI)를 띄우고 서버를 시작할 수 있다.
 */
public class GameServer {

    private final Map<String, GameRoom> rooms = new LinkedHashMap<>();
    private final ExecutorService clientPool = Executors.newCachedThreadPool();
    private final AtomicInteger playerCounter = new AtomicInteger(1);

    private ServerSocket serverSocket;
    private volatile boolean running = false;

    // 기본 방 이름 (처음 접속 시 자동 배치)
    public static final String DEFAULT_ROOM_NAME = "Lobby";

    /**
     * 서버 시작 (GUI에서 포트를 받아 호출)
     */
    public void start(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;

        System.out.println("[SERVER] GameServer started on port " + port);

        Thread acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    ClientHandler handler = new ClientHandler(this, socket);
                    clientPool.execute(handler);
                } catch (IOException e) {
                    if (running) {
                        System.err.println("[SERVER] Accept error: " + e.getMessage());
                    }
                }
            }
        }, "Accept-Thread");

        acceptThread.start();
    }

    /**
     * 새로운 클라이언트 등록 (처음 접속 시 기본 방 Lobby 로 배치)
     */
    public synchronized PlayerSession registerNewClient(ClientHandler handler, String nickname) {
        int playerNumber = playerCounter.getAndIncrement();
        GameRoom room = getOrCreateRoom(DEFAULT_ROOM_NAME);
        PlayerSession session = new PlayerSession(handler, playerNumber, nickname);

        room.addPlayer(session);
        handler.setRoomAndSession(room, session);

        return session;
    }

    /**
     * /rooms 명령 처리: 호출한 클라이언트에게 방 목록을 SYSTEM 메시지로 전송
     */
    public synchronized void sendRoomListTo(ClientHandler handler) {
        if (rooms.isEmpty()) {
            handler.sendMessage("SYSTEM:[방목록] 현재 생성된 방이 없습니다.");
            return;
        }

        handler.sendMessage("SYSTEM:[방목록] 현재 " + rooms.size() + "개 방이 있습니다.");

        for (GameRoom room : rooms.values()) {
            String info = String.format(
                    "[방] %s - %d명 (%s)",
                    room.getRoomName(),
                    room.getPlayerCount(),
                    room.getCurrentPhase().name()
            );
            handler.sendMessage("SYSTEM:" + info);
        }
    }

    /**
     * /join roomName 명령 처리
     *  - 없으면 새로 생성
     *  - 기존 방에서 탈퇴 후 새 방으로 이동
     */
    public synchronized void joinRoom(ClientHandler handler, String roomName) {
        roomName = roomName.trim();
        if (roomName.isEmpty()) {
            handler.sendMessage("SYSTEM:방 이름이 올바르지 않습니다. 예: /join Room1");
            return;
        }

        GameRoom currentRoom = handler.getRoom();
        PlayerSession session = handler.getSession();

        if (session == null) {
            handler.sendMessage("SYSTEM:아직 서버에 등록되지 않았습니다. 잠시 후 다시 시도해주세요.");
            return;
        }

        if (currentRoom != null && currentRoom.getRoomName().equals(roomName)) {
            handler.sendMessage("SYSTEM:이미 '" + roomName + "' 방에 있습니다.");
            return;
        }

        // 기존 방에서 제거
        if (currentRoom != null) {
            currentRoom.removePlayer(session);
            // 방이 비면 제거
            if (currentRoom.getPlayerCount() == 0) {
                rooms.remove(currentRoom.getRoomName());
                System.out.println("[SERVER] 방 제거: " + currentRoom.getRoomName());
            }
        }

        // 새 방에 추가
        GameRoom targetRoom = getOrCreateRoom(roomName);
        targetRoom.addPlayer(session);
        handler.setRoomAndSession(targetRoom, session);

        handler.sendMessage("SYSTEM:[방이동] '" + roomName + "' 방에 입장했습니다.");
    }

    /**
     * 클라이언트 연결 해제 처리
     */
    public synchronized void onClientDisconnected(ClientHandler handler) {
        GameRoom room = handler.getRoom();
        PlayerSession session = handler.getSession();

        if (room != null && session != null) {
            room.removePlayer(session);

            if (room.getPlayerCount() == 0) {
                rooms.remove(room.getRoomName());
                System.out.println("[SERVER] 방 제거: " + room.getRoomName());
            }
        }
    }

    /**
     * 방이 비었을 때 GameRoom 에서 호출 가능 (현재는 onClientDisconnected 에서 처리)
     */
    public synchronized void onRoomEmpty(GameRoom room) {
        if (room.getPlayerCount() == 0) {
            rooms.remove(room.getRoomName());
            System.out.println("[SERVER] 방 제거: " + room.getRoomName());
        }
    }

    /**
     * 방 가져오기 또는 생성
     */
    private synchronized GameRoom getOrCreateRoom(String roomName) {
        GameRoom room = rooms.get(roomName);
        if (room == null) {
            room = new GameRoom(this, roomName);
            rooms.put(roomName, room);
            System.out.println("[SERVER] 새 방 생성: " + roomName);
        }
        return room;
    }

    // ============================================================
    // main: GameServer 를 직접 실행할 수 있는 진입점
    // ============================================================

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ServerGUI serverGUI = new ServerGUI();
            GameServer gameServer = new GameServer();

            serverGUI.getStartButton().addActionListener(e -> {
                try {
                    int port = serverGUI.getPortNumber();
                    gameServer.start(port);
                    serverGUI.getStartButton().setEnabled(false);
                    serverGUI.setTitle("Mafia Game Server (Running on Port " + port + ")");
                } catch (IOException ex) {
                    System.err.println("서버 시작 실패: " + ex.getMessage());
                    serverGUI.getStartButton().setEnabled(true);
                    JOptionPane.showMessageDialog(
                            serverGUI,
                            "서버 시작 실패: " + ex.getMessage(),
                            "오류",
                            JOptionPane.ERROR_MESSAGE
                    );
                }
            });
        });
    }
}
