package p4gate;

import java.awt.*;
import javax.swing.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;

/**
 * The "why can't I connect" panel (README 5.1, rows 1-2 — the #1 reason users bounce):
 * shows the exact command, every resolved setting source (process env, `p4 set`, P4CONFIG),
 * the raw p4 output, and a verdict that names the first failing step.
 * One click imports a known-good environment from a found P4CONFIG file.
 */
public final class P4Connect {
    /** Full diagnosis result: the rendered report and whether the server was reachable. */
    public record Report(String text, boolean connected) { }

    private final javax.swing.JTextArea reportArea = new javax.swing.JTextArea(24, 90);
    private final javax.swing.JTextField wsDir =
            new javax.swing.JTextField(System.getProperty("user.dir"), 40);

    public javax.swing.JComponent root() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("workspace dir:"));
        top.add(wsDir);
        top.add(button("Run diagnosis", this::runDiagnosis));
        top.add(button("Import env from P4CONFIG", this::importConfig));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(top, BorderLayout.NORTH);
        reportArea.setEditable(false);
        reportArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JScrollPane(reportArea), BorderLayout.CENTER);
        return panel;
    }

    private static javax.swing.JButton button(String label, Runnable action) {
        javax.swing.JButton b = new javax.swing.JButton(label);
        b.addActionListener(e -> action.run()); // listener body runs p4 work off the EDT itself
        return b;
    }

    private void runDiagnosis() {
        reportArea.setText("running diagnosis...");
        String dir = wsDir.getText().trim();
        new Thread(() -> {
            Report r = diagnose(dir);
            javax.swing.SwingUtilities.invokeLater(() -> reportArea.setText(r.text()));
        }).start();
    }

    private void importConfig() {
        reportArea.setText("looking for a P4CONFIG file...");
        String dir = wsDir.getText().trim();
        new Thread(() -> {
            StringBuilder b = new StringBuilder();
            List<String> applied = List.of();
            boolean ok = false;
            try {
                SortedMap<String, String> env = P4Env.processEnv();
                SortedMap<String, String> set = P4Env.parseSet(P4Env.p4Set());
                Optional<Path> f = P4Env.findConfig(Path.of(dir), P4Env.configNames(env, set));
                if (f.isEmpty()) {
                    b.append("no P4CONFIG file found above ").append(dir)
                     .append("\nlooked for: ").append(P4Env.configNames(env, set));
                } else {
                    SortedMap<String, String> cfg = P4Env.parseConfig(f.get());
                    java.util.Map<String, String> override = new java.util.HashMap<>();
                    for (String key : List.of("P4PORT", "P4USER", "P4CLIENT")) {
                        if (cfg.containsKey(key)) override.put(key, cfg.get(key));
                    }
                    P4Cli.setEnvOverride(override);
                    applied = new ArrayList<>(override.keySet());
                    ok = !override.isEmpty();
                    b.append("imported from ").append(f.get()).append(":\n");
                    for (String key : applied) b.append("  ").append(key).append('=').append(override.get(key)).append('\n');
                    b.append("\nsubsequent p4 commands use these values.");
                }
            } catch (Exception e) {
                b.append("import failed: ").append(e);
            }
            javax.swing.SwingUtilities.invokeLater(() -> reportArea.setText(b.toString()));
        }).start();
    }

    /** Runs every check and renders the report. Also drives the CLI verification path. */
    public static Report diagnose(String workspaceDir) {
        StringBuilder b = new StringBuilder();
        b.append("== p4 connect diagnosis ==\n\n");
        List<String> failing = new ArrayList<>();

        b.append("[1] p4 executable: ").append(P4Cli.EXECUTABLE).append('\n');
        b.append("    run as: \"").append(P4Cli.EXECUTABLE).append(" info\"\n\n");

        SortedMap<String, String> env = P4Env.processEnv();
        b.append("[2] this process's environment:\n");
        if (env.isEmpty()) {
            b.append("    (no P4* variables set)\n");
        } else {
            for (var e : env.entrySet()) b.append("    ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        b.append('\n');

        P4Cli.Result setRes = P4Cli.run("set");
        SortedMap<String, String> set = P4Env.parseSet(setRes.text());
        b.append("[3] `p4 set` (OS-level defaults):\n");
        if (!setRes.ok()) {
            b.append("    FAILED: ").append(setRes.text().trim()).append('\n');
        } else if (set.isEmpty()) {
            b.append("    (nothing set)\n");
        } else {
            for (var e : set.entrySet()) b.append("    ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        b.append('\n');

        b.append("[4] P4CONFIG file lookup from ").append(workspaceDir).append(":\n");
        String cfgVar = env.get("P4CONFIG");
        if (cfgVar == null) cfgVar = set.get("P4CONFIG");
        boolean p4ReadsConfig = cfgVar != null && !cfgVar.isBlank();
        if (!p4ReadsConfig) {
            b.append("    P4CONFIG is not set (neither env nor `p4 set`) — p4 reads NO config file at all;\n")
             .append("    it falls back to built-in defaults (observed on this Windows build: port perforce:1666).\n");
        }
        List<String> names = P4Env.configNames(env, set);
        Optional<Path> cfgFile = Optional.empty();
        SortedMap<String, String> cfg = new java.util.TreeMap<>();
        try {
            cfgFile = P4Env.findConfig(Path.of(workspaceDir), names);
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
                for (var e : cfg.entrySet()) b.append("      ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
            } catch (Exception e) {
                b.append("      unreadable: ").append(e).append('\n');
            }
        }
        b.append('\n');

        if (!P4Cli.envOverride().isEmpty()) {
            b.append("    plugin env override active:\n");
            for (var e : P4Cli.envOverride().entrySet()) {
                b.append("      ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            b.append('\n');
        }

        // p4 precedence: process env > P4CONFIG file > OS-level (`p4 set`). Conflicts are the classic silent misconfiguration.
        if (p4ReadsConfig) {
            for (String key : List.of("P4PORT", "P4USER", "P4CLIENT")) {
                String ev = env.get(key);
                String cv = cfg.get(key);
                if (ev != null && cv != null && !ev.equals(cv)) {
                    b.append("    conflict: the config file's ").append(key).append('=').append(cv)
                     .append(" overrides the env's ").append(key).append('=').append(ev)
                     .append(" (p4 precedence: P4CONFIG file > env/`p4 set` > built-in defaults, verified against p4.exe)\n");
                }
            }
        }
        b.append('\n');

        P4Cli.Result info = P4Cli.run("info");
        b.append("[5] p4 info (the actual connect attempt) -> exit ").append(info.code()).append('\n');
        String raw = info.text().strip();
        String[] infoLines = raw.isBlank() ? new String[]{"(empty)"} : raw.split("\n");
        b.append("    raw output:\n");
        for (String line : infoLines) {
            b.append("      ").append(line).append('\n');
        }
        if (!info.ok()) {
            failing.add("[5] `p4 info` failed — " + hint(info.text()));
            b.append("    hint: ").append(hint(info.text())).append("\n\n");
        } else {
            b.append('\n');
        }

        P4Cli.Result login = P4Cli.run("login", "-s");
        b.append("[6] p4 login -s (authentication state) -> exit ").append(login.code()).append('\n');
        String loginOut = login.text().strip();
        String[] loginLines = loginOut.isBlank() ? new String[]{"(empty)"} : loginOut.split("\n");
        for (String line : loginLines) {
            b.append("      ").append(line).append('\n');
        }
        if (!login.ok()) {
            // informational, not fatal: a reachable server can still reject auth
            b.append(info.ok()
                    ? "    (informational: server reachable but no valid ticket — p4 login to fix)\n\n"
                    : "\n");
        } else {
            b.append('\n');
        }

        boolean connected = info.ok();
        b.append("VERDICT: ").append(connected
                ? "connected"
                : "NOT connected — first failing step " + (!failing.isEmpty() ? failing.get(0) : "(unknown)"));
        return new Report(b.toString(), connected);
    }

    /** Targeted, non-generic hints mapped from p4's own error text. */
    static String hint(String p4Output) {
        String t = p4Output.toLowerCase();
        if (t.contains("connect to server failed") || t.contains("failed to connect"))
            return "check P4PORT — host:port is unreachable (server down, wrong port, or firewall)";
        if (t.contains("unknown host") || t.contains("could not find host"))
            return "P4PORT host does not resolve — check DNS / hosts file";
        if (t.contains("timed out") || t.contains("timeout"))
            return "connection timed out — server busy or unreachable; try the same command in a terminal";
        if (t.contains("password") || t.contains("ticket"))
            return "authentication problem — run `p4 login`";
        if (t.contains("no such file") || t.contains("not recognized"))
            return "the p4 executable itself was not found — fix the PATH or -Dp4.executable";
        return "run the same command in a terminal to compare";
    }

    /** CLI driver for verification: prints the report; exit 0 = connected, 2 = not. */
    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : System.getProperty("user.dir");
        Report r = diagnose(dir);
        System.out.print(r.text());
        System.exit(r.connected() ? 0 : 2);
    }
}
