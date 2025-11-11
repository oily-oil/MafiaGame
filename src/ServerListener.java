import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;

public class ServerListener extends Thread {
    private final Socket socket;
    private BufferedReader in;

    public ServerListener(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String serverMessage;

            // 서버로부터 메시지를 계속해서 읽고 화면에 출력
            while ((serverMessage = in.readLine()) != null) {
                // 서버 메시지 처리 로직 (UI 업데이트)
                displayMessage(serverMessage);
            }
        } catch (IOException e) {
            // 서버와의 연결이 끊어진 경우
            System.err.println("\n[SYSTEM] 서버와의 연결이 끊어졌습니다. 클라이언트를 종료합니다.");
        } finally {
            try {
                if (in != null) in.close();
            } catch (IOException e) {
                // 무시
            }
        }
    }

    // 메시지를 사용자 화면(콘솔)에 출력
    private void displayMessage(String message) {
        // 실제 게임에서는 이 부분이 GUI 업데이트 로직이 됩니다.
        // 현재는 콘솔에 출력합니다.
        System.out.println(message);
        System.out.print("> "); // 입력 프롬프트 유지
    }
}