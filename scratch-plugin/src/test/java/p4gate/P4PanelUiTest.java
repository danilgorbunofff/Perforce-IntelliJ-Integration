package p4gate;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The Changelists tab's UI behaviour in a headless IDE against a real p4d: what the toolbar offers, how rows read,
 * and that a refresh (which every action triggers) keeps what the user had expanded and selected.
 * Excluded from the build (like {@link P4VcsPlatformTest}) unless P4_BIN points at p4 + p4d, so it never passes vacuously.
 */
public final class P4PanelUiTest extends HeavyPlatformTestCase {
    private P4Lab lab;
    private P4Project service;

    @Override
    protected boolean runInDispatchThread() {
        return false; // p4 work runs in background tasks whose results arrive on the EDT: do not block it
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        lab = P4Lab.start();
        Files.writeString(lab.ws.resolve(".p4config"), "P4CLIENT=" + P4Lab.CLIENT + "\n");
        VirtualFile ws = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(lab.ws);
        assertNotNull(ws);
        EdtTestUtil.runInEdtAndWait(() -> PsiTestUtil.addContentRoot(getModule(), ws));
        service = P4Project.get(getProject());
        service.configure(lab.p4(), lab.ws.toString());
        service.setEnvOverride(lab.env(P4Lab.USER, P4Lab.CLIENT));
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            super.tearDown();
        } finally {
            if (lab != null) lab.close();
        }
    }

    private static <T> T onEdt(Supplier<T> s) {
        AtomicReference<T> r = new AtomicReference<>();
        EdtTestUtil.runInEdtAndWait(() -> r.set(s.get()));
        return r.get();
    }

    private static void collect(AnAction a, List<String> names) {
        if (a instanceof ActionGroup g && !(a instanceof DefaultActionGroup d && !d.isPopup())) {
            names.add(a.getTemplatePresentation().getText());
            for (AnAction c : ((DefaultActionGroup) g).getChildActionsOrStubs()) collect(c, names);
        } else if (a instanceof DefaultActionGroup d) {
            for (AnAction c : d.getChildActionsOrStubs()) collect(c, names);
        } else if (a.getTemplatePresentation().getText() != null) {
            names.add(a.getTemplatePresentation().getText());
        }
    }

    public void testToolbarOffersEveryOperationAndNothingIsClippedAway() {
        P4Panel panel = new P4Panel(service);
        List<String> names = new ArrayList<>();
        collect(onEdt(panel::toolbarGroup), names);
        for (String expected : List.of("Refresh", "Submit Changelist…", "Shelve Changelist", "Revert Changelist",
                "Revert (keep files)", "Revert (discard edits)…", "Diff Against Depot", "Annotate File", "Resolve Conflict",
                "Accept theirs…", "Accept yours…", "Sync + Auto-Merge", "More Actions", "Edit current file",
                "Add current file", "Ignore file…", "Reconcile…", "p4 info")) {
            assertTrue("toolbar is missing '" + expected + "' (has " + names + ")", names.contains(expected));
        }
    }

    /**
     * "Replace shelf" always failed with "another Perforce operation is still running": the confirm callback the first
     * operation queued with ui() started the next exclusive one while the first still held the lock (it was only released
     * in onFinished, which runs after that callback). The 600 ms sleep stands for the modal prompt on the EDT.
     */
    public void testAFollowUpQueuedByAnExclusiveOperationCanStartTheNextOne() throws Exception {
        AtomicBoolean secondStarted = new AtomicBoolean();
        CountDownLatch secondRan = new CountDownLatch(1);
        CountDownLatch decided = new CountDownLatch(1);
        assertTrue(service.background("first", false, true, ind -> service.ui(() -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            secondStarted.set(service.background("second", false, true, i -> secondRan.countDown()));
            decided.countDown();
        })));
        assertTrue("the follow-up callback ran", decided.await(20, TimeUnit.SECONDS));
        assertTrue("the follow-up was accepted, not refused as busy", secondStarted.get());
        assertTrue("and it ran", secondRan.await(20, TimeUnit.SECONDS));
        Thread.sleep(300);
        assertTrue("and the lock is free again afterwards", service.background("third", false, true, i -> { }));
    }

    public void testRowsReadAsChangelistsAndColouredFiles() {
        P4Data.OpenedFile edit = new P4Data.OpenedFile("//depot/src/a.txt", "/ws/src/a.txt", "edit", 5, "text");
        P4Data.Change numbered = new P4Data.Change(5, "alice", "Fix the login timeout", List.of(edit));
        P4Data.Change dflt = new P4Data.Change(0, "alice", "", List.of());
        P4Panel.NodeRenderer r = new P4Panel.NodeRenderer();
        Tree tree = onEdt(Tree::new);
        String change = onEdt(() -> r.getTreeCellRendererComponent(tree, new DefaultMutableTreeNode(numbered),
                false, true, false, 0, false).toString());
        String empty = onEdt(() -> r.getTreeCellRendererComponent(tree, new DefaultMutableTreeNode(dflt),
                false, false, true, 0, false).toString());
        String file = onEdt(() -> r.getTreeCellRendererComponent(tree, new DefaultMutableTreeNode(edit),
                false, false, true, 0, false).toString());
        assertEquals("Change 5  Fix the login timeout  1 file", change);
        assertEquals("Default Changelist  empty", empty);
        assertEquals("a.txt  src  edit", file);
        assertEquals("a file directly in the depot shows no folder", "", P4Panel.NodeRenderer.folderBelowDepot("//depot/a.txt"));
        assertEquals("sub dir", P4Panel.NodeRenderer.folderBelowDepot("//depot/sub dir/file with space.txt"));
        assertEquals("main/src/util", P4Panel.NodeRenderer.folderBelowDepot("//streams/main/src/util/x.java"));
        assertNotNull("an edit is drawn in the modified-file colour", P4Panel.NodeRenderer.statusColor("edit"));
        assertNotSame("add and delete differ", P4Panel.NodeRenderer.statusColor("add"), P4Panel.NodeRenderer.statusColor("delete"));
    }

    /** every action ends in a refresh: it must not collapse the tree or drop the selection. */
    public void testRefreshKeepsExpansionAndSelection() throws Exception {
        P4Cli cli = lab.cli();
        long cl = lab.newChange("keep my place");
        P4Lab.ok(cli.run("edit", "-c", String.valueOf(cl), "a.txt"));
        lab.write("extra.txt", "x\n");
        P4Lab.ok(cli.run("add", "-c", String.valueOf(cl), "extra.txt"));

        P4Panel panel = new P4Panel(service);
        JComponent root = onEdt(panel::root);
        Tree tree = onEdt(() -> UIUtil.findComponentOfType(root, Tree.class));
        refreshAndWait(panel, tree, 2);

        // the numbered changelist is expanded and its second file selected
        DefaultMutableTreeNode changeNode = onEdt(() -> findChange(tree, cl));
        assertNotNull("change " + cl + " is listed", changeNode);
        TreePath changePath = new TreePath(changeNode.getPath());
        TreePath filePath = onEdt(() -> {
            tree.expandPath(changePath);
            TreePath p = new TreePath(((DefaultMutableTreeNode) changeNode.getChildAt(1)).getPath());
            tree.setSelectionPath(p);
            return p;
        });
        String selectedBefore = onEdt(() -> String.valueOf(((DefaultMutableTreeNode) tree.getSelectionPath().getLastPathComponent()).getUserObject()));

        refreshAndWait(panel, tree, 2);

        assertTrue("the changelist is still expanded", onEdt(() -> tree.isExpanded(new TreePath(findChange(tree, cl).getPath()))));
        TreePath after = onEdt(tree::getSelectionPath);
        assertNotNull("the selection survived the refresh", after);
        assertEquals("the same file is still selected", selectedBefore,
                String.valueOf(((DefaultMutableTreeNode) after.getLastPathComponent()).getUserObject()));
        assertNotNull(filePath);
    }

    private static DefaultMutableTreeNode findChange(Tree tree, long id) {
        TreeNode root = (TreeNode) tree.getModel().getRoot();
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) root.getChildAt(i);
            if (n.getUserObject() instanceof P4Data.Change c && c.id() == id) return n;
        }
        return null;
    }

    /** runs a refresh and waits until the tree model was replaced by a new one holding at least {@code minChildren}. */
    private static void refreshAndWait(P4Panel panel, Tree tree, int minChildren) throws InterruptedException {
        Object oldModel = onEdt(tree::getModel);
        EdtTestUtil.runInEdtAndWait(panel::refresh);
        for (int i = 0; i < 100; i++) {
            Thread.sleep(200);
            boolean done = onEdt(() -> tree.getModel() != oldModel
                    && ((TreeNode) tree.getModel().getRoot()).getChildCount() >= minChildren);
            if (done) return;
        }
        fail("the refresh did not finish");
    }
}
