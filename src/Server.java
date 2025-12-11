import javax.swing.*;
import java.io.IOException;

/**
 * Server: 실행 진입점. GUI(ServerGUI)와 GameServer 를 연결하는 역할만 담당합니다.
 */
public class Server {

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
                    JOptionPane.showMessageDialog(serverGUI,
                            "서버 시작 실패: " + ex.getMessage(),
                            "오류",
                            JOptionPane.ERROR_MESSAGE);
                }
            });
        });
    }
}
