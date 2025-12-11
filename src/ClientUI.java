import javax.swing.*;
import java.awt.*;

/**
 * 순수 UI 관리 클래스.
 * - JFrame / 패널 전환 등 화면 관련 처리만 담당
 * - 실제 네트워크/게임 상태는 Client / ClientGameState 가 담당
 */
public class ClientUI {

    private final Client client;
    private final ClientGameState gameState;

    private final JFrame frame;
    private final ServerConnectionPanel connectionPanel;
    private final WaitingGamePanel waitingGamePanel;
    private final GamePanel gamePanel;

    public ClientUI(Client client, ClientGameState gameState) {
        this.client = client;
        this.gameState = gameState;

        frame = new JFrame("마피아 게임 클라이언트");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 700);
        frame.setLocationRelativeTo(null);

        connectionPanel = new ServerConnectionPanel(client);
        waitingGamePanel = new WaitingGamePanel(client);
        gamePanel = new GamePanel(client, gameState);

        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(connectionPanel, BorderLayout.CENTER);
        frame.setVisible(true);
    }

    public JFrame getFrame() {
        return frame;
    }

    public GamePanel getGamePanel() {
        return gamePanel;
    }

    public WaitingGamePanel getWaitingGamePanel() {
        return waitingGamePanel;
    }

    public void showWaitingPanel() {
        frame.getContentPane().removeAll();
        frame.getContentPane().add(waitingGamePanel, BorderLayout.CENTER);
        frame.revalidate();
        frame.repaint();
    }

    public void showGamePanel() {
        frame.getContentPane().removeAll();
        frame.getContentPane().add(gamePanel, BorderLayout.CENTER);
        frame.revalidate();
        frame.repaint();
    }
}
