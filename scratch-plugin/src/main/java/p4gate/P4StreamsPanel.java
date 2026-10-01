package p4gate;

import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stream tree tab: builds the parent hierarchy from `p4 streams`, marks the stream the current
 *  client is dedicated to. p4 work in a background task, Swing writes on the EDT, failures shown. */
public final class P4StreamsPanel {
    private final P4Project service;
    private final Tree tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode("no streams loaded — press Refresh")));
    private final JLabel clientLabel = new JLabel(" ");

    public P4StreamsPanel(P4Project service) {
        this.service = service;
    }

    public JComponent root() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refresh());
        top.add(refresh);
        top.add(clientLabel);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JBScrollPane(tree), BorderLayout.CENTER);
        return panel;
    }

    private void refresh() {
        P4Cli cli = service.cli();
        service.background("Perforce: streams", true, false, indicator -> {
            P4Data.Listing<P4Data.StreamSpec> streams = P4Data.streams(cli);
            P4Data.Listing<String> clientStream = P4Data.clientStream(cli);
            service.ui(() -> {
                clientLabel.setText(clientStream.error() != null ? "client stream unknown: " + clientStream.error()
                        : clientStream.items().isEmpty() ? "(current client is not a stream client)"
                        : "current client on stream: " + clientStream.items().get(0));
                tree.setModel(new DefaultTreeModel(buildTree(streams)));
            });
        });
    }

    /** Real parent hierarchy (children may be listed before parents). */
    static DefaultMutableTreeNode buildTree(P4Data.Listing<P4Data.StreamSpec> listing) {
        if (listing.error() != null) {
            return new DefaultMutableTreeNode("streams — FAILED: " + listing.error());
        }
        List<P4Data.StreamSpec> streams = listing.items();
        Map<String, DefaultMutableTreeNode> nodes = new HashMap<>();
        for (P4Data.StreamSpec s : streams) nodes.put(s.name(), new DefaultMutableTreeNode(s));
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("streams (" + streams.size() + ")");
        for (P4Data.StreamSpec s : streams) {
            DefaultMutableTreeNode parent = s.parent().equals("none") ? root : nodes.get(s.parent());
            (parent == null ? root : parent).add(nodes.get(s.name())); // parent not listed (no access) — show top-level
        }
        return root;
    }
}
