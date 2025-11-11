import java.io.IOException;
import java.net.Socket;
import java.util.Scanner;

public class MafiaClient {
    private static final String SERVER_IP = "127.0.0.1"; // 서버 IP 주소
    private static final int SERVER_PORT = 12345;

    private Socket socket;
    private ServerListener serverListener;
    private InputHandler inputHandler;

    public void startClient() {
        try {
            // 1. 서버에 연결
            socket = new Socket(SERVER_IP, SERVER_PORT);
            System.out.println("서버에 성공적으로 연결되었습니다: " + SERVER_IP + ":" + SERVER_PORT);

            // 2. 서버 수신 스레드 시작
            serverListener = new ServerListener(socket);
            serverListener.start();

            // 3. 사용자 입력 처리 스레드 시작
            inputHandler = new InputHandler(socket);
            inputHandler.start();

            // 클라이언트 메인 스레드는 두 핸들러 스레드가 종료되기를 기다림
            serverListener.join();
            inputHandler.join();

        } catch (IOException e) {
            System.err.println("서버 연결에 실패했습니다: " + e.getMessage());
        } catch (InterruptedException e) {
            System.err.println("클라이언트 메인 스레드 오류: " + e.getMessage());
        } finally {
            closeConnection();
        }
    }

    private void closeConnection() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                System.out.println("연결을 종료했습니다.");
            }
        } catch (IOException e) {
            System.err.println("소켓 종료 중 오류 발생: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        MafiaClient client = new MafiaClient();
        client.startClient();
    }
}