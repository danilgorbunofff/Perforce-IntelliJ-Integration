package p4gate;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

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
    private final Tree tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode("No streams loaded — press Refresh")));
    private final JBLabel clientLabel = new JBLabel(" ");

    public P4StreamsPanel(P4Project service) {
        this.service = service;
    }

    public JComponent root() {
        tree.setCellRenderer(new ColoredTreeCellRenderer() {
            @Override
            public void customizeCellRenderer(@NotNull JTree t, Object value, boolean selected, boolean expanded,
                                              boolean leaf, int row, boolean hasFocus) {
                Object o = ((DefaultMutableTreeNode) value).getUserObject();
                if (o instanceof P4Data.StreamSpec s) {
                    setIcon(AllIcons.Vcs.Branch);
                    append(s.name(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    append("  " + s.type(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                } else {
                    append(String.valueOf(o), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                }
            }
        });
        DefaultActionGroup bar = new DefaultActionGroup();
        bar.add(new DumbAwareAction("Refresh", "Reload the stream hierarchy", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                refresh();
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        });
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("P4Streams", bar, true);
        toolbar.setTargetComponent(tree);
        toolbar.getComponent().setBorder(JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0));
        clientLabel.setForeground(UIUtil.getContextHelpForeground());
        clientLabel.setBorder(JBUI.Borders.empty(3, 8));

        JPanel header = new JPanel(new BorderLayout());
        header.add(toolbar.getComponent(), BorderLayout.NORTH);
        header.add(clientLabel, BorderLayout.SOUTH);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(header, BorderLayout.NORTH);
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
            return new DefaultMutableTreeNode("Streams — FAILED: " + listing.error());
        }
        List<P4Data.StreamSpec> streams = listing.items();
        Map<String, DefaultMutableTreeNode> nodes = new HashMap<>();
        for (P4Data.StreamSpec s : streams) nodes.put(s.name(), new DefaultMutableTreeNode(s));
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(
                streams.isEmpty() ? "No streams on this server" : "Streams (" + streams.size() + ")");
        for (P4Data.StreamSpec s : streams) {
            DefaultMutableTreeNode parent = s.parent().equals("none") ? root : nodes.get(s.parent());
            (parent == null ? root : parent).add(nodes.get(s.name())); // parent not listed (no access) — show top-level
        }
        return root;
    }
}
