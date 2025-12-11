package mafia.server.ui;

import javax.swing.*;
import java.awt.*;
import java.io.PrintStream;
import java.io.OutputStream;

public class ServerGUI extends JFrame {
    private JTextArea logArea;
    private JTextField portNumberField;
    private JButton startButton;

    public ServerGUI() {
        setTitle("Mafia Game Server GUI");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);
        add(scrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = createBottomPanel();
        add(bottomPanel, BorderLayout.SOUTH);

        setPreferredSize(new Dimension(500, 600));
        pack();
        setLocationRelativeTo(null);
        setVisible(true);

        redirectSystemOut();
    }

    private JPanel createBottomPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new FlowLayout(FlowLayout.CENTER));

        panel.add(new JLabel("포트 넘버:"));
        portNumberField = new JTextField("9090", 5);
        panel.add(portNumberField);

        startButton = new JButton("서버 시작");
        panel.add(startButton);

        return panel;
    }

    public JButton getStartButton() {
        return startButton;
    }

    public int getPortNumber() {
        try {
            return Integer.parseInt(portNumberField.getText());
        } catch (NumberFormatException e) {
            return 9090; // 기본값
        }
    }

    private void redirectSystemOut() {
        PrintStream printStream = new PrintStream(new CustomOutputStream(logArea));
        System.setOut(printStream);
        System.setErr(printStream);
    }

    private class CustomOutputStream extends OutputStream {
        private JTextArea textArea;

        public CustomOutputStream(JTextArea textArea) {
            this.textArea = textArea;
        }

        @Override
        public void write(int b) {
            SwingUtilities.invokeLater(() -> {
                textArea.append(String.valueOf((char) b));
                textArea.setCaretPosition(textArea.getDocument().getLength());
            });
        }

        @Override
        public void write(byte[] b, int off, int len) {
            String text = new String(b, off, len);
            SwingUtilities.invokeLater(() -> {
                textArea.append(text);
                textArea.setCaretPosition(textArea.getDocument().getLength());
            });
        }
    }
}
