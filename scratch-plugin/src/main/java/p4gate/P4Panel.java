package p4gate;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
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
    private final Tree tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode("No data yet — press Refresh")));
    private final DefaultTableModel submittedModel =
            new DefaultTableModel(new Object[]{"CL", "date", "user", "client", "description"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
                @Override public Class<?> getColumnClass(int c) { return c == 0 ? Long.class : String.class; }
            };
    private final JBTable submittedTable = new JBTable(submittedModel);
    private final JBLabel streamLabel = new JBLabel(" ");
    private final JTextArea status = new JTextArea(8, 70);
    private final AtomicLong generation = new AtomicLong(); // only the newest refresh may update the UI

    public P4Panel(P4Project service) {
        this.service = service;
    }

    public JComponent root() {
        submittedTable.setRowSorter(new TableRowSorter<>(submittedModel)); // header click sorts (CL numeric)
        submittedTable.setStriped(true);
        submittedTable.setShowGrid(false);
        int[] widths = {50, 125, 70, 120}; // CL, date, user, client: never truncated; the description takes the rest
        for (int i = 0; i < widths.length; i++) {
            TableColumn col = submittedTable.getColumnModel().getColumn(i);
            col.setMinWidth(widths[i]);
            col.setPreferredWidth(widths[i]);
        }
        submittedTable.getColumnModel().getColumn(4).setPreferredWidth(300);
        tree.setCellRenderer(new NodeRenderer());
        tree.setShowsRootHandles(true);
        installContextMenu();

        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("P4Changelists", toolbarGroup(), true);
        toolbar.setTargetComponent(tree);
        toolbar.getComponent().setBorder(JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0));

        streamLabel.setForeground(UIUtil.getContextHelpForeground());
        streamLabel.setBorder(JBUI.Borders.empty(3, 8));

        status.setEditable(false);
        status.setLineWrap(true); // long p4 lines wrap instead of running off the edge
        status.setWrapStyleWord(true);
        status.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.label().getSize()));
        status.setBorder(JBUI.Borders.empty(4, 8));
        JBScrollPane output = new JBScrollPane(status);
        output.setBorder(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0));

        JBScrollPane table = new JBScrollPane(submittedTable);
        table.setBorder(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0));
        OnePixelSplitter lower = new OnePixelSplitter(true, 0.5f);
        lower.setFirstComponent(table);
        lower.setSecondComponent(output);
        OnePixelSplitter split = new OnePixelSplitter(true, 0.42f);
        split.setFirstComponent(new JBScrollPane(tree));
        split.setSecondComponent(lower);

        JPanel header = new JPanel(new BorderLayout());
        header.add(toolbar.getComponent(), BorderLayout.NORTH);
        header.add(streamLabel, BorderLayout.SOUTH);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(header, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    // ---------------------------------------------------------------- toolbar and menus

    private static AnAction act(String text, String description, Icon icon, Runnable run) {
        return new DumbAwareAction(text, description, icon) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                run.run(); // EDT: bodies only read Swing state, ask the user, and queue background work
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
    }

    private static DefaultActionGroup popup(String name, String description, Icon icon, AnAction... children) {
        DefaultActionGroup g = new DefaultActionGroup(name, true);
        g.getTemplatePresentation().setDescription(description);
        g.getTemplatePresentation().setIcon(icon);
        g.addAll(children);
        return g;
    }

    private final AnAction submitAction = act("Submit…", "Submit the selected changelist to the server",
            AllIcons.Actions.Commit, this::submitSelected);
    private final AnAction shelveAction = act("Shelve", "Shelve the selected changelist's files on the server",
            AllIcons.Vcs.ShelveSilent, this::shelveSelected);
    private final AnAction revertKeepAction = act("Revert (keep files)", "Give the files back to Perforce but keep your edits on disk",
            AllIcons.Actions.Undo, this::revertKeepSelected);
    private final AnAction revertDiscardAction = act("Revert (discard edits)…", "Restore the depot revision and DISCARD your local edits",
            AllIcons.Actions.Rollback, this::revertDiscardSelected);
    private final AnAction diffAction = act("Diff", "Diff the selected file against the depot revision you have",
            AllIcons.Actions.Diff, this::diffSelected);
    private final AnAction annotateAction = act("Annotate", "Show who changed each line (p4 annotate)",
            AllIcons.Actions.Annotate, this::annotateSelected);
    private final AnAction acceptTheirsAction = act("Accept theirs…", "Resolve the selected file with the depot's version",
            AllIcons.Vcs.Merge, () -> resolveSelected("-at"));
    private final AnAction acceptYoursAction = act("Accept yours…", "Resolve the selected file keeping your version",
            AllIcons.Vcs.Merge, () -> resolveSelected("-ay"));
    private final AnAction ignoreAction = act("Ignore file…", "Stop adding the selected file and ignore it from now on",
            AllIcons.Actions.Cancel, this::ignoreSelected);

    DefaultActionGroup toolbarGroup() { // package-private: the UI test checks what the toolbar offers
        // A toolbar button's tooltip shows only the action's text, so these names say what the icon does;
        // the dropdowns and the right-click menu keep the short names the user guide documents.
        DefaultActionGroup bar = new DefaultActionGroup();
        bar.add(act("Refresh", "Reload the changelists and the submitted history", AllIcons.Actions.Refresh, this::refresh));
        bar.addSeparator();
        bar.add(act("Submit Changelist…", "Submit the selected changelist to the server", AllIcons.Actions.Commit, this::submitSelected));
        bar.add(act("Shelve Changelist", "Shelve the selected changelist's files on the server", AllIcons.Vcs.ShelveSilent, this::shelveSelected));
        bar.add(popup("Revert Changelist", "Revert the selected changelist", AllIcons.Actions.Rollback, revertKeepAction, revertDiscardAction));
        bar.addSeparator();
        bar.add(act("Diff Against Depot", "Diff the selected file against the depot revision you have", AllIcons.Actions.Diff, this::diffSelected));
        bar.add(act("Annotate File", "Show who changed each line (p4 annotate)", AllIcons.Actions.Annotate, this::annotateSelected));
        bar.add(popup("Resolve Conflict", "Resolve the selected conflicting file", AllIcons.Vcs.Merge,
                acceptTheirsAction, acceptYoursAction));
        bar.addSeparator();
        bar.add(act("Sync + Auto-Merge", "Sync to the latest revisions and auto-merge (resolve -am); conflicts are left for you",
                AllIcons.Actions.CheckOut, this::syncAutoMerge));
        DefaultActionGroup more = popup("More Actions", "More Perforce actions", AllIcons.Actions.More,
                act("Edit current file", "Open the file in the editor for edit (p4 edit)", AllIcons.Actions.Edit, () -> openCurrentFile("edit")),
                act("Add current file", "Open the file in the editor for add (p4 add)", AllIcons.General.Add, () -> openCurrentFile("add")),
                ignoreAction);
        more.addSeparator();
        more.add(act("Reconcile…", "Find edits, adds and deletes made outside Perforce and open them", AllIcons.Actions.Find, this::reconcile));
        more.add(act("p4 info", "Show the server and client details", AllIcons.General.Information, this::showInfo));
        bar.add(more);
        return bar;
    }

    /** Right-click on a changelist or a file: only the actions that apply to what was clicked. */
    private void installContextMenu() {
        tree.addMouseListener(new PopupHandler() {
            @Override
            public void invokePopup(Component comp, int x, int y) {
                TreePath path = tree.getPathForLocation(x, y);
                if (path == null) return;
                tree.setSelectionPath(path);
                Object o = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                DefaultActionGroup g = new DefaultActionGroup();
                if (o instanceof P4Data.Change c) {
                    if (c.id() != 0) {
                        g.add(submitAction);
                        g.add(shelveAction);
                        g.addSeparator();
                    }
                    g.add(revertKeepAction);
                    g.add(revertDiscardAction);
                } else if (o instanceof P4Data.OpenedFile) {
                    g.add(diffAction);
                    g.add(annotateAction);
                    g.addSeparator();
                    g.add(acceptTheirsAction);
                    g.add(acceptYoursAction);
                    g.addSeparator();
                    g.add(ignoreAction);
                } else {
                    return;
                }
                ActionManager.getInstance().createActionPopupMenu("P4ChangelistsPopup", g).getComponent().show(comp, x, y);
            }
        });
    }

    /** Changelist rows: bold name, grey file count. File rows: file-type icon, name in the file-status colour
     *  (add green, edit blue, delete red), grey directory and the p4 action. */
    static final class NodeRenderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded,
                                          boolean leaf, int row, boolean hasFocus) {
            Object o = ((DefaultMutableTreeNode) value).getUserObject();
            if (o instanceof P4Data.Change c) {
                setIcon(AllIcons.Vcs.Changelist);
                append(c.id() == 0 ? "Default Changelist" : "Change " + c.id(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                if (c.id() != 0 && !c.desc().isBlank()) append("  " + c.desc());
                int n = c.files().size();
                append("  " + (n == 0 ? "empty" : n == 1 ? "1 file" : n + " files"), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            } else if (o instanceof P4Data.OpenedFile f) {
                String depot = f.depotFile();
                int slash = depot.lastIndexOf('/');
                String name = depot.substring(slash + 1);
                setIcon(FileTypeManager.getInstance().getFileTypeByFileName(name).getIcon());
                append(name, new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, statusColor(f.action())));
                String dir = folderBelowDepot(depot);
                if (!dir.isEmpty()) append("  " + dir, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append("  " + f.action(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            } else {
                append(String.valueOf(o), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            }
        }

        /** "//depot/sub dir/x.txt" -> "sub dir"; a file directly in the depot has no folder to show. */
        static String folderBelowDepot(String depotFile) {
            String path = depotFile.startsWith("//") ? depotFile.substring(2) : depotFile;
            int first = path.indexOf('/');
            int last = path.lastIndexOf('/');
            return first < 0 || last <= first ? "" : path.substring(first + 1, last);
        }

        static Color statusColor(String action) {
            FileStatus s = switch (action) {
                case "add", "branch", "move/add" -> FileStatus.ADDED;
                case "delete", "move/delete", "purge" -> FileStatus.DELETED;
                case "edit", "integrate" -> FileStatus.MODIFIED;
                default -> null;
            };
            return s == null ? null : s.getColor();
        }
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
                    tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("Not ready — " + why)));
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
                ? "Pending changelists — FAILED: " + pending.error()
                : "Pending changelists";
        // an action refreshes the tree; keep what the user had open and selected instead of resetting it
        java.util.Set<Long> expanded = new java.util.HashSet<>();
        Object selected = selectedObject();
        if (tree.getModel().getRoot() instanceof DefaultMutableTreeNode oldRoot) {
            for (int i = 0; i < oldRoot.getChildCount(); i++) {
                DefaultMutableTreeNode n = (DefaultMutableTreeNode) oldRoot.getChildAt(i);
                if (n.getUserObject() instanceof P4Data.Change c
                        && tree.isExpanded(new TreePath(n.getPath()))) {
                    expanded.add(c.id());
                }
            }
        }
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(label);
        for (P4Data.Change c : pending.items()) {
            DefaultMutableTreeNode cn = new DefaultMutableTreeNode(c);
            for (P4Data.OpenedFile f : c.files()) cn.add(new DefaultMutableTreeNode(f));
            root.add(cn);
        }
        tree.setModel(new DefaultTreeModel(root));
        TreePath toSelect = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode cn = (DefaultMutableTreeNode) root.getChildAt(i);
            P4Data.Change c = (P4Data.Change) cn.getUserObject();
            if (expanded.contains(c.id())) tree.expandPath(new TreePath(cn.getPath()));
            if (selected instanceof P4Data.Change sc && sc.id() == c.id()) toSelect = new TreePath(cn.getPath());
            for (int j = 0; j < cn.getChildCount(); j++) {
                DefaultMutableTreeNode fn = (DefaultMutableTreeNode) cn.getChildAt(j);
                if (selected instanceof P4Data.OpenedFile sf
                        && fn.getUserObject() instanceof P4Data.OpenedFile f
                        && f.depotFile().equals(sf.depotFile()) && f.change() == sf.change()) {
                    toSelect = new TreePath(fn.getPath());
                }
            }
        }
        if (toSelect != null) tree.setSelectionPath(toSelect);
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
            service.ui(() -> status.setText(clip(r.text())));
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

    /** Exclusive background operation, then a refresh of this tab AND of the IDE's Local Changes. */
    private void mutate(String title, boolean cancellable, Consumer<ProgressIndicator> work) {
        boolean started = service.background(title, cancellable, true, indicator -> {
            try {
                work.accept(indicator);
            } finally {
                service.ui(() -> {
                    service.vcsDirty();
                    refresh();
                });
            }
        });
        if (!started) status.setText("another Perforce operation is still running — wait for it to finish");
    }

    private void show(String op, P4Cli.Result r) {
        String text = op + " -> " + (r.ok() ? "OK" : "FAILED (" + r.code() + ")") + "\n" + clip(r.text());
        service.ui(() -> status.setText(text));
    }

    private static final int MAX_LINES = 500;

    /** p4 output for the status area, at most {@link #MAX_LINES} lines: a full sync prints one line per file, and
     *  handing megabytes to a text area would freeze the UI thread. */
    static String clip(String text) {
        int end = -1;
        for (int i = 0; i < MAX_LINES; i++) {
            end = text.indexOf('\n', end + 1);
            if (end < 0) return text;
        }
        long more = text.substring(end + 1).lines().count();
        return more == 0 ? text : text.substring(0, end) + "\n… " + more + " more lines (run the command in a terminal for all of them)";
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
        mutate("Perforce: submit " + c.id(), false, ind -> show("submit change " + c.id(), submitConfirmed(cli, c)));
    }

    /**
     * `submit -c` of a changelist, only if it still holds exactly the files the user confirmed: the tree is a
     * snapshot of the last Refresh, and p4 submits whatever the changelist holds now.
     */
    static P4Cli.Result submitConfirmed(P4Cli cli, P4Data.Change c) {
        String changed = changedSinceRefresh(cli, c);
        if (changed != null) return new P4Cli.Result(-1, "", changed + " Nothing was submitted: Refresh and review it again.");
        return cli.run(null, () -> false, "submit", "-c", String.valueOf(c.id()));
    }

    /** Why the changelist no longer matches the confirmed snapshot, or null when it does. */
    private static String changedSinceRefresh(P4Cli cli, P4Data.Change c) {
        P4Cli.Tagged now = cli.tagged("opened", "-c", clArg(c));
        if (now.error() != null) return "Could not re-read change " + clArg(c) + ": " + now.error() + ".";
        java.util.Set<String> current = new java.util.HashSet<>();
        for (Map<String, String> r : now.records()) {
            if (r.get("depotFile") != null) current.add(r.get("depotFile"));
        }
        java.util.Set<String> confirmed = new java.util.HashSet<>();
        for (P4Data.OpenedFile f : c.files()) confirmed.add(f.depotFile());
        return current.equals(confirmed) ? null : "Change " + clArg(c) + " now holds " + current.size()
                + " files; you confirmed " + confirmed.size() + ".";
    }

    private void shelveSelected() {
        P4Data.Change c = selectedChange(false);
        if (c == null) return;
        P4Cli cli = service.cli();
        mutate("Perforce: shelve " + c.id(), false, ind -> {
            P4Cli.Result r = shelve(cli, c.id(), false);
            show("shelve change " + c.id(), r);
            if (alreadyShelved(r)) service.ui(() -> reshelve(c));
        });
    }

    /** `shelve -c` (or `shelve -f -c`, which replaces an existing shelf). Not cancellable: half a shelf is worse. */
    static P4Cli.Result shelve(P4Cli cli, long change, boolean replace) {
        return replace ? cli.run(null, () -> false, "shelve", "-f", "-c", String.valueOf(change))
                : cli.run(null, () -> false, "shelve", "-c", String.valueOf(change));
    }

    /** r25.2: "//depot/a.txt - already shelved, use -f to update." */
    static boolean alreadyShelved(P4Cli.Result r) {
        return !r.ok() && r.text().contains("use -f");
    }

    /** The change already has a shelf: replacing it discards what is shelved now, so ask before `shelve -f`. */
    private void reshelve(P4Data.Change c) {
        if (!confirm("Replace shelved files", "Change " + c.id() + " already has shelved files.\n\nReplace them with the "
                + "current open files? What is shelved now is overwritten.", "Replace shelf")) {
            return;
        }
        P4Cli cli = service.cli();
        mutate("Perforce: shelve -f " + c.id(), false, ind -> show("shelve -f change " + c.id(), shelve(cli, c.id(), true)));
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
        mutate("Perforce: revert", false, ind -> show("revert change " + clArg(c), revertConfirmed(cli, c).raw()));
    }

    /**
     * Discards edits in exactly the files the user confirmed, never `//...`: a file opened into the changelist after
     * the last Refresh (auto-checkout opens into the default one) was never shown, so it is never touched.
     */
    static P4Cli.Tagged revertConfirmed(P4Cli cli, P4Data.Change c) {
        List<String> files = new ArrayList<>();
        for (P4Data.OpenedFile f : c.files()) files.add(f.depotFile());
        return cli.taggedWithArgs(null, () -> false, files, "revert", "-c", clArg(c));
    }

    // ---------------------------------------------------------------- file operations

    /** `p4 edit` / `p4 add` on the file in the active editor — the step that starts every change. */
    private void openCurrentFile(String command) {
        VirtualFile vf = currentEditorFile();
        if (vf == null) return;
        String path = new File(vf.getPath()).getPath(); // platform separators
        P4Cli cli = service.cli();
        mutate("Perforce: " + command, false, ind -> {
            // edit takes the name escaped (icon@2x.png -> icon%402x.png); add -f takes it literally
            String error = command.equals("add") ? P4Ops.add(cli, List.of(path), ind::isCanceled)
                    : P4Ops.edit(cli, List.of(path), ind::isCanceled);
            service.ui(() -> status.setText(command + " " + path + " -> " + (error == null ? "OK" : "FAILED\n" + error)));
            VfsUtil.markDirtyAndRefresh(true, false, false, vf); // p4 edit flips the read-only bit
        });
    }

    private void diffSelected() {
        P4Data.OpenedFile f = selectedFile();
        if (f == null) return;
        P4Cli cli = service.cli();
        service.background("Perforce: diff", true, false, ind -> {
            P4Cli.Result r = diff(cli, f.depotFile(), ind::isCanceled);
            service.ui(() -> status.setText("diff " + f.depotFile() + "\n" + clip(r.text())));
        });
    }

    static P4Cli.Result diff(P4Cli cli, String depotFile, BooleanSupplier cancelled) {
        return cli.run(null, cancelled, "diff", depotFile);
    }

    static P4Cli.Result annotate(P4Cli cli, String depotFile, BooleanSupplier cancelled) {
        return cli.run(null, cancelled, "annotate", depotFile);
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
            P4Cli.Result r = annotate(cli, f.depotFile(), ind::isCanceled);
            service.ui(() -> status.setText("annotate " + f.depotFile() + "\n" + clip(r.text())));
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
                        resolve(cli, flag, f.depotFile())));
            });
        });
    }

    /** `resolve -at|-ay` of one file. No time limit: accepting theirs on a large binary transfers it. */
    static P4Cli.Result resolve(P4Cli cli, String flag, String depotFile) {
        return cli.run(null, () -> false, "resolve", flag, depotFile);
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
            P4Cli.Tagged fstat = cli.tagged("fstat", P4Args.escape(local));
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
        // "/name": anchored to the ignore file's directory, so a same-named file deeper down is not ignored too,
        // and a name starting with # or ! is not read as a comment or a negation (both verified on r25.2).
        // A global (absolute) P4IGNORE file lives elsewhere, so it gets the bare name; the re-check below reports it.
        String rule = Path.of(name).isAbsolute() ? file.getFileName().toString() : "/" + file.getFileName();
        try {
            List<String> lines = Files.exists(ignoreFile) ? Files.readAllLines(ignoreFile, StandardCharsets.UTF_8) : List.of();
            if (!lines.contains(rule)) {
                List<String> updated = new ArrayList<>(lines);
                updated.add(rule);
                Files.write(ignoreFile, (String.join("\n", updated) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            return "cannot write " + ignoreFile + ": " + e;
        }
        boolean ok = !P4Data.ignored(cli, List.of(local)).isEmpty();
        return (ok ? "now ignored: " : "IGNORE FAILED (p4 still does not ignore it): ") + local + "\n(rule '" + rule
                + "' in " + ignoreFile + (setting == null ? ", P4IGNORE unset" : ", P4IGNORE=" + setting) + ")";
    }

    // ---------------------------------------------------------------- workspace operations

    /** sync the client, then `resolve -am`: merges only files with no conflicting chunks; conflicts are listed, not touched. */
    private void syncAutoMerge() {
        P4Cli cli = service.cli();
        mutate("Perforce: sync", true, ind -> {
            P4Cli.Result[] r = syncAndAutoMerge(cli, ind::isCanceled);
            service.ui(() -> status.setText("sync:\n" + clip(r[0].text()) + "\n\nresolve -am (auto-merge, conflicts left alone):\n"
                    + clip(r[1].text()) + "\nFor a file still listed as conflicting: select it, then Accept theirs… / Accept yours…"));
        });
    }

    /** {sync, resolve -am}: merges only files without conflicting chunks; conflicting files are left opened. */
    static P4Cli.Result[] syncAndAutoMerge(P4Cli cli, BooleanSupplier cancelled) {
        P4Cli.Result sync = cli.run(null, cancelled, "sync");
        P4Cli.Result merge = cli.run(null, cancelled, "resolve", "-am");
        return new P4Cli.Result[]{sync, merge};
    }

    /** Preview with `reconcile -n`, show what would be opened, then open exactly those files.
     *  -f in both: without it p4 skips every file whose name holds @ # % * (verified on r25.2). */
    private void reconcile() {
        P4Cli cli = service.cli();
        service.background("Perforce: reconcile preview", true, false, ind -> {
            P4Cli.Tagged preview = cli.tagged(null, ind::isCanceled, "reconcile", "-n", "-f", "//...");
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
                        reconcileExactly(cli, recs, ind2::isCanceled).raw()));
            });
        });
    }

    /** Opens exactly the previewed files (their local paths, taken literally with -f) via an argument file. */
    static P4Cli.Tagged reconcileExactly(P4Cli cli, List<Map<String, String>> recs, BooleanSupplier cancelled) {
        List<String> files = new ArrayList<>();
        for (Map<String, String> r : recs) {
            String local = r.get("clientFile");
            if (local != null && !local.isBlank() && !files.contains(local)) files.add(local);
        }
        return cli.taggedWithArgs(null, cancelled, files, "reconcile", "-f");
    }
}
