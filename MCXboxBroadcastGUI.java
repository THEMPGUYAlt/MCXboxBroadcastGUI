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

/**
 * MCXboxBroadcast Windows GUI Launcher
 *
 * HOW TO USE:
 *   1. Place this .java file in the same folder as MCXboxBroadcastStandalone.jar
 *   2. Open Command Prompt in that folder
 *   3. Compile:  javac MCXboxBroadcastGUI.java
 *   4. Run:      java MCXboxBroadcastGUI
 *
 * Requires Java 11+  (same JRE used to run the standalone JAR)
 */
public class MCXboxBroadcastGUI extends JFrame {

    // ── Colours ──────────────────────────────────────────────────────────────
    private static final Color BG_DARK       = new Color(0x1A1D23);
    private static final Color BG_PANEL      = new Color(0x22262F);
    private static final Color BG_INPUT      = new Color(0x2A2F3A);
    private static final Color ACCENT_GREEN  = new Color(0x4CAF50);
    private static final Color ACCENT_YELLOW = new Color(0xFFC107);
    private static final Color ACCENT_RED    = new Color(0xF44336);
    private static final Color ACCENT_BLUE   = new Color(0x42A5F5);
    private static final Color TEXT_PRIMARY  = new Color(0xECEFF4);
    private static final Color TEXT_MUTED    = new Color(0x8892A4);
    private static final Color TEXT_INFO     = new Color(0x88C0D0);
    private static final Color TEXT_WARN     = new Color(0xEBCB8B);
    private static final Color TEXT_ERROR    = new Color(0xBF616A);

    // ── UI components ────────────────────────────────────────────────────────
    private JTextPane      logPane;
    private StyledDocument logDoc;
    private JTextField     cmdField;
    private JLabel         statusLabel;
    private JLabel         statusDot;
    private JButton        startStopBtn;
    private JButton        restartBtn;
    private JButton        openBrowserBtn;
    private JTextField     jarPathField;
    private JSpinner       heapSpinner;
    private JCheckBox      autoRestartCb;
    private JSpinner       cooldownSpinner;
    private JPanel         authBarRef;

    // ── Process management ───────────────────────────────────────────────────
    private Process     process;
    private PrintWriter processStdin;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private final AtomicBoolean running  = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private ScheduledFuture<?> countdownFuture;

    // ── Auth detection ───────────────────────────────────────────────────────
    private static final Pattern AUTH_CODE_PATTERN =
        Pattern.compile("enter the code ([A-Z0-9]+)", Pattern.CASE_INSENSITIVE);
    private String lastAuthUrl = "https://www.microsoft.com/link";

    // ── Preferences ──────────────────────────────────────────────────────────
    private static final String PREFS_FILE = "mcxboxbroadcast-gui.properties";
    private final Properties prefs = new Properties();

