package p4gate;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.regex.Pattern;

/**
 * The "why can't I connect" panel (README 5.1, rows 1-2 — the #1 reason users bounce).
 * Invariant: the diagnosis runs p4 exactly the way every other tab does (same executable, same `-d <workdir>`),
 * so what the report says is what the plugin experiences. The verdict names the first failing step.
 */
public final class P4Connect {
    public enum Verdict { READY, NOT_READY, NOT_CONNECTED }

    /** Windows CreateProcess: error=2 is "file not found" (the executable), error=267 "directory name is invalid". */
    private static final Pattern MISSING_EXE = Pattern.compile("error=2(?!\\d)");
    private static final Pattern MISSING_DIR = Pattern.compile("error=267(?!\\d)");

    /** Full diagnosis: rendered report, verdict, and the first failing step (null when READY). */
    public record Report(String text, Verdict verdict, String firstFailure) { }

    private final P4Project service;
    private final JTextArea reportArea = new JTextArea(24, 90);
    private final JTextField exeField;
    private final JTextField dirField;
    private final JBLabel banner = new JBLabel(" ");

    public P4Connect(P4Project service) {
        this.service = service;
        this.exeField = new JBTextField(service.cli().executable(), 18);
        this.dirField = new JBTextField(service.cli().workdir(), 36);
    }

