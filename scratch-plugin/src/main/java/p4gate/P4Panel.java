package p4gate;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Consumer;

/** Tool window content: pending changelist tree, sortable submitted index, stream context, ignore action.
 *  All p4 work runs off the EDT; all Swing access happens on it. */
public final class P4Panel {
    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode("no data yet — press Refresh")));
    private final DefaultTableModel submittedModel =
            new DefaultTableModel(new Object[]{"CL", "user", "client", "description"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
                @Override public Class<?> getColumnClass(int c) { return c == 0 ? Long.class : String.class; }
            };
    private final JTable submittedTable = new JTable(submittedModel);
    private final JLabel streamLabel = new JLabel(" ");
    private final JTextArea status = new JTextArea(10, 70);

    public JComponent root() {
        submittedTable.setRowSorter(new TableRowSorter<>(submittedModel)); // header click sorts (CL numeric)
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(button("Refresh", this::refresh));
        top.add(button("Submit", this::submitSelected));
        top.add(button("Shelve", this::shelveSelected));
        top.add(button("Revert (-k)", this::revertSelected));
        top.add(button("Ignore file", this::ignoreSelected));
        top.add(button("p4 info", this::showInfo));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(tree), new JScrollPane(submittedTable));
        split.setResizeWeight(0.6);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(top, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(streamLabel, BorderLayout.NORTH);
        status.setEditable(false);
        south.add(new JScrollPane(status), BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    private static JButton button(String label, Runnable action) {
        JButton b = new JButton(label);
        b.addActionListener(e -> action.run()); // EDT: these bodies only start background work or read Swing state
        return b;
    }

    private void refresh() {
        new Thread(() -> {
            List<P4Data.Change> changes = P4Data.pendingChanges();
            List<P4Data.SubmittedChange> index = P4Data.submittedIndex(200);
            String stream = P4Data.clientStream();
            SwingUtilities.invokeLater(() -> {
                refreshTree(changes);
                refreshIndex(index);
                streamLabel.setText(stream == null ? "(classic client — no stream)" : "client on stream: " + stream);
            });
        }).start();
    }

    private void refreshTree(List<P4Data.Change> changes) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("pending changelists (" + changes.size() + ")");
        for (P4Data.Change c : changes) {
            DefaultMutableTreeNode cn = new DefaultMutableTreeNode(c);
            for (String f : c.files()) {
                cn.add(new DefaultMutableTreeNode(f));
            }
            root.add(cn);
        }
        tree.setModel(new DefaultTreeModel(root));
    }

    private void refreshIndex(List<P4Data.SubmittedChange> index) {
        submittedModel.setRowCount(0);
        for (P4Data.SubmittedChange sc : index) {
            submittedModel.addRow(new Object[]{sc.id(), sc.user(), sc.client(), sc.desc()});
        }
    }

    private void showInfo() {
        new Thread(() -> {
            P4Cli.Result r = P4Cli.run("info");
            SwingUtilities.invokeLater(() -> status.setText(r.text()));
        }).start();
    }

    /** Reads the selection here on the EDT, then runs the operation on a background thread. */
    private void onSelected(Consumer<Long> op) {
        Object o = selectionObject("select a numbered changelist (not the default)");
        if (o == null) {
            return;
        }
        if (!(o instanceof P4Data.Change c) || c.id() == 0) {
            status.setText("select a numbered changelist (not the default)");
            return;
        }
        long id = c.id();
        new Thread(() -> {
            op.accept(id);
            refresh();
        }).start();
    }

    /** Tree selection: null when nothing applicable (message already shown). */
    private Object selectionObject(String emptyMessage) {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            status.setText(emptyMessage);
            return null;
        }
        return ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    }

    private void ignoreSelected() {
        Object o = selectionObject("select a file under a changelist first");
        if (o == null) {
            return;
        }
        if (!(o instanceof String node)) {
            status.setText("select a file node (a changelist is not a file)");
            return;
        }
        String depotPath = node.split(" — ")[0].trim(); // node text: "<depot path> — <action> @<cl>"
        new Thread(() -> {
            String local = P4Data.where(depotPath);
            if (local == null) {
                SwingUtilities.invokeLater(() -> status.setText("p4 where failed for " + depotPath));
                return;
            }
            if (!P4Data.ignored(List.of(local)).isEmpty()) {
                SwingUtilities.invokeLater(() -> status.setText(local + "\nis already ignored by p4"));
                return;
            }
            // append the file's name to the ignore file in the file's own directory;
            // p4's default ignore names on this build (verified): p4ignore.txt and .p4ignore
            String name = P4Cli.envOverride().get("P4IGNORE");
            Path ignoreFile = Paths.get(local).getParent().resolve(name == null ? "p4ignore.txt" : name);
            String baseName = Paths.get(local).getFileName().toString();
            try {
                List<String> lines = Files.exists(ignoreFile) ? Files.readAllLines(ignoreFile, StandardCharsets.UTF_8) : List.of();
                if (!lines.contains(baseName)) {
                    String joined = lines.isEmpty() ? baseName : String.join("\n", lines) + "\n" + baseName;
                    Files.write(ignoreFile, (joined + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> status.setText("cannot write " + ignoreFile + ": " + e));
                return;
            }
            boolean ok = !P4Data.ignored(List.of(local)).isEmpty();
            SwingUtilities.invokeLater(() -> status.setText((ok ? "now ignored: " : "IGNORE FAILED: ") + local
                    + "\n(via " + ignoreFile + ")\nrefresh pending list to see the file stop showing in add"));
        }).start();
    }

    private void submitSelected() { onSelected(this::submit); }

    private void shelveSelected() { onSelected(this::shelve); }

    private void revertSelected() { onSelected(this::revert); }

    private void submit(long id) {
        P4Cli.Result r = P4Cli.run("submit", "-c", String.valueOf(id));
        report("submit", id, r);
    }

    private void shelve(long id) {
        P4Cli.Result r = P4Cli.run("shelve", "-c", String.valueOf(id));
        report("shelve", id, r);
    }

    private void revert(long id) {
        // -k: keep workspace contents, only clear the opened state — no data loss
        P4Cli.Result r = P4Cli.run("revert", "-k", "-c", String.valueOf(id), "//...");
        report("revert -k", id, r);
    }

    private void report(String op, long id, P4Cli.Result r) {
        String text = op + " change " + id + " -> " + (r.ok() ? "OK" : "FAILED (" + r.code() + ")") + "\n" + r.text();
        SwingUtilities.invokeLater(() -> status.setText(text));
    }
}
