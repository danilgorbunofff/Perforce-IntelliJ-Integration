package p4gate;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.treeStructure.Tree;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Changelists tab: pending tree (this client only), submitted index (this client's view), and every operation.
 *  Rules: p4 runs only in platform background tasks; Swing is touched only on the EDT; mutating operations are
 *  exclusive per project; anything that can discard work names the files and asks first. */
public final class P4Panel {
    private final P4Project service;
    private final Tree tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode("no data yet — press Refresh")));
    private final DefaultTableModel submittedModel =
            new DefaultTableModel(new Object[]{"CL", "date", "user", "client", "description"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
                @Override public Class<?> getColumnClass(int c) { return c == 0 ? Long.class : String.class; }
            };
    private final JBTable submittedTable = new JBTable(submittedModel);
    private final JLabel streamLabel = new JLabel(" ");
    private final JTextArea status = new JTextArea(10, 70);
    private final AtomicLong generation = new AtomicLong(); // only the newest refresh may update the UI

    public P4Panel(P4Project service) {
        this.service = service;
    }

    public JComponent root() {
        submittedTable.setRowSorter(new TableRowSorter<>(submittedModel)); // header click sorts (CL numeric)
        JPanel changelistRow = row(
                button("Refresh", this::refresh),
                button("Submit…", this::submitSelected),
                button("Shelve", this::shelveSelected),
                button("Revert (keep files)", this::revertKeepSelected),
                button("Revert (discard edits)…", this::revertDiscardSelected));
        JPanel fileRow = row(
                button("Edit current file", () -> openCurrentFile("edit")),
                button("Add current file", () -> openCurrentFile("add")),
                button("Diff", this::diffSelected),
                button("Annotate", this::annotateSelected),
                button("Ignore file…", this::ignoreSelected),
                button("Accept theirs…", () -> resolveSelected("-at")),
                button("Accept yours…", () -> resolveSelected("-ay")));
        JPanel workspaceRow = row(
                button("Sync + auto-merge", this::syncAutoMerge),
                button("Reconcile…", this::reconcile),
                button("p4 info", this::showInfo));
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(changelistRow);
        top.add(fileRow);
        top.add(workspaceRow);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JBScrollPane(tree), new JBScrollPane(submittedTable));
        split.setResizeWeight(0.6);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(top, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(streamLabel, BorderLayout.NORTH);
        status.setEditable(false);
        south.add(new JBScrollPane(status), BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    private static JPanel row(JButton... buttons) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (JButton b : buttons) p.add(b);
        return p;
    }

    private static JButton button(String label, Runnable action) {
        JButton b = new JButton(label);
        b.addActionListener(e -> action.run()); // EDT: bodies only read Swing state, ask the user, and queue background work
        return b;
    }

    // ---------------------------------------------------------------- refresh

    void refresh() {
        long gen = generation.incrementAndGet();
        P4Cli cli = service.cli();
        service.background("Perforce: refresh", true, false, indicator -> {
            P4Data.ClientInfo info = P4Data.info(cli);
            if (info.error() != null || !info.clientKnown()) {
                String why = info.error() != null
                        ? "cannot reach the server: " + info.error()
                        : "client '" + P4Data.requestedClient(cli, info) + "' does not exist on the server";
                uiIfCurrent(gen, () -> {
                    tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("NOT READY — " + why)));
                    submittedModel.setRowCount(0);
                    streamLabel.setText(" ");
                    status.setText("Refresh failed: " + why + "\nOpen the Connection tab and press 'Save & run diagnosis'.");
                });
                return;
            }
            P4Data.Listing<P4Data.Change> pending = P4Data.pendingChanges(cli, info.clientName());
            P4Data.Listing<P4Data.SubmittedChange> index = P4Data.submittedIndex(cli, info.clientName(), 200);
            P4Data.Listing<String> stream = P4Data.clientStream(cli);
            uiIfCurrent(gen, () -> {
                refreshTree(info.clientName(), pending);
                refreshIndex(index.items());
                streamLabel.setText(stream.error() != null ? "stream: unknown (" + stream.error() + ")"
                        : stream.items().isEmpty() ? "client " + info.clientName() + " — classic client, no stream"
                        : "client " + info.clientName() + " on stream " + stream.items().get(0));
                List<String> problems = new ArrayList<>();
                if (pending.error() != null) problems.add("pending changelists: " + pending.error());
                if (index.error() != null) problems.add("submitted index: " + index.error());
                if (!problems.isEmpty()) status.setText("Refresh incomplete:\n  " + String.join("\n  ", problems));
            });
        });
    }

    private void uiIfCurrent(long gen, Runnable r) {
        service.ui(() -> {
            if (gen == generation.get()) r.run();
        });
    }

    private void refreshTree(String client, P4Data.Listing<P4Data.Change> pending) {
        String label = pending.error() != null
                ? "pending changelists of " + client + " — FAILED: " + pending.error()
                : "pending changelists of " + client + " (" + (pending.items().size() - 1) + " numbered + default)";
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(label);
        for (P4Data.Change c : pending.items()) {
            DefaultMutableTreeNode cn = new DefaultMutableTreeNode(c);
            for (P4Data.OpenedFile f : c.files()) cn.add(new DefaultMutableTreeNode(f));
            root.add(cn);
        }
        tree.setModel(new DefaultTreeModel(root));
    }

    private void refreshIndex(List<P4Data.SubmittedChange> index) {
        submittedModel.setRowCount(0);
        for (P4Data.SubmittedChange sc : index) {
            submittedModel.addRow(new Object[]{sc.id(), sc.date(), sc.user(), sc.client(), sc.desc()});
        }
    }

    private void showInfo() {
        P4Cli cli = service.cli();
        service.background("Perforce: p4 info", true, false, indicator -> {
            P4Cli.Result r = cli.run("info");
            service.ui(() -> status.setText(r.text()));
        });
    }

    // ---------------------------------------------------------------- selection helpers (EDT)

    private Object selectedObject() {
        TreePath path = tree.getSelectionPath();
        return path == null ? null : ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    }

    private P4Data.Change selectedChange(boolean allowDefault) {
        Object o = selectedObject();
        if (o instanceof P4Data.Change c && (allowDefault || c.id() != 0)) return c;
        status.setText(allowDefault ? "select a changelist first" : "select a numbered changelist (not the default)");
        return null;
    }

    private P4Data.OpenedFile selectedFile() {
        Object o = selectedObject();
        if (o instanceof P4Data.OpenedFile f) return f;
        status.setText("select a file under a changelist first");
        return null;
    }

    private VirtualFile currentEditorFile() {
        VirtualFile[] files = FileEditorManager.getInstance(service.project()).getSelectedFiles();
        if (files.length == 0 || !files[0].isInLocalFileSystem()) {
            status.setText("open the file in an editor first");
            return null;
        }
        return files[0];
    }

    private boolean confirm(String title, String message, String yes) {
        return Messages.showYesNoDialog(service.project(), message, title, yes, "Cancel", Messages.getWarningIcon()) == Messages.YES;
    }

    /** Exclusive background operation followed by a refresh. */
    private void mutate(String title, boolean cancellable, Consumer<ProgressIndicator> work) {
        boolean started = service.background(title, cancellable, true, indicator -> {
            work.accept(indicator);
            service.ui(this::refresh);
        });
        if (!started) status.setText("another Perforce operation is still running — wait for it to finish");
    }

    private void show(String op, P4Cli.Result r) {
        String text = op + " -> " + (r.ok() ? "OK" : "FAILED (" + r.code() + ")") + "\n" + r.text();
        service.ui(() -> status.setText(text));
    }

    private static String clArg(P4Data.Change c) {
        return c.id() == 0 ? "default" : String.valueOf(c.id());
    }

    private static String fileList(List<P4Data.OpenedFile> files, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < files.size() && i < max; i++) b.append("\n  ").append(files.get(i));
        if (files.size() > max) b.append("\n  … and ").append(files.size() - max).append(" more");
        return b.toString();
    }

    // ---------------------------------------------------------------- changelist operations

    private void submitSelected() {
        P4Data.Change c = selectedChange(false);
        if (c == null || !confirm("Submit", "Submit change " + c.id() + " '" + c.desc() + "' (" + c.files().size()
                + " files)?" + fileList(c.files(), 15), "Submit")) {
            return;
        }
        P4Cli cli = service.cli();
        // not cancellable: killing p4 half-way through a submit leaves a locked, half-transferred changelist
        mutate("Perforce: submit " + c.id(), false, ind -> show("submit change " + c.id(),
                cli.run(null, () -> false, "submit", "-c", String.valueOf(c.id()))));
    }

    private void shelveSelected() {
        P4Data.Change c = selectedChange(false);
        if (c == null) return;
        P4Cli cli = service.cli();
        mutate("Perforce: shelve " + c.id(), false, ind -> show("shelve change " + c.id(),
                cli.run(null, () -> false, "shelve", "-c", String.valueOf(c.id()))));
    }

    /** `revert -k`: clears the open state, leaves the files on disk as they are (they become unopened local edits). */
    private void revertKeepSelected() {
        P4Data.Change c = selectedChange(true);
        if (c == null) return;
        P4Cli cli = service.cli();
        mutate("Perforce: revert -k", true, ind -> show("revert -k change " + clArg(c),
                cli.run(null, ind::isCanceled, "revert", "-k", "-c", clArg(c), "//...")));
    }

    private void revertDiscardSelected() {
        P4Data.Change c = selectedChange(true);
        if (c == null) return;
        if (c.files().isEmpty()) {
            status.setText("change " + clArg(c) + " has no open files");
            return;
        }
        if (!confirm("Revert and discard", "Revert change " + clArg(c) + " and DISCARD your local edits in "
                + c.files().size() + " files? Files opened for add stay on disk; everything else is restored "
                + "to the depot revision. This cannot be undone." + fileList(c.files(), 15), "Discard edits")) {
            return;
        }
        P4Cli cli = service.cli();
        mutate("Perforce: revert", false, ind -> show("revert change " + clArg(c),
                cli.run(null, () -> false, "revert", "-c", clArg(c), "//...")));
    }

    // ---------------------------------------------------------------- file operations

    /** `p4 edit` / `p4 add` on the file in the active editor — the step that starts every change. */
    private void openCurrentFile(String command) {
        VirtualFile vf = currentEditorFile();
        if (vf == null) return;
        String path = new File(vf.getPath()).getPath(); // platform separators
        P4Cli cli = service.cli();
        mutate("Perforce: " + command, false, ind -> {
            P4Cli.Result r = cli.run(command, path);
            show(command + " " + path, r);
            VfsUtil.markDirtyAndRefresh(true, false, false, vf); // p4 edit flips the read-only bit
        });
    }

    private void diffSelected() {
        P4Data.OpenedFile f = selectedFile();
        if (f == null) return;
        P4Cli cli = service.cli();
        service.background("Perforce: diff", true, false, ind -> {
            P4Cli.Result r = cli.run(null, ind::isCanceled, "diff", f.depotFile());
            service.ui(() -> status.setText("diff " + f.depotFile() + "\n" + r.text()));
        });
    }

    private void annotateSelected() {
        P4Data.OpenedFile f = selectedFile();
        if (f == null) return;
        if (f.action().equals("add") || f.action().equals("branch")) {
            status.setText(f.depotFile() + " is opened for " + f.action() + " — it has no submitted history to annotate yet");
            return;
        }
        P4Cli cli = service.cli();
        service.background("Perforce: annotate", true, false, ind -> {
            P4Cli.Result r = cli.run(null, ind::isCanceled, "annotate", f.depotFile());
            service.ui(() -> status.setText("annotate " + f.depotFile() + "\n" + r.text()));
        });
    }

    /** Accept theirs / yours for the SELECTED file only, after checking it needs resolving and confirming. */
    private void resolveSelected(String flag) {
        P4Data.OpenedFile f = selectedFile();
        if (f == null) return;
        P4Cli cli = service.cli();
        service.background("Perforce: resolve preview", true, false, ind -> {
            P4Cli.Tagged preview = cli.tagged("resolve", "-n", f.depotFile());
            service.ui(() -> {
                if (preview.error() != null) {
                    status.setText("resolve preview failed: " + preview.error());
                    return;
                }
                if (preview.records().isEmpty()) {
                    status.setText(f.depotFile() + " has nothing to resolve");
                    return;
                }
                boolean theirs = flag.equals("-at");
                String msg = theirs
                        ? "Accept THEIRS for " + f.depotFile() + "?\n\nYour local edits in this file will be DISCARDED and "
                          + "replaced by the depot revision. This cannot be undone."
                        : "Accept YOURS for " + f.depotFile() + "?\n\nThe incoming depot changes to this file are ignored; "
                          + "your next submit of it overwrites them.";
                if (!confirm(theirs ? "Accept theirs" : "Accept yours", msg, theirs ? "Discard my edits" : "Keep mine")) {
                    return;
                }
                mutate("Perforce: resolve " + flag, false, ind2 -> show("resolve " + flag + " " + f.depotFile(),
                        cli.run("resolve", flag, f.depotFile())));
            });
        });
    }

    /**
     * Ignore rules only stop files from being ADDED. A file already in the depot cannot be "ignored";
     * a file opened for add must first stop being opened (revert -k keeps it on disk).
     */
    private void ignoreSelected() {
        Object o = selectedObject();
        P4Cli cli = service.cli();
        if (o instanceof P4Data.OpenedFile f) {
            if (!f.action().equals("add")) {
                status.setText(f.depotFile() + " is opened for " + f.action() + " — it is already in the depot.\n"
                        + "Ignore rules only stop files from being added; they cannot untrack an existing file.");
                return;
            }
            if (!confirm("Ignore file", "Stop adding " + f.depotFile() + " and ignore it from now on?\n\n"
                    + "The add is reverted with -k (the file stays on disk) and its name is written to the ignore file.", "Ignore")) {
                return;
            }
            mutate("Perforce: ignore", false, ind -> {
                String local = P4Data.where(cli, f.depotFile());
                if (local == null) {
                    service.ui(() -> status.setText("p4 where found no local path for " + f.depotFile()));
                    return;
                }
                P4Cli.Result revert = cli.run("revert", "-k", f.depotFile());
                if (!revert.ok()) {
                    show("revert -k " + f.depotFile(), revert);
                    return;
                }
                String result = writeIgnoreRule(cli, local);
                service.ui(() -> status.setText(result));
            });
            return;
        }
        VirtualFile vf = currentEditorFile();
        if (vf == null) return;
        String local = new File(vf.getPath()).getPath();
        mutate("Perforce: ignore", false, ind -> {
            P4Cli.Tagged fstat = cli.tagged("fstat", local);
            boolean known = fstat.records().stream().anyMatch(r -> r.containsKey("headRev") || r.containsKey("action"));
            if (known) {
                service.ui(() -> status.setText(local + " is already tracked or opened in Perforce — ignore rules cannot untrack it.\n"
                        + "(If it is opened for add, select it in the tree and use Ignore file… there.)"));
                return;
            }
            String result = writeIgnoreRule(cli, local);
            service.ui(() -> status.setText(result));
        });
    }

    /** Appends the file's name to the ignore file p4 actually uses (P4IGNORE as p4 resolves it; default p4ignore.txt). */
    static String writeIgnoreRule(P4Cli cli, String local) {
        if (!P4Data.ignored(cli, List.of(local)).isEmpty()) return local + "\nis already ignored by p4";
        String setting = P4Data.effectiveSetting(cli, "P4IGNORE");
        String name = "p4ignore.txt"; // r25.2 default when P4IGNORE is unset (p4ignore.txt and .p4ignore are both honored)
        if (setting != null && !setting.isBlank()) {
            name = setting.split(File.pathSeparator)[0].strip(); // P4IGNORE may list several files
        }
        Path file = Path.of(local);
        Path ignoreFile = Path.of(name).isAbsolute() ? Path.of(name) : file.getParent().resolve(name);
        String baseName = file.getFileName().toString();
        try {
            List<String> lines = Files.exists(ignoreFile) ? Files.readAllLines(ignoreFile, StandardCharsets.UTF_8) : List.of();
            if (!lines.contains(baseName)) {
                List<String> updated = new ArrayList<>(lines);
                updated.add(baseName);
                Files.write(ignoreFile, (String.join("\n", updated) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            return "cannot write " + ignoreFile + ": " + e;
        }
        boolean ok = !P4Data.ignored(cli, List.of(local)).isEmpty();
        return (ok ? "now ignored: " : "IGNORE FAILED (p4 still does not ignore it): ") + local + "\n(rule '" + baseName
                + "' in " + ignoreFile + (setting == null ? ", P4IGNORE unset" : ", P4IGNORE=" + setting) + ")";
    }

    // ---------------------------------------------------------------- workspace operations

    /** sync the client, then `resolve -am`: merges only files with no conflicting chunks; conflicts are listed, not touched. */
    private void syncAutoMerge() {
        P4Cli cli = service.cli();
        mutate("Perforce: sync", true, ind -> {
            P4Cli.Result sync = cli.run(null, ind::isCanceled, "sync");
            P4Cli.Result merge = cli.run(null, ind::isCanceled, "resolve", "-am");
            service.ui(() -> status.setText("sync:\n" + sync.text() + "\n\nresolve -am (auto-merge, conflicts left alone):\n"
                    + merge.text() + "\nFor a file still listed as conflicting: select it, then Accept theirs… / Accept yours…"));
        });
    }

    /** Preview with `reconcile -n`, show what would be opened, then open exactly those files. */
    private void reconcile() {
        P4Cli cli = service.cli();
        service.background("Perforce: reconcile preview", true, false, ind -> {
            P4Cli.Tagged preview = cli.tagged(null, ind::isCanceled, "reconcile", "-n", "//...");
            List<Map<String, String>> recs = preview.records();
            service.ui(() -> {
                if (preview.error() != null && recs.isEmpty()) {
                    status.setText("reconcile preview failed: " + preview.error());
                    return;
                }
                if (recs.isEmpty()) {
                    status.setText("reconcile: the workspace matches the depot (ignored files are skipped)");
                    return;
                }
                StringBuilder list = new StringBuilder();
                for (int i = 0; i < recs.size() && i < 20; i++) {
                    list.append("\n  ").append(recs.get(i).getOrDefault("action", "?")).append("  ")
                        .append(recs.get(i).getOrDefault("depotFile", "?"));
                }
                if (recs.size() > 20) list.append("\n  … and ").append(recs.size() - 20).append(" more");
                if (!confirm("Reconcile", "Open these " + recs.size() + " files in the default changelist?" + list, "Open files")) {
                    status.setText("reconcile preview (nothing opened):" + list);
                    return;
                }
                mutate("Perforce: reconcile", true, ind2 -> show("reconcile " + recs.size() + " files",
                        reconcileExactly(cli, recs, ind2::isCanceled)));
            });
        });
    }

    /** Opens exactly the previewed files via an argument file (`-x`), so long lists never hit command-line limits. */
    static P4Cli.Result reconcileExactly(P4Cli cli, List<Map<String, String>> recs, BooleanSupplier cancelled) {
        Path args = null;
        try {
            args = Files.createTempFile("p4gate-reconcile", ".txt");
            List<String> files = new ArrayList<>();
            for (Map<String, String> r : recs) files.add(r.getOrDefault("clientFile", r.get("depotFile")));
            Files.write(args, files, StandardCharsets.UTF_8);
            return cli.run(null, cancelled, "-x", args.toString(), "reconcile");
        } catch (IOException e) {
            return new P4Cli.Result(-1, "", "cannot write argument file: " + e);
        } finally {
            if (args != null) {
                try {
                    Files.deleteIfExists(args);
                } catch (IOException ignored) {
                    // temp dir cleanup will get it
                }
            }
        }
    }
}
