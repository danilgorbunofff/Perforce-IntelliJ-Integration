package p4gate;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.List;

/** Tool window content: pending changelist tree + submit/shelve/revert/info actions driven by the p4 CLI. */
public final class P4Panel {
    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode("no data yet — press Refresh")));
    private final JTextArea status = new JTextArea(10, 70);

    public JComponent root() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(button("Refresh", this::refresh));
        top.add(button("Submit", () -> onSelected(this::submit)));
        top.add(button("Shelve", () -> onSelected(this::shelve)));
        top.add(button("Revert (-k)", () -> onSelected(this::revert)));
        top.add(button("p4 info", this::showInfo));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(tree), BorderLayout.CENTER);
        status.setEditable(false);
        panel.add(new JScrollPane(status), BorderLayout.SOUTH);
        return panel;
    }

    private static JButton button(String label, Runnable action) {
        JButton b = new JButton(label);
        b.addActionListener(e -> new Thread(() -> action.run()).start());
        return b;
    }

    private void refresh() {
        List<P4Data.Change> changes = P4Data.pendingChanges();
        SwingUtilities.invokeLater(() -> {
            DefaultMutableTreeNode root = new DefaultMutableTreeNode("pending changelists (" + changes.size() + ")");
            for (P4Data.Change c : changes) {
                DefaultMutableTreeNode cn = new DefaultMutableTreeNode(c);
                for (String f : c.files()) {
                    cn.add(new DefaultMutableTreeNode(f));
                }
                root.add(cn);
            }
            tree.setModel(new DefaultTreeModel(root));
            status.setText("");
        });
    }

    private void showInfo() {
        P4Cli.Result r = P4Cli.run("info");
        SwingUtilities.invokeLater(() -> status.setText(r.text()));
    }

    private void onSelected(java.util.function.Consumer<Long> op) {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            status.setText("select a changelist first");
            return;
        }
        Object o = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        if (!(o instanceof P4Data.Change c) || c.id() == 0) {
            status.setText("select a numbered changelist (not the default)");
            return;
        }
        op.accept(c.id());
        refresh();
    }

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
