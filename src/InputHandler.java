import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public class InputHandler extends Thread {
    private final Socket socket;
    private PrintWriter out;

    public InputHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try (Scanner scanner = new Scanner(System.in)) {
            out = new PrintWriter(socket.getOutputStream(), true);

            System.out.println("명령어 입력 준비 완료. (예: LIST, CREATE 제목 5, ENTER ROOM_1, CHAT 메시지, /quit)");

            // 사용자 입력 루프
            while (true) {
                System.out.print("> ");
                if (scanner.hasNextLine()) {
                    String input = scanner.nextLine();

                    if (input.equalsIgnoreCase("/quit")) {
                        out.println("/quit");
                        break;
                    }

                    // 입력된 명령어를 서버로 전송
                    sendCommand(input);
                }
            }
        } catch (IOException e) {
            System.err.println("[SYSTEM] 서버 연결이 끊겨 입력을 전송할 수 없습니다.");
        } finally {
            try {
                if (out != null) out.close();
            } catch (Exception e) {
                // 무시
            }
        }
    }

    // 명령어를 서버로 전송하는 핵심 함수
    public void sendCommand(String command) {
        if (out != null && !socket.isClosed()) {
            // 명령어는 대문자로 변환하여 서버로 전송 (서버의 parseCommand와 일치시키기 위해)
            // CHAT 명령은 그대로 전송합니다.
            if (command.toUpperCase().startsWith("CHAT")) {
                out.println(command);
            } else {
                out.println(command.toUpperCase());
            }
        }
    }
}