package mafia.server;

import mafia.server.room.GameRoom;
import mafia.server.ui.ServerGUI;

import javax.swing.*;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 서버 진입 클래스.
 * - mafia.server.ui.ServerGUI 와 연결되어 포트 입력 후 서버 시작
 * - 클라이언트 소켓 accept
 * - mafia.server.room.GameRoom 에서 실제 게임 규칙/진행 관리
 */
public class GameServer {

    private ServerSocket serverSocket;
    private final ExecutorService clientPool = Executors.newFixedThreadPool(20);
    private ScheduledExecutorService tickScheduler;

    private final GameRoom gameRoom = new GameRoom();

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ServerGUI gui = new ServerGUI();
            GameServer server = new GameServer();

            gui.getStartButton().addActionListener(e -> {
                try {
                    int port = gui.getPortNumber();
                    server.start(port);
                    gui.getStartButton().setEnabled(false);
                    gui.setTitle("Mafia Game Server (Running on Port " + port + ")");
                } catch (IOException ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(
                            gui,
                            "서버 시작 실패: " + ex.getMessage(),
                            "오류",
                            JOptionPane.ERROR_MESSAGE
                    );
                }
            });
        });
    }

    public GameRoom getGameRoom() {
        return gameRoom;
    }

    public void start(int port) throws IOException {
        System.out.println("게임 서버가 시작되었습니다. (Port: " + port + ")");
        serverSocket = new ServerSocket(port);

        startAcceptLoop();
        startTickScheduler();
    }

    private void startAcceptLoop() {
        Thread t = new Thread(() -> {
            try {
                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    ClientHandler handler = new ClientHandler(socket, this);
                    clientPool.execute(handler);
                }
            } catch (IOException e) {
                System.err.println("서버 리스너 오류: " + e.getMessage());
            }
        }, "Server-Accept-Thread");

        t.setDaemon(true);
        t.start();
    }

    private void startTickScheduler() {
        tickScheduler = Executors.newSingleThreadScheduledExecutor();
        tickScheduler.scheduleAtFixedRate(() -> {
            try {
                gameRoom.tickOneSecond();
            } catch (Exception e) {
                System.err.println("tickOneSecond 오류: " + e.getMessage());
                e.printStackTrace();
            }
        }, 0, 1, TimeUnit.SECONDS);
    }
}