    public JComponent root() {
        // labels share one column, fields stretch to the dock width (never clipped), buttons share a row
        JPanel actions = new JPanel(new GridLayout(1, 2, 6, 0));
        actions.add(button("Save & run diagnosis", this::runDiagnosis));
        actions.add(button("Import env from P4CONFIG", this::importConfig));
        banner.setBorder(JBUI.Borders.empty(6, 8));
        banner.setVisible(false);
        JPanel rows = new JPanel(new GridLayout(0, 1, 0, 6));
        int labelWidth = Math.max(new JBLabel("p4 executable:").getPreferredSize().width,
                new JBLabel("workspace dir:").getPreferredSize().width);
        rows.add(labeled("p4 executable:", exeField, labelWidth));
        rows.add(labeled("workspace dir:", dirField, labelWidth));
        rows.add(actions);
        JPanel form = new JPanel(new BorderLayout(0, 6));
        form.add(rows, BorderLayout.NORTH);
        form.add(banner, BorderLayout.SOUTH);
        form.setBorder(JBUI.Borders.empty(8));

        reportArea.setText("Press “Save & run diagnosis” to find out why p4 can’t connect (or to confirm that it can).");
        reportArea.setEditable(false);
        reportArea.setLineWrap(true); // long paths and hints wrap instead of running off the edge
        reportArea.setWrapStyleWord(true);
        reportArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.label().getSize()));
        reportArea.setBorder(JBUI.Borders.empty(4, 8));
        JBScrollPane report = new JBScrollPane(reportArea);
        report.setBorder(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(form, BorderLayout.NORTH);
        panel.add(report, BorderLayout.CENTER);
        return panel;
    }

    /** The verdict at a glance: green tick when READY, otherwise the first failing step. */
    private void showVerdict(Report r) {
        Color green = new JBColor(0x368746, 0x5FAD65);
        Color red = new JBColor(0xC7222D, 0xE05555);
        Color amber = new JBColor(0x9E6A03, 0xD9A343);
        switch (r.verdict()) {
            case READY -> style(AllIcons.General.InspectionsOK, green, "Ready — connected, client exists, authenticated");
            case NOT_READY -> style(AllIcons.General.Warning, amber, "Not ready — " + r.firstFailure());
            case NOT_CONNECTED -> style(AllIcons.General.Error, red, "Not connected — " + r.firstFailure());
        }
    }

    private void style(Icon icon, Color color, String text) {
        banner.setIcon(icon);
        banner.setForeground(color);
        // one line that ends in "…" when the dock is narrow (a wrapping label would not re-measure its height);
        // the full text is in the tooltip and in the report below
        banner.setText(text);
        banner.setToolTipText(text);
        banner.setVisible(true);
    }

    /** label on the left (all labels one width), field stretched across the rest of the row. */
    private static JPanel labeled(String label, JComponent field, int labelWidth) {
        JBLabel l = new JBLabel(label);
        l.setPreferredSize(new Dimension(labelWidth, l.getPreferredSize().height));
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.add(l, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    private static JButton button(String label, Runnable action) {
        JButton b = new JButton(label);
        b.addActionListener(e -> action.run()); // EDT: only reads fields and queues background work
        return b;
    }

    private void runDiagnosis() {
        String exe = exeField.getText().strip();
        String dir = dirField.getText().strip();
        service.configure(exe.isEmpty() ? "p4" : exe, dir); // every tab now uses exactly what is diagnosed
        banner.setVisible(false);
        reportArea.setText("running diagnosis...");
        P4Cli cli = service.cli();
        service.background("Perforce: connection diagnosis", true, false, indicator -> {
            Report r = diagnose(cli);
            service.ui(() -> {
                reportArea.setText(r.text());
                reportArea.setCaretPosition(0);
                showVerdict(r);
            });
        });
    }

    private void importConfig() {
        banner.setVisible(false); // the environment is about to change: the last verdict no longer applies
        reportArea.setText("looking for a P4CONFIG file...");
        P4Cli cli = service.cli();
        service.background("Perforce: import P4CONFIG", true, false, indicator -> {
            StringBuilder b = new StringBuilder();
            try {
                SortedMap<String, String> env = P4Env.processEnv();
                SortedMap<String, String> set = P4Env.parseSet(P4Env.p4Set(cli));
                List<String> names = P4Env.configNames(env, set);
                Optional<Path> f = P4Env.findConfig(Path.of(cli.workdir()), names);
                if (f.isEmpty()) {
                    b.append("no P4CONFIG file found at or above ").append(cli.workdir()).append("\nlooked for: ").append(names);
                } else {
                    SortedMap<String, String> cfg = P4Env.parseConfig(f.get());
                    Map<String, String> override = new java.util.TreeMap<>();
                    for (String key : List.of("P4PORT", "P4USER", "P4CLIENT")) {
                        if (cfg.containsKey(key)) override.put(key, cfg.get(key));
                    }
                    service.setEnvOverride(override);
                    b.append("imported from ").append(f.get()).append(" (this project, this session):\n");
                    override.forEach((k, v) -> b.append("  ").append(k).append('=').append(v).append('\n'));
                    b.append("\nThese are passed to every p4 call as environment variables. Note: if p4 also reads a P4CONFIG\n")
                     .append("file from the workspace dir, that file still wins over environment variables.\n");
                }
            } catch (Exception e) {
                b.append("import failed: ").append(e);
            }
            service.ui(() -> reportArea.setText(b.toString()));
        });
    }

    /** Runs every check with the given cli and renders the report. Also drives the CLI verification path. */
    public static Report diagnose(P4Cli cli) {
        StringBuilder b = new StringBuilder();
        b.append("== p4 connect diagnosis ==\n\n");
        List<String> failing = new ArrayList<>();
        boolean reachable = false;

        b.append("[1] p4 executable: ").append(cli.executable()).append('\n');
        b.append("    workspace dir: ").append(cli.workdir()).append('\n');
        b.append("    every plugin call runs as: ").append(String.join(" ", cli.commandLine("<command>"))).append('\n');
        b.append("    (-d makes p4 resolve P4CONFIG from the workspace dir, not from $PWD or the IDE's own directory)\n\n");
        if (cli.workdir() == null || cli.workdir().isBlank() || !Files.isDirectory(Path.of(cli.workdir()))) {
            String first = "[1] the workspace dir '" + cli.workdir() + "' does not exist — " + hint("workspace dir does not exist");
            b.append("    ").append(first).append("\n\nVERDICT: NOT CONNECTED — first failing step ").append(first).append('\n');
            return new Report(b.toString(), Verdict.NOT_CONNECTED, first);
        }

        SortedMap<String, String> env = P4Env.processEnv();
        b.append("[2] IDE process environment:\n");
        if (env.isEmpty()) {
            b.append("    (no P4* variables set)\n");
        } else {
            env.forEach((k, v) -> b.append("    ").append(k).append('=').append(v).append('\n'));
        }
        if (!cli.envOverride().isEmpty()) {
            b.append("    plugin env override (imported, this project):\n");
            cli.envOverride().forEach((k, v) -> b.append("      ").append(k).append('=').append(v).append('\n'));
        }
        b.append('\n');

        P4Cli.Result setRes = cli.run("set");
        SortedMap<String, String> set = P4Env.parseSet(setRes.text());
        b.append("[3] `p4 set` from the workspace dir (p4's own answer, with the source of each value):\n");
        if (!setRes.ok()) {
            b.append("    FAILED: ").append(setRes.text().strip()).append('\n');
            failing.add("[3] p4 could not run — " + hint(setRes.text()));
        } else {
            List<String> lines = P4Env.annotatedSet(setRes.out());
            if (lines.isEmpty()) b.append("    (nothing set)\n");
            for (String line : lines) b.append("    ").append(line).append('\n');
        }
        b.append('\n');

        b.append("[4] P4CONFIG file lookup from ").append(cli.workdir()).append(":\n");
        String cfgVar = env.get("P4CONFIG");
        if (cfgVar == null) cfgVar = set.get("P4CONFIG");
        boolean p4ReadsConfig = cfgVar != null && !cfgVar.isBlank();
        if (!p4ReadsConfig) {
            b.append("    P4CONFIG is not set (neither env nor `p4 set`) — p4 reads NO config file at all;\n")
             .append("    it falls back to built-in defaults (e.g. port perforce:1666).\n");
        }
        List<String> names = P4Env.configNames(env, set);
        Optional<Path> cfgFile = Optional.empty();
        SortedMap<String, String> cfg = new java.util.TreeMap<>();
        try {
            cfgFile = P4Env.findConfig(Path.of(cli.workdir()), names);
        } catch (Exception e) {
            b.append("    lookup failed: ").append(e).append('\n');
        }
        if (cfgFile.isEmpty()) {
            b.append("    none found (looked for ").append(names).append(")\n");
        } else {
            b.append("    found: ").append(cfgFile.get()).append('\n');
            String foundName = cfgFile.get().getFileName().toString();
            if (!p4ReadsConfig) {
                b.append("    WARNING: p4 will NOT read it — set P4CONFIG (e.g. `p4 set P4CONFIG=").append(foundName)
                 .append("`) or an env var P4CONFIG=").append(foundName).append(" to make p4 use it.\n");
            } else if (!foundName.equals(cfgVar)) {
                b.append("    WARNING: p4 looks for a file named '").append(cfgVar).append("' — it will not read '")
                 .append(foundName).append("'.\n")
                 .append("    rename the file to '").append(cfgVar).append("' or set P4CONFIG='").append(foundName).append("'.\n");
            }
            try {
                cfg = P4Env.parseConfig(cfgFile.get());
                cfg.forEach((k, v) -> b.append("      ").append(k).append('=').append(v).append('\n'));
            } catch (Exception e) {
                b.append("      unreadable: ").append(e).append('\n');
            }
        }
        // p4 precedence (verified against p4.exe r25.2): P4CONFIG file > environment > `p4 set` > built-in defaults.
        if (p4ReadsConfig) {
            for (String key : List.of("P4PORT", "P4USER", "P4CLIENT")) {
                String cv = cfg.get(key);
                String ev = cli.envOverride().containsKey(key) ? cli.envOverride().get(key) : env.get(key);
                if (cv != null && ev != null && !ev.equals(cv)) {
                    b.append("    conflict: the config file's ").append(key).append('=').append(cv)
                     .append(" silently overrides the environment's ").append(key).append('=').append(ev).append('\n');
                }
            }
        }
        b.append('\n');

        P4Cli.Tagged infoRes = cli.tagged("info");
        P4Data.ClientInfo info = P4Data.parseInfo(infoRes);
        b.append("[5] p4 info (the actual connect attempt) -> exit ").append(infoRes.raw().code()).append('\n');
        if (info.error() != null) {
            b.append("    raw output:\n");
            for (String line : infoRes.raw().text().strip().split("\n")) b.append("      ").append(line).append('\n');
            String h = hint(info.error());
            b.append("    hint: ").append(h).append("\n\n");
            failing.add("[5] `p4 info` failed — " + h);
        } else {
            reachable = true;
            String clientShown = info.clientKnown() ? info.clientName() : P4Data.requestedClient(cli, info);
            b.append("    server: ").append(info.serverAddress()).append("  (").append(info.serverVersion()).append(")\n")
             .append("    user:   ").append(info.userName()).append('\n')
             .append("    client: ").append(clientShown).append(info.clientKnown() ? "" : "  <-- DOES NOT EXIST on this server").append('\n')
             .append("    host:   ").append(info.clientHost()).append('\n');
            if (!info.clientKnown()) {
                failing.add("[5] client '" + clientShown + "' does not exist on the server — set P4CLIENT to one of "
                        + "your workspaces (`" + clientsCommand(info.userName()) + "`) or create it");
            } else {
                b.append("    root:   ").append(info.clientRoot()).append('\n');
                if (!isUnder(cli.workdir(), info.clientRoot())) {
                    b.append("    WARNING: the workspace dir is outside this client's root — local paths will not map.\n");
                }
                P4Cli.Tagged spec = cli.tagged("client", "-o");
                String host = spec.records().isEmpty() ? "" : spec.records().get(0).getOrDefault("Host", "");
                if (!host.isBlank() && !host.equalsIgnoreCase(info.clientHost())) {
                    failing.add("[5] client '" + info.clientName() + "' is locked to host '" + host + "', this machine is '"
                            + info.clientHost() + "' — use a client created on this machine, or clear its Host: field");
                }
            }
            b.append('\n');
        }

        if (reachable) {
            P4Cli.Tagged login = cli.tagged("login", "-s");
            b.append("[6] p4 login -s (authentication state) -> exit ").append(login.raw().code()).append('\n');
            if (login.error() != null) {
                b.append("      ").append(login.error()).append('\n');
                failing.add("[6] not authenticated — " + hint(login.error()));
            } else {
                b.append("      ok").append(login.records().isEmpty() ? "" : " (" + login.records().get(0) + ")").append('\n');
            }
            b.append('\n');
        }

        Verdict verdict = !reachable ? Verdict.NOT_CONNECTED : failing.isEmpty() ? Verdict.READY : Verdict.NOT_READY;
        String first = failing.isEmpty() ? null : failing.get(0);
        b.append("VERDICT: ").append(switch (verdict) {
            case READY -> "READY — connected, client exists, authenticated";
            case NOT_READY -> "NOT READY — the server answers, but first failing step " + first;
            case NOT_CONNECTED -> "NOT CONNECTED — first failing step " + (first != null ? first : "(unknown)");
        }).append('\n');
        return new Report(b.toString(), verdict, first);
    }

    /** The command that lists the user's clients; p4 info says "*unknown*" for a user the server does not have. */
    static String clientsCommand(String user) {
        return user == null || user.isBlank() || user.equals("*unknown*") ? "p4 clients" : "p4 clients -u " + user;
    }

    /** Case- and separator-insensitive "dir is inside root" (p4 prints roots with '/' on Windows). */
    static boolean isUnder(String dir, String root) {
        String d = dir.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        String r = root.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        if (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return d.equals(r) || d.startsWith(r + "/");
    }

    /** Targeted hints mapped from p4's (or the OS's) own error text. Order matters: most specific first. */
    static String hint(String output) {
        String t = output.toLowerCase(java.util.Locale.ROOT);
        // before the executable check: a missing cwd is CreateProcess error=267, which also says "cannot run program"
        if (t.contains("workspace dir does not exist") || MISSING_DIR.matcher(t).find() || t.contains("directory name is invalid"))
            return "set 'workspace dir' to an existing directory inside your Perforce workspace";
        if (t.contains("cannot run program") || MISSING_EXE.matcher(t).find() || t.contains("cannot find the file specified")
                || t.contains("no such file or directory") || t.contains("not recognized"))
            return "the p4 executable itself was not found — set the full path in 'p4 executable' (e.g. C:\\Program Files\\Perforce\\p4.exe)";
        if (t.contains("authenticity of") || t.contains("p4 trust") || t.contains("fingerprint"))
            return "SSL server not trusted yet — confirm the fingerprint with your admin, then run `p4 trust` once";
        if (t.contains("no such host is known") || t.contains("unknown host") || t.contains("could not find host")
                || t.contains("name or service not known") || t.contains("nodename nor servname"))
            return "the P4PORT host name does not resolve — check the spelling, DNS or VPN";
        if (t.contains("connect to server failed") || t.contains("failed to connect") || t.contains("connection refused")
                || t.contains("wsaeconnrefused"))
            return "check P4PORT — host:port is unreachable (server down, wrong port, VPN or firewall)";
        if (t.contains("timed out") || t.contains("timeout"))
            return "connection timed out — server busy or unreachable; try the same command in a terminal";
        if (t.contains("can only be used from host"))
            return "this client is locked to another machine (Host: field) — use a client created on this machine";
        if (t.contains("use 'client' command to create it") || t.contains("client unknown"))
            return "P4CLIENT names a client that does not exist on this server";
        if (t.contains("password") || t.contains("ticket") || t.contains("session has expired"))
            return "authentication problem — run `p4 login`";
        return "run the same command in a terminal to compare";
    }

    /** CLI driver: java -Dp4.executable=... p4gate.P4Connect [workdir]; exit 0 = READY, 2 = not. */
    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : System.getProperty("user.dir");
        Report r = diagnose(new P4Cli(System.getProperty("p4.executable", "p4"), dir, Map.of()));
        System.out.print(r.text());
        System.exit(r.verdict() == Verdict.READY ? 0 : 2);
    }
}