    // ── Time formatter ───────────────────────────────────────────────────────
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ════════════════════════════════════════════════════════════════════════
    //  ENTRY POINT
    // ════════════════════════════════════════════════════════════════════════
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new MCXboxBroadcastGUI().setVisible(true));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  CONSTRUCTOR
    // ════════════════════════════════════════════════════════════════════════
    public MCXboxBroadcastGUI() {
        super("MCXboxBroadcast — Windows Launcher");
        loadPrefs();
        buildUI();
        setSize(960, 680);
        setMinimumSize(new Dimension(720, 480));
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { onClose(); }
        });
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UI BUILD
    // ════════════════════════════════════════════════════════════════════════
    private void buildUI() {
        setLayout(new BorderLayout(0, 0));

        // ── Top bar ──────────────────────────────────────────────────────────
        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setBackground(BG_PANEL);
        topBar.setBorder(new EmptyBorder(10, 14, 10, 14));

        JLabel logo = new JLabel("MCXboxBroadcast  —  Windows Launcher");
        logo.setFont(new Font("Segoe UI", Font.BOLD, 15));
        logo.setForeground(ACCENT_GREEN);
        topBar.add(logo, BorderLayout.WEST);

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        statusPanel.setOpaque(false);
        statusDot = new JLabel("●");
        statusDot.setFont(new Font("Segoe UI", Font.BOLD, 15));
        statusDot.setForeground(ACCENT_RED);
        statusLabel = new JLabel("Stopped");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        statusLabel.setForeground(ACCENT_RED);
        statusPanel.add(statusDot);
        statusPanel.add(statusLabel);
        topBar.add(statusPanel, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        // ── Left sidebar ─────────────────────────────────────────────────────
        add(buildSidebar(), BorderLayout.WEST);

        // ── Centre: log + command row ─────────────────────────────────────────
        JPanel centre = new JPanel(new BorderLayout(0, 4));
        centre.setBackground(BG_DARK);
        centre.setBorder(new EmptyBorder(8, 4, 8, 8));

        logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setBackground(new Color(0x12141A));
        logPane.setFont(new Font("Consolas", Font.PLAIN, 12));
        logDoc = logPane.getStyledDocument();

        JScrollPane scroll = new JScrollPane(logPane);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(0x2E3440)));
        centre.add(scroll, BorderLayout.CENTER);

        // Command input row
        JPanel cmdRow = new JPanel(new BorderLayout(6, 0));
        cmdRow.setBackground(BG_DARK);

        JLabel prompt = new JLabel(">");
        prompt.setFont(new Font("Consolas", Font.BOLD, 14));
        prompt.setForeground(ACCENT_GREEN);
        prompt.setBorder(new EmptyBorder(0, 4, 0, 4));

        cmdField = new JTextField();
        styleTextField(cmdField);
        cmdField.setFont(new Font("Consolas", Font.PLAIN, 13));
        cmdField.setToolTipText("Commands: restart | dumpsession | accounts list | accounts add <id> | exit");
        cmdField.addActionListener(e -> sendCommand());

        JButton sendBtn = makeButton("Send", ACCENT_GREEN);
        sendBtn.addActionListener(e -> sendCommand());

        cmdRow.add(prompt,   BorderLayout.WEST);
        cmdRow.add(cmdField, BorderLayout.CENTER);
        cmdRow.add(sendBtn,  BorderLayout.EAST);
        centre.add(cmdRow, BorderLayout.SOUTH);

        add(centre, BorderLayout.CENTER);

        // ── Auth banner (hidden until detected) ──────────────────────────────
        openBrowserBtn = makeButton("  Open Microsoft Login Page  ", ACCENT_BLUE);
        openBrowserBtn.addActionListener(e -> {
            try { Desktop.getDesktop().browse(new URI(lastAuthUrl)); }
            catch (Exception ex) { appendLog("[GUI] Could not open browser: " + ex.getMessage(), TEXT_ERROR); }
        });

        JLabel authHint = new JLabel("  Authentication required — sign in with your Microsoft/Xbox account:");
        authHint.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        authHint.setForeground(ACCENT_BLUE);

        authBarRef = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        authBarRef.setBackground(new Color(0x0D1A2A));
        authBarRef.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ACCENT_BLUE));
        authBarRef.add(authHint);
        authBarRef.add(openBrowserBtn);
        authBarRef.setVisible(false);

        add(authBarRef, BorderLayout.SOUTH);

        // Welcome message
        appendLog("[GUI] MCXboxBroadcast Launcher ready.", ACCENT_GREEN);
        appendLog("[GUI] Select your JAR file, configure settings, then click Start.", TEXT_MUTED);
    }

    private JPanel buildSidebar() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(BG_PANEL);
        p.setBorder(new EmptyBorder(14, 12, 14, 12));
        p.setPreferredSize(new Dimension(220, 0));

        // ── JAR file ──────────────────────────────────────────────────────────
        p.add(sectionLabel("JAR FILE"));
        jarPathField = new JTextField(prefs.getProperty("jar.path", "MCXboxBroadcastStandalone.jar"));
        styleTextField(jarPathField);
        jarPathField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        jarPathField.setToolTipText("Full path to MCXboxBroadcastStandalone.jar");
        p.add(jarPathField);
        p.add(Box.createVerticalStrut(4));

        JButton browseBtn = makeButton("Browse...", TEXT_MUTED);
        browseBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        browseBtn.addActionListener(e -> browseJar());
        p.add(browseBtn);

        // ── Heap ──────────────────────────────────────────────────────────────
        p.add(Box.createVerticalStrut(14));
        p.add(sectionLabel("MAX HEAP (MB)"));
        int heap = parseIntSafe(prefs.getProperty("heap.mb", "256"), 256);
        heapSpinner = new JSpinner(new SpinnerNumberModel(heap, 64, 4096, 64));
        styleSpinner(heapSpinner);
        heapSpinner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        p.add(heapSpinner);

        // ── Auto-restart ─────────────────────────────────────────────────────
        p.add(Box.createVerticalStrut(14));
        p.add(sectionLabel("AUTO-RESTART"));
        autoRestartCb = new JCheckBox("Restart on crash / exit");
        autoRestartCb.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        autoRestartCb.setForeground(TEXT_PRIMARY);
        autoRestartCb.setBackground(BG_PANEL);
        autoRestartCb.setSelected(Boolean.parseBoolean(prefs.getProperty("auto.restart", "true")));
        autoRestartCb.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(autoRestartCb);

        p.add(Box.createVerticalStrut(8));
        p.add(sectionLabel("COOLDOWN (seconds)"));
        int cooldown = parseIntSafe(prefs.getProperty("cooldown.s", "30"), 30);
        cooldownSpinner = new JSpinner(new SpinnerNumberModel(cooldown, 5, 300, 5));
        styleSpinner(cooldownSpinner);
        cooldownSpinner.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        p.add(cooldownSpinner);

        // ── Controls ─────────────────────────────────────────────────────────
        p.add(Box.createVerticalStrut(20));
        p.add(sectionLabel("CONTROLS"));

        startStopBtn = makeButton("▶   Start", ACCENT_GREEN);
        startStopBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        startStopBtn.addActionListener(e -> toggleStartStop());
        p.add(startStopBtn);
        p.add(Box.createVerticalStrut(6));

        restartBtn = makeButton("↺   Restart Session", ACCENT_YELLOW);
        restartBtn.setEnabled(false);
        restartBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        restartBtn.addActionListener(e -> doRestart());
        p.add(restartBtn);

        p.add(Box.createVerticalGlue());

        JLabel footer = new JLabel("MCXboxBroadcast GUI Launcher");
        footer.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        footer.setForeground(TEXT_MUTED);
        footer.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(footer);

        return p;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PROCESS LIFECYCLE
    // ════════════════════════════════════════════════════════════════════════

    private void startProcess() {
        if (running.get()) return;

        String jar = jarPathField.getText().trim();
        File   jarFile = new File(jar);

        if (!jarFile.exists()) {
            appendLog("[GUI] ERROR: JAR not found at: " + jar, TEXT_ERROR);
            appendLog("[GUI] Click Browse... to locate MCXboxBroadcastStandalone.jar", TEXT_WARN);
            return;
        }

        int heapMb = (int) heapSpinner.getValue();
        stopping.set(false);
        running.set(true);
        setStatus("Starting...", ACCENT_YELLOW);

        SwingUtilities.invokeLater(() -> {
            if (authBarRef != null) authBarRef.setVisible(false);
        });

        File workDir = jarFile.getParentFile() != null ? jarFile.getParentFile() : new File(".");

        ProcessBuilder pb = new ProcessBuilder(
            "java",
            "-Xms64m",
            "-Xmx" + heapMb + "m",
            "-jar", jarFile.getAbsolutePath()
        );
        pb.directory(workDir);
        pb.redirectErrorStream(true);

        appendLog("[GUI] Working dir: " + workDir.getAbsolutePath(), TEXT_MUTED);
        appendLog("[GUI] Command: java -Xmx" + heapMb + "m -jar " + jarFile.getName(), TEXT_MUTED);

        scheduler.submit(() -> {
            try {
                process = pb.start();
                processStdin = new PrintWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8), true);

                SwingUtilities.invokeLater(() -> {
                    startStopBtn.setText("■   Stop");
                    restartBtn.setEnabled(true);
                    jarPathField.setEnabled(false);
                    heapSpinner.setEnabled(false);
                });

                // Stream all output
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        processLine(line);
                    }
                }

                int exitCode = process.waitFor();
                running.set(false);

                SwingUtilities.invokeLater(() -> {
                    appendLog("[GUI] Process exited with code " + exitCode, TEXT_MUTED);
                    startStopBtn.setText("▶   Start");
                    restartBtn.setEnabled(false);
                    jarPathField.setEnabled(true);
                    heapSpinner.setEnabled(true);
                    if (authBarRef != null) authBarRef.setVisible(false);
                    setStatus("Stopped", ACCENT_RED);
                });

                // Auto-restart watchdog
                if (!stopping.get() && autoRestartCb.isSelected()) {
                    scheduleAutoRestart((int) cooldownSpinner.getValue());
                }

            } catch (IOException ex) {
                running.set(false);
                appendLog("[GUI] Failed to launch: " + ex.getMessage(), TEXT_ERROR);
                appendLog("[GUI] Make sure 'java' is on your PATH (test: java -version)", TEXT_WARN);
                SwingUtilities.invokeLater(() -> setStatus("Stopped", ACCENT_RED));
            } catch (InterruptedException ex) {
                running.set(false);
                Thread.currentThread().interrupt();
            }
        });
    }

    private void stopProcess(boolean userInitiated) {
        if (!running.get()) return;
        if (userInitiated) stopping.set(true);
        if (countdownFuture != null) { countdownFuture.cancel(false); countdownFuture = null; }
        if (processStdin != null) processStdin.println("exit");
        scheduler.schedule(() -> {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }, 4, TimeUnit.SECONDS);
    }

    /**
     * Sends the built-in "restart" command to the JAR's stdin.
     * This triggers SessionManager.restart() inside the JVM — tears down
     * and re-creates the Xbox Live session without killing the process.
     * If the process dies anyway the auto-restart watchdog picks it up.
     */
    private void doRestart() {
        if (!running.get()) { startProcess(); return; }
        appendLog("[GUI] Sending restart command...", ACCENT_YELLOW);
        setStatus("Restarting...", ACCENT_YELLOW);
        if (processStdin != null) processStdin.println("restart");
        scheduler.schedule(() -> {
            if (!running.get() && !stopping.get()) {
                SwingUtilities.invokeLater(this::startProcess);
            } else {
                SwingUtilities.invokeLater(() -> setStatus("Running", ACCENT_GREEN));
            }
        }, 8, TimeUnit.SECONDS);
    }

    private void scheduleAutoRestart(int delaySecs) {
        appendLog("[GUI] Auto-restart watchdog — restarting in " + delaySecs + " s...", ACCENT_YELLOW);
        setStatus("Restarting in " + delaySecs + " s", ACCENT_YELLOW);
        final int[] remaining = {delaySecs};
        countdownFuture = scheduler.scheduleAtFixedRate(() -> {
            if (stopping.get()) { countdownFuture.cancel(false); return; }
            remaining[0]--;
            SwingUtilities.invokeLater(() -> setStatus("Restarting in " + remaining[0] + " s...", ACCENT_YELLOW));
            if (remaining[0] <= 0) {
                countdownFuture.cancel(false);
                SwingUtilities.invokeLater(this::startProcess);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    private void toggleStartStop() {
        if (running.get()) stopProcess(true);
        else               startProcess();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  LOG PROCESSING
    // ════════════════════════════════════════════════════════════════════════

    // Strips ANSI/VT100 escape sequences (e.g. [36;1mINFO[m] from log4j/jansi output)
    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[;\\d]*[A-Za-z]|\\[\\d+[;m][\\d;]*m?");

    private void processLine(String raw) {
        // Strip ANSI escape codes before any processing
        String clean = ANSI_PATTERN.matcher(raw).replaceAll("").trim();
        if (clean.isEmpty()) return;

        Color color = TEXT_PRIMARY;
        String lower = clean.toLowerCase();

        // Colour-code by log level — works for both [INFO] and [36;1mINFO after stripping
        if (lower.contains("[error]") || lower.contains("exception") || lower.contains("failed"))
            color = TEXT_ERROR;
        else if (lower.contains("[warn]") || lower.contains("warning"))
            color = TEXT_WARN;
        else if (lower.contains("[info]"))
            color = TEXT_INFO;

        // Session running confirmation
        if (lower.contains("creation of xbox live session was successful")
                || lower.contains("created session")
                || lower.contains("session created")
                || lower.contains("updated session")) {
            if (lower.contains("successful") || lower.contains("created")) {
                color = ACCENT_GREEN;
                SwingUtilities.invokeLater(() -> setStatus("Running", ACCENT_GREEN));
            }
        }

        // Detect Microsoft auth prompt line
        if (lower.contains("microsoft.com/link")) {
            Matcher m = AUTH_CODE_PATTERN.matcher(clean);
            String code = m.find() ? "  Code: " + m.group(1) : "";
            appendLog("[GUI] Auth required" + code + "  ->  Click the button below", ACCENT_BLUE);
            SwingUtilities.invokeLater(() -> {
                if (authBarRef != null) authBarRef.setVisible(true);
            });
        }

        appendLog(clean, color);
    }

    private void appendLog(String text, Color color) {
        String ts = "[" + LocalTime.now().format(TIME_FMT) + "] ";
        SwingUtilities.invokeLater(() -> {
            try {
                SimpleAttributeSet tsAttr = new SimpleAttributeSet();
                StyleConstants.setForeground(tsAttr, TEXT_MUTED);
                logDoc.insertString(logDoc.getLength(), ts, tsAttr);

                SimpleAttributeSet lineAttr = new SimpleAttributeSet();
                StyleConstants.setForeground(lineAttr, color);
                logDoc.insertString(logDoc.getLength(), text + "\n", lineAttr);

                logPane.setCaretPosition(logDoc.getLength());
            } catch (BadLocationException ignored) {}
        });
    }

    private void sendCommand() {
        String cmd = cmdField.getText().trim();
        if (cmd.isEmpty()) return;
        if (!running.get()) {
            appendLog("[GUI] Process is not running — start it first.", TEXT_WARN);
            return;
        }
        appendLog("> " + cmd, ACCENT_GREEN);
        if (processStdin != null) processStdin.println(cmd);
        cmdField.setText("");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  STATUS HELPER
    // ════════════════════════════════════════════════════════════════════════

    private void setStatus(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusDot.setForeground(color);
            statusLabel.setText(text);
            statusLabel.setForeground(color);
        });
    }

    // ════════════════════════════════════════════════════════════════════════
    //  FILE CHOOSER
    // ════════════════════════════════════════════════════════════════════════

    private void browseJar() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Select MCXboxBroadcastStandalone.jar");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JAR files (*.jar)", "jar"));
        String current = jarPathField.getText().trim();
        if (!current.isEmpty()) {
            File f = new File(current);
            fc.setCurrentDirectory(f.getParentFile() != null ? f.getParentFile() : new File("."));
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            jarPathField.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PREFERENCES
    // ════════════════════════════════════════════════════════════════════════

    private void loadPrefs() {
        try (InputStream in = new FileInputStream(PREFS_FILE)) { prefs.load(in); }
        catch (IOException ignored) {}
    }

    private void savePrefs() {
        if (jarPathField    != null) prefs.setProperty("jar.path",     jarPathField.getText());
        if (heapSpinner     != null) prefs.setProperty("heap.mb",      heapSpinner.getValue().toString());
        if (autoRestartCb   != null) prefs.setProperty("auto.restart", Boolean.toString(autoRestartCb.isSelected()));
        if (cooldownSpinner != null) prefs.setProperty("cooldown.s",   cooldownSpinner.getValue().toString());
        try (OutputStream out = new FileOutputStream(PREFS_FILE)) {
            prefs.store(out, "MCXboxBroadcast GUI preferences");
        } catch (IOException ignored) {}
    }

    // ════════════════════════════════════════════════════════════════════════
    //  CLOSE
    // ════════════════════════════════════════════════════════════════════════

    private void onClose() {
        int choice = JOptionPane.showConfirmDialog(this,
            "Stop MCXboxBroadcast and exit the launcher?",
            "Confirm Exit", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;
        savePrefs();
        stopping.set(true);
        stopProcess(true);
        scheduler.shutdownNow();
        dispose();
        System.exit(0);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  STYLE HELPERS
    // ════════════════════════════════════════════════════════════════════════

    private JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Segoe UI", Font.BOLD, 10));
        l.setForeground(TEXT_MUTED);
        l.setBorder(new EmptyBorder(4, 0, 3, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private void styleTextField(JTextField f) {
        f.setBackground(BG_INPUT);
        f.setForeground(TEXT_PRIMARY);
        f.setCaretColor(TEXT_PRIMARY);
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x3B4252)),
            new EmptyBorder(4, 6, 4, 6)));
        f.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    private void styleSpinner(JSpinner s) {
        s.setBackground(BG_INPUT);
        JComponent editor = s.getEditor();
        if (editor instanceof JSpinner.DefaultEditor) {
            JTextField tf = ((JSpinner.DefaultEditor) editor).getTextField();
            tf.setBackground(BG_INPUT);
            tf.setForeground(TEXT_PRIMARY);
            tf.setCaretColor(TEXT_PRIMARY);
        }
        s.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    private JButton makeButton(String text, Color fg) {
        JButton b = new JButton(text);
        b.setFont(new Font("Segoe UI", Font.BOLD, 12));
        b.setForeground(fg);
        b.setBackground(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 28));
        b.setOpaque(true);
        b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 110)),
            new EmptyBorder(5, 12, 5, 12)));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        Color hoverColor = new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 60);
        Color normColor  = new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 28);
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { if (b.isEnabled()) b.setBackground(hoverColor); }
            @Override public void mouseExited (MouseEvent e) { b.setBackground(normColor); }
        });
        return b;
    }

    private static int parseIntSafe(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }
}
