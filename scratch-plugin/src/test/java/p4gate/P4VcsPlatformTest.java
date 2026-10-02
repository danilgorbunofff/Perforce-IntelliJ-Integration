package p4gate;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.VcsConfiguration;
import com.intellij.openapi.vcs.VcsShowConfirmationOption;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.VcsRootChecker;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl;
import com.intellij.openapi.vcs.changes.LocallyDeletedChange;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vcs.rollback.RollbackProgressListener;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PsiTestUtil;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The VCS integration inside a real (headless) IDE: the plugin is loaded from plugin.xml, the project is mapped
 * to Perforce, and the platform's own ChangeListManager, commit and rollback machinery runs against a real p4d.
 * Skipped (passes trivially) unless P4_BIN points at p4 + p4d.
 */
public final class P4VcsPlatformTest extends HeavyPlatformTestCase {
    private P4Lab lab;
    private VirtualFile ws;
    private ChangeListManagerImpl clm;
    private P4Vcs vcs;

    @Override
    protected boolean runInDispatchThread() {
        return false; // ChangeListManager updates run in the background; waiting for them on the EDT would deadlock
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (P4Lab.binDir() == null) return;
        lab = P4Lab.start();
        Files.writeString(lab.ws.resolve(".p4config"), "P4CLIENT=" + P4Lab.CLIENT + "\n");
        ws = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(lab.ws);
        assertNotNull(ws);
        EdtTestUtil.runInEdtAndWait(() -> PsiTestUtil.addContentRoot(getModule(), ws));
        P4Project service = P4Project.get(getProject());
        service.configure(lab.p4(), lab.ws.toString());
        service.setEnvOverride(lab.env(P4Lab.USER, P4Lab.CLIENT));
        EdtTestUtil.runInEdtAndWait(() -> ProjectLevelVcsManager.getInstance(getProject())
                .setDirectoryMappings(List.of(new VcsDirectoryMapping(ws.getPath(), P4Vcs.NAME))));
        AbstractVcs found = ProjectLevelVcsManager.getInstance(getProject()).findVcsByName(P4Vcs.NAME);
        assertTrue("the registered 'Perforce' VCS is this plugin's: " + found, found instanceof P4Vcs);
        vcs = (P4Vcs) found;
        clm = ChangeListManagerImpl.getInstanceImpl(getProject());
        clm.forceGoInTestMode();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (clm != null) clm.waitEverythingDoneAndStopInTestMode();
        } finally {
            try {
                super.tearDown();
            } finally {
                if (lab != null) lab.close();
            }
        }
    }

    private Collection<Change> changes() {
        VfsUtil.markDirtyAndRefresh(false, true, true, ws);
        VcsDirtyScopeManager.getInstance(getProject()).markEverythingDirty();
        clm.ensureUpToDate();
        return clm.getAllChanges();
    }

    private Change changeOf(String rel) {
        String path = lab.local(rel).replace('\\', '/');
        for (Change c : changes()) {
            for (var rev : new com.intellij.openapi.vcs.changes.ContentRevision[]{c.getBeforeRevision(), c.getAfterRevision()}) {
                if (rev != null && rev.getFile().getPath().equalsIgnoreCase(path)) return c;
            }
        }
        return null;
    }

    private static final RollbackProgressListener NO_PROGRESS = RollbackProgressListener.EMPTY;

    /** Blocker 4 of the review: an application-level extension with a constructor parameter never loads. */
    public void testRootCheckerIsLoadedAndDetectsTheWorkspace() {
        if (lab == null) return;
        P4RootChecker checker = null;
        for (VcsRootChecker c : VcsRootChecker.EXTENSION_POINT_NAME.getExtensionList()) {
            if (c instanceof P4RootChecker p) checker = p;
        }
        assertNotNull("P4RootChecker is instantiated by the platform", checker);
        assertEquals(P4Vcs.getKey(), checker.getSupportedVcs());
        assertTrue(checker.isRoot(ws));
        assertFalse(checker.isRoot(ws.findChild("sub dir")));
    }

    public void testLocalChangesListOpenedFilesWithTheDepotSideAtHave() throws Exception {
        if (lab == null) return;
        assertNull(P4Ops.edit(lab.cli(), List.of(lab.local("a.txt"), lab.local("icon@2x.png")), () -> false));
        lab.write("a.txt", "EDITED\n");
        lab.write("new file.txt", "n\n");
        assertNull(P4Ops.add(lab.cli(), List.of(lab.local("new file.txt")), () -> false));
        // writable but never opened: Perforce does not know it changed, so neither does Local Changes
        lab.ws.resolve("sub dir/c 2.txt").toFile().setWritable(true);
        lab.write("sub dir/c 2.txt", "not opened\n");

        Change edited = changeOf("a.txt");
        assertNotNull("edited file is listed: " + changes(), edited);
        assertEquals(FileStatus.MODIFIED, edited.getFileStatus());
        assertEquals("diff before side is the depot content, not the disk", "a1", edited.getBeforeRevision().getContent().strip());
        assertEquals("EDITED", edited.getAfterRevision().getContent().strip());
        assertEquals(FileStatus.MODIFIED, changeOf("icon@2x.png").getFileStatus());
        assertEquals(FileStatus.ADDED, changeOf("new file.txt").getFileStatus());
        assertNull("an unopened file is never listed", changeOf("sub dir/c 2.txt"));
        assertEquals(3, changes().size());
    }

    public void testCommitSubmitsTheSelectedFileWithTheMessage() throws Exception {
        if (lab == null) return;
        assertNull(P4Ops.edit(lab.cli(), List.of(lab.local("a.txt"), lab.local("icon@2x.png")), () -> false));
        lab.write("a.txt", "a2\n");
        Change edited = changeOf("a.txt");
        List<VcsException> errors = vcs.getCheckinEnvironment().commit(List.of(edited), "from the commit dialog");
        assertTrue(String.valueOf(errors), errors.isEmpty());
        P4Data.SubmittedChange top = P4Data.submittedIndex(lab.cli(), P4Lab.CLIENT, 1).items().get(0);
        assertEquals("from the commit dialog", top.desc());
        assertNull("submitted, so no longer a change", changeOf("a.txt"));
        assertNotNull("the unselected file is untouched", changeOf("icon@2x.png"));
        assertFalse("submitted files are read-only again", Files.isWritable(lab.ws.resolve("a.txt")));
    }

    public void testCommitReportsP4Errors() throws Exception {
        if (lab == null) return;
        assertNull(P4Ops.edit(lab.cli(), List.of(lab.local("a.txt")), () -> false));
        List<VcsException> errors = vcs.getCheckinEnvironment().commit(List.of(changeOf("a.txt")), "   ");
        assertEquals("an empty message is refused, not silently submitted", 1, errors.size());
    }

    public void testRollbackRestoresTheDepotContent() throws Exception {
        if (lab == null) return;
        assertNull(P4Ops.edit(lab.cli(), List.of(lab.local("a.txt")), () -> false));
        lab.write("a.txt", "EDITED\n");
        List<VcsException> errors = new ArrayList<>();
        vcs.getRollbackEnvironment().rollbackChanges(List.of(changeOf("a.txt")), errors, NO_PROGRESS);
        assertTrue(String.valueOf(errors), errors.isEmpty());
        assertEquals("a1\n", lab.read("a.txt"));
        assertTrue(changes().isEmpty());
    }

    public void testAMissingOpenedFileIsLocallyDeletedAndRollbackBringsItBack() throws Exception {
        if (lab == null) return;
        assertNull(P4Ops.edit(lab.cli(), List.of(lab.local("a.txt")), () -> false));
        Files.delete(lab.ws.resolve("a.txt"));
        changes();
        List<LocallyDeletedChange> deleted = clm.getDeletedFiles();
        assertEquals(1, deleted.size());
        List<VcsException> errors = new ArrayList<>();
        vcs.getRollbackEnvironment().rollbackMissingFileDeletion(List.of(deleted.get(0).getPath()), errors, NO_PROGRESS);
        assertTrue(String.valueOf(errors), errors.isEmpty());
        assertEquals("a1\n", lab.read("a.txt"));
    }

    private void silentConfirmations() {
        ProjectLevelVcsManager mgr = ProjectLevelVcsManager.getInstance(getProject());
        mgr.getStandardConfirmation(VcsConfiguration.StandardConfirmation.ADD, vcs).setValue(VcsShowConfirmationOption.Value.DO_ACTION_SILENTLY);
        mgr.getStandardConfirmation(VcsConfiguration.StandardConfirmation.REMOVE, vcs).setValue(VcsShowConfirmationOption.Value.DO_ACTION_SILENTLY);
    }

    /** The listener runs p4 asynchronously (never on the UI thread): wait for p4 to report the expected state. */
    private P4OpenFile awaitOpened(String rel, String action) throws InterruptedException {
        assertNotNull("the VCS is active, so its file listener is installed", vcs.vfsListener());
        vcs.vfsListener().waitForEvents();
        P4OpenFile f = null;
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            f = P4OpenFile.byLocal(P4OpenFile.all(lab.cli(), () -> false).items()).get(P4OpenFile.normalize(lab.local(rel)));
            if (f != null && f.action().equals(action)) return f;
            Thread.sleep(100);
        }
        fail(rel + " expected opened for " + action + ", was " + f);
        return null;
    }

    /** A rename in the IDE (what every refactoring does) is a real p4 move, not a delete plus an unknown file. */
    public void testIdeRenameIsAPerforceMove() throws Exception {
        if (lab == null) return;
        silentConfirmations();
        changes(); // the listener asks ChangeListManager whether the file is versioned
        VirtualFile a = ws.findChild("a.txt");
        // inside a command, as every IDE edit and refactoring is: the listener acts when the command finishes
        EdtTestUtil.runInEdtAndWait(() -> WriteCommandAction.writeCommandAction(getProject()).run(() -> a.rename(this, "renamed.txt")));
        P4OpenFile moved = awaitOpened("renamed.txt", "move/add");
        assertEquals("//depot/a.txt", moved.movedFile());
    }

    public void testIdeCreatedFileIsAddedAndDeletedFileIsDeleted() throws Exception {
        if (lab == null) return;
        silentConfirmations();
        changes();
        EdtTestUtil.runInEdtAndWait(() -> WriteCommandAction.writeCommandAction(getProject()).run(() -> {
            VirtualFile f = ws.createChildData(this, "Created.java");
            VfsUtil.saveText(f, "class Created {}\n");
        }));
        awaitOpened("Created.java", "add");
        VirtualFile blob = ws.findChild("blob.bin");
        EdtTestUtil.runInEdtAndWait(() -> WriteCommandAction.writeCommandAction(getProject()).run(() -> blob.delete(this)));
        awaitOpened("blob.bin", "delete");
    }

    /** Auto-checkout, called from a worker thread and from the EDT (where it runs under modal progress). */
    public void testEditFileProviderOpensTheFile() throws Exception {
        if (lab == null) return;
        VirtualFile a = ws.findChild("a.txt");
        VirtualFile icon = ws.findChild("icon@2x.png");
        assertFalse(a.isWritable());
        vcs.getEditFileProvider().editFiles(new VirtualFile[]{a});
        assertTrue("writable after p4 edit", a.isWritable());
        EdtTestUtil.runInEdtAndWait(() -> vcs.getEditFileProvider().editFiles(new VirtualFile[]{icon}));
        assertTrue(icon.isWritable());
        assertEquals(2, P4OpenFile.all(lab.cli(), () -> false).items().size());
    }
}
