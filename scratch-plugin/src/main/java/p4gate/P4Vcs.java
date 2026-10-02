package p4gate;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.EditFileProvider;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsKey;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeProvider;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vcs.checkin.CheckinEnvironment;
import com.intellij.openapi.vcs.rollback.RollbackEnvironment;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.vcsUtil.VcsUtil;
import kotlinx.coroutines.CompletableJob;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.JobKt;
import kotlinx.coroutines.SupervisorKt;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Registers the plugin's p4 command line as a version control system, so the platform's own Local Changes
 * view, Commit, Rollback and auto-checkout work on a Perforce workspace. Every operation still runs through
 * {@link P4Cli}, so nothing here bypasses the connection diagnosis the tool window already does.
 * The name is "Perforce" on purpose: existing {@code .idea/vcs.xml} mappings of JetBrains' own Perforce plugin
 * carry over, and plugin.xml declares the two plugins incompatible so they are never enabled together.
 */
public final class P4Vcs extends AbstractVcs {
    public static final String NAME = "Perforce";
    private static final VcsKey KEY = createKey(NAME);

    private final P4ChangeProvider changeProvider;
    private final P4EditFileProvider editFileProvider;
    private volatile P4VFSListener vfsListener;
    private CompletableJob listenerJob;

    public P4Vcs(Project project) {
        super(project, NAME);
        changeProvider = new P4ChangeProvider(this);
        editFileProvider = new P4EditFileProvider(this);
    }

    /** Called when the project maps a root to Perforce: start following IDE renames, moves, deletes and creations. */
    @Override
    protected synchronized void activate() {
        if (vfsListener != null) return;
        // the listener lives as long as its scope: a child of the project service's, cancelled on deactivate
        CoroutineScope parent = P4Project.get(getProject()).scope();
        listenerJob = SupervisorKt.SupervisorJob(JobKt.getJob(parent.getCoroutineContext()));
        vfsListener = P4VFSListener.create(this, CoroutineScopeKt.CoroutineScope(parent.getCoroutineContext().plus(listenerJob)));
    }

    @Override
    protected synchronized void deactivate() {
        vfsListener = null;
        if (listenerJob != null) listenerJob.cancel(null);
        listenerJob = null;
    }

    /** For tests: the file listener while the VCS is active, else null. */
    P4VFSListener vfsListener() {
        return vfsListener;
    }

    /** The key, without a project: what the application-level root checker reports. */
    public static VcsKey getKey() {
        return KEY;
    }

    @Override
    public @NotNull String getDisplayName() {
        return NAME;
    }

    @Override
    public @NotNull String getShortName() {
        return NAME;
    }

    /** Roots come from {@link P4RootChecker}, never from "every content root belongs to us". */
    @Override
    public boolean needsLegacyDefaultMappings() {
        return false;
    }

    @Override
    protected @NotNull CheckinEnvironment createCheckinEnvironment() {
        return new P4CheckinEnvironment(this);
    }

    @Override
    protected @NotNull RollbackEnvironment createRollbackEnvironment() {
        return new P4RollbackEnvironment(this);
    }

    @Override
    public @NotNull ChangeProvider getChangeProvider() {
        return changeProvider;
    }

    @Override
    public EditFileProvider getEditFileProvider() {
        return editFileProvider;
    }

    /** Local paths p4 should act on for one platform change: the file before and after the change. */
    static List<String> fileArgs(Change change) {
        List<String> paths = new ArrayList<>(2);
        addPath(paths, change.getBeforeRevision());
        addPath(paths, change.getAfterRevision());
        return paths;
    }

    private static void addPath(List<String> paths, ContentRevision revision) {
        if (revision == null) return;
        String path = revision.getFile().getPath();
        if (!paths.contains(path)) paths.add(path);
    }

    /**
     * After p4 changed files on disk (writable bit, restored content, files coming back): refresh them in the VFS
     * synchronously, then let Local Changes re-read their state.
     */
    static void refresh(Project project, List<String> localPaths) {
        if (localPaths.isEmpty() || project.isDisposed()) return;
        File[] files = new File[localPaths.size()];
        List<FilePath> filePaths = new ArrayList<>(localPaths.size());
        for (int i = 0; i < files.length; i++) {
            files[i] = new File(localPaths.get(i));
            filePaths.add(VcsUtil.getFilePath(localPaths.get(i), false));
        }
        VfsUtil.markDirtyAndRefresh(false, false, false, files);
        VcsDirtyScopeManager.getInstance(project).filePathsDirty(filePaths, null);
    }
}
