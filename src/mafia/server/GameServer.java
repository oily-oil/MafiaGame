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
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GameServer: 여러 개의 GameRoom을 관리하는 메인 서버.
 *  - 클라이언트 접속/해제 관리
 *  - 각 클라이언트를 기본 방(Lobby)에 배치
 *  - 방 생성/입장/목록 관련 메서드를 제공 (PlayerSession에서 /room 명령으로 사용)
 *
 * 실제 게임 규칙(낮/밤, 투표, 능력 등)은 GameRoom 이 담당.
 *
 * main() 을 포함하고 있어, 이 클래스만 실행해도
 * 서버 GUI(ServerGUI)를 띄우고 서버를 시작할 수 있다.
 */
public class GameServer {

    // 현재 서버에서 운영 중인 방 목록
    private final Map<String, GameRoom> rooms = new LinkedHashMap<>();

    // 접속 중인 플레이어 세션 목록
    private final Set<PlayerSession> sessions =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // 클라이언트 처리용 스레드 풀
    private final ExecutorService clientPool = Executors.newCachedThreadPool();

    // P1, P2, ... 부여용 번호 카운터
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

                    int playerNumber = playerCounter.getAndIncrement();
                    // PlayerSession은 Runnable 이며, 소켓을 직접 처리
                    PlayerSession session = new PlayerSession(this, socket, playerNumber);
                    sessions.add(session);

                    // 기본 방(Lobby)에 자동 입장
                    GameRoom lobby = getOrCreateRoom(DEFAULT_ROOM_NAME);
                    session.setCurrentRoom(lobby);
                    lobby.addPlayer(session);

                    // 클라이언트에게 "지금은 Lobby에 있다"는 정보 전달
                    session.send("SYSTEM:[방이동] '" + DEFAULT_ROOM_NAME + "' 방에 입장했습니다.");

                    // 방 목록 전체를 모든 세션에 브로드캐스트
                    broadcastRoomListToAll();

                    // 세션 스레드 시작
                    clientPool.execute(session);
                } catch (IOException e) {
                    if (running) {
                        System.err.println("[SERVER] Accept error: " + e.getMessage());
                    }
                }
            }
        }, "Accept-Thread");

        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    // ============================================================
    // 방 관리 메서드들 (PlayerSession / GameRoom 에서 사용)
    // ============================================================

    /**
     * 방 가져오기 또는 생성 (기본 방 등에서 사용)
     */
    public synchronized GameRoom getOrCreateRoom(String roomName) {
        GameRoom room = rooms.get(roomName);
        if (room == null) {
            room = new GameRoom(this, roomName);
            rooms.put(roomName, room);
            System.out.println("[SERVER] 방 생성: " + roomName);
        }
        return room;
    }

    /**
     * 명시적으로 새 방을 만들 때 사용 (/room create ...)
     * 이미 존재하면 null 반환
     */
    public synchronized GameRoom createRoom(String roomName) {
        if (rooms.containsKey(roomName)) {
            return null;
        }
        GameRoom room = new GameRoom(this, roomName);
        rooms.put(roomName, room);
        System.out.println("[SERVER] 방 생성: " + roomName);
        return room;
    }

    /**
     * 방 이름으로 GameRoom 조회 (없으면 null)
     */
    public synchronized GameRoom getRoom(String roomName) {
        return rooms.get(roomName);
    }

    /**
     * 방 목록 정보:
     *  - "방이름 (인원수명)" 문자열 목록 반환
     */
    public synchronized List<String> getRoomInfoList() {
        List<String> result = new ArrayList<>();
        for (GameRoom room : rooms.values()) {
            result.add(room.getRoomName() + " (" + room.getPlayerCount() + "명)");
        }
        return result;
    }

    /**
     * 특정 플레이어에게만 방 목록을 전송
     *  → 클라이언트는 SYSTEM 메시지의 text가 "[ROOM_LIST] ..." 로 시작하는 것을 파싱함
     */
    public void sendRoomListTo(PlayerSession session) {
        List<String> infos = getRoomInfoList();
        String payload = String.join(",", infos);
        session.send("SYSTEM:[ROOM_LIST] " + payload);
    }

    /**
     * 현재 모든 방 목록을 모든 세션에 브로드캐스트
     *  → 새로운 방이 생성/제거/인원수 변경될 때 호출
     */
    public void broadcastRoomListToAll() {
        List<String> infos = getRoomInfoList();
        String payload = String.join(",", infos);
        String msg = "SYSTEM:[ROOM_LIST] " + payload;

        for (PlayerSession s : sessions) {
            s.send(msg);
        }
    }

    /**
     * 방에서 플레이어가 나간 뒤, 방이 비었을 경우 GameRoom 쪽에서 호출
     */
    public synchronized void removeRoomIfEmpty(GameRoom room) {
        if (room.getPlayerCount() == 0) {
            rooms.remove(room.getRoomName());
            System.out.println("[SERVER] 방 제거: " + room.getRoomName());
            // 방이 사라졌으므로 전체에 갱신된 방 목록 브로드캐스트
            broadcastRoomListToAll();
        }
    }

    /**
     * PlayerSession 정리용 (세션 종료 시 PlayerSession.run()의 finally 블록에서 호출)
     */
    public void removeSession(PlayerSession session) {
        sessions.remove(session);
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
