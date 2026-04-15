import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;

public class MCXboxBroadcastGUI extends JFrame {

    // ───────────────── THEME (MODERN DISCORD STYLE) ─────────────────
    private static class T {
        static final Color BG = new Color(0x0B0F19);
        static final Color PANEL = new Color(0x111827);
        static final Color CARD = new Color(0x161F32);
        static final Color BORDER = new Color(0x26324A);

        static final Color TEXT = new Color(0xE5E7EB);
        static final Color MUTED = new Color(0x9CA3AF);

        static final Color GREEN = new Color(0x22C55E);
        static final Color YELLOW = new Color(0xFACC15);
        static final Color RED = new Color(0xEF4444);
        static final Color BLUE = new Color(0x3B82F6);

        static final Font FONT = new Font("Segoe UI", Font.PLAIN, 13);
        static final Font BOLD = new Font("Segoe UI Semibold", Font.BOLD, 13);
    }

    // ───────────────── UI ─────────────────
    JTextPane logPane;
    StyledDocument logDoc;

    JTextField cmdField;
    JTextField jarField;
    JSpinner heap;

    JLabel statusLabel;

    JButton startBtn, restartBtn;

    JPanel authBar;

    // ───────────────── PROCESS ─────────────────
    Process process;
    PrintWriter stdin;

    AtomicBoolean running = new AtomicBoolean(false);
    AtomicBoolean stopping = new AtomicBoolean(false);

    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ───────────────── MAIN ─────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MCXboxBroadcastGUI().setVisible(true));
    }

    public MCXboxBroadcastGUI() {
        super("MCXboxBroadcast Launcher");

        setSize(920, 620);
        setMinimumSize(new Dimension(750, 500));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());

        getContentPane().setBackground(T.BG);

        add(buildTop(), BorderLayout.NORTH);
        add(buildSide(), BorderLayout.WEST);
        add(buildCenter(), BorderLayout.CENTER);

        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                stopProcess();
                System.exit(0);
            }
        });
    }

    // ───────────────── TOP BAR ─────────────────
    private JPanel buildTop() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(T.PANEL);
        p.setBorder(new EmptyBorder(10, 14, 10, 14));

        JLabel title = new JLabel("MCXboxBroadcast");
        title.setForeground(T.TEXT);
        title.setFont(T.BOLD);

        statusLabel = new JLabel("Stopped");
        statusLabel.setForeground(T.RED);

        p.add(title, BorderLayout.WEST);
        p.add(statusLabel, BorderLayout.EAST);

        return p;
    }

    // ───────────────── SIDEBAR ─────────────────
    private JPanel buildSide() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(T.PANEL);
        p.setPreferredSize(new Dimension(240, 0));
        p.setBorder(new EmptyBorder(12, 12, 12, 12));

        p.add(cardLabel("JAR PATH"));

        jarField = new JTextField("MCXboxBroadcastStandalone.jar");
        style(jarField);
        p.add(jarField);

        p.add(space(10));

        p.add(cardLabel("HEAP MB"));

        heap = new JSpinner(new SpinnerNumberModel(256, 64, 4096, 64));
        p.add(heap);

        p.add(space(15));

        startBtn = button("Start", T.GREEN);
        restartBtn = button("Restart", T.YELLOW);

        startBtn.addActionListener(e -> startProcess());
        restartBtn.addActionListener(e -> send("restart"));

        p.add(startBtn);
        p.add(space(8));
        p.add(restartBtn);

        return p;
    }

    // ───────────────── CENTER ─────────────────
    private JPanel buildCenter() {
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBackground(T.BG);
        p.setBorder(new EmptyBorder(10, 10, 10, 10));

        logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setBackground(T.BG);
        logPane.setForeground(T.TEXT);
        logPane.setFont(new Font("Consolas", Font.PLAIN, 13));
        logDoc = logPane.getStyledDocument();

        JScrollPane scroll = new JScrollPane(logPane);
        scroll.setBorder(null);

        // command bar
        JPanel cmdBar = new JPanel(new BorderLayout(8, 0));
        cmdBar.setBackground(T.BG);

        cmdField = new JTextField();
        style(cmdField);

        JButton send = button("Send", T.BLUE);
        send.addActionListener(e -> send(cmdField.getText()));

        cmdBar.add(cmdField, BorderLayout.CENTER);
        cmdBar.add(send, BorderLayout.EAST);

        p.add(scroll, BorderLayout.CENTER);
        p.add(cmdBar, BorderLayout.SOUTH);

        return p;
    }

    // ───────────────── START / STOP ─────────────────
    private void startProcess() {
        if (running.get()) return;

        try {
            File jar = new File(jarField.getText());
            if (!jar.exists()) {
                log("JAR not found");
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(
                    "java",
                    "-Xmx" + heap.getValue() + "m",
                    "-jar",
                    jar.getAbsolutePath()
            );

            process = pb.start();
            stdin = new PrintWriter(process.getOutputStream(), true);

            running.set(true);
            stopping.set(false);

            setStatus("Running", T.GREEN);

            new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {

                    String line;
                    while ((line = r.readLine()) != null) {
                        log(line);
                    }

                } catch (Exception ignored) {}
            }).start();

        } catch (Exception e) {
            log("Error: " + e.getMessage());
        }
    }

    private void stopProcess() {
        running.set(false);
        stopping.set(true);

        if (stdin != null) stdin.println("exit");
        if (process != null) process.destroy();

        setStatus("Stopped", T.RED);
    }

    private void send(String cmd) {
        if (!running.get()) return;
        if (cmd.isEmpty()) return;

        stdin.println(cmd);
        cmdField.setText("");

        log("> " + cmd);
    }

    // ───────────────── LOG ─────────────────
    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            try {
                logDoc.insertString(
                        logDoc.getLength(),
                        "[" + LocalTime.now().format(TIME) + "] " + msg + "\n",
                        null
                );
            } catch (Exception ignored) {}
        });
    }

    // ───────────────── STATUS ─────────────────
    private void setStatus(String text, Color c) {
        statusLabel.setText(text);
        statusLabel.setForeground(c);
    }

    // ───────────────── UI HELPERS ─────────────────
    private JButton button(String t, Color c) {
        JButton b = new JButton(t);
        b.setBackground(c);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        return b;
    }

    private void style(JTextField f) {
        f.setBackground(T.PANEL);
        f.setForeground(T.TEXT);
        f.setCaretColor(T.TEXT);
        f.setBorder(new EmptyBorder(8, 10, 8, 10));
    }

    private JLabel cardLabel(String t) {
        JLabel l = new JLabel(t);
        l.setForeground(T.MUTED);
        l.setFont(T.FONT);
        return l;
    }

    private Component space(int h) {
        return Box.createVerticalStrut(h);
    }
}
