package p4gate;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;

/** Stream tree tab: builds the parent hierarchy from `p4 streams`, marks the stream the current
 *  client is dedicated to. Off-EDT p4 work, EDT-only Swing writes. */
public final class P4StreamsPanel {
    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode("no streams loaded — press Refresh")));
    private final JLabel clientLabel = new JLabel(" ");

    public JComponent root() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refresh());
        top.add(refresh);
        top.add(clientLabel);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(tree), BorderLayout.CENTER);
        return panel;
    }

    private void refresh() {
        new Thread(() -> {
            java.util.List<P4Data.StreamSpec> streams = P4Data.streams();
            String clientStream = P4Data.clientStream();
            SwingUtilities.invokeLater(() -> {
                clientLabel.setText(clientStream == null
                        ? "(current client is not a stream client)"
                        : "current client on stream: " + clientStream);
                // build the real parent hierarchy (children may be listed before parents)
                java.util.Map<String, DefaultMutableTreeNode> nodes = new java.util.HashMap<>();
                for (P4Data.StreamSpec s : streams) {
                    nodes.put(s.name(), new DefaultMutableTreeNode(s));
                }
                DefaultMutableTreeNode root = new DefaultMutableTreeNode("streams (" + streams.size() + ")");
                for (P4Data.StreamSpec s : streams) {
                    DefaultMutableTreeNode node = nodes.get(s.name());
                    DefaultMutableTreeNode parent = s.parent().equals("none") ? root : nodes.get(s.parent());
                    if (parent == null) {
                        parent = root; // parent lives in another depot — show it top-level
                    }
                    parent.add(node);
                }
                tree.setModel(new DefaultTreeModel(root));
            });
        }).start();
    }
}
