package p4gate;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.AbstractVcsHelper;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.VcsVFSListener;
import com.intellij.openapi.vfs.VirtualFile;
import kotlinx.coroutines.CoroutineScope;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Keeps Perforce in step with what the user does to files in the IDE: a file created in the IDE is offered for
 * {@code p4 add}, a deleted one is opened for delete, a renamed or moved one (including refactorings) becomes a
 * real {@code p4 move} — so the depot history follows the file instead of seeing a delete plus an unrelated add.
 * The platform asks first, per its Version Control "Confirmation" settings.
 */
final class P4VFSListener extends VcsVFSListener {

    private P4VFSListener(P4Vcs vcs, CoroutineScope scope) {
        super(vcs, scope);
    }

    static P4VFSListener create(P4Vcs vcs, CoroutineScope scope) {
        P4VFSListener listener = new P4VFSListener(vcs, scope);
        listener.installListeners();
        return listener;
    }

    /** For tests: the listener processes VFS events asynchronously. */
    void waitForEvents() {
        waitForEventsProcessedInTestMode();
    }

    @Override
    protected @NotNull String getAddTitle() {
        return "Add Files to Perforce";
    }

    @Override
    protected @NotNull String getSingleFileAddTitle() {
        return "Add File to Perforce";
    }

    @Override
    protected @NotNull String getSingleFileAddPromptTemplate() {
        return "Do you want to add the following file to Perforce (p4 add)?\n{0}";
    }

    @Override
    protected @NotNull String getDeleteTitle() {
        return "Delete Files in Perforce";
    }

    @Override
    protected @NotNull String getSingleFileDeleteTitle() {
        return "Delete File in Perforce";
    }

    @Override
    protected @NotNull String getSingleFileDeletePromptTemplate() {
        return "Do you want to open the following file for delete in Perforce (p4 delete)?\n{0}";
    }

    @Override
    protected void performAdding(@NotNull Collection<VirtualFile> addedFiles, @NotNull Map<VirtualFile, VirtualFile> copyFromMap) {
        List<String> paths = new ArrayList<>();
        for (VirtualFile f : addedFiles) {
            if (!f.isDirectory() && f.isInLocalFileSystem()) paths.add(f.getPath()); // p4 versions files, not directories
        }
        run("Perforce: p4 add", paths, cli -> P4Ops.addNew(cli, paths, () -> false));
    }

    @Override
    protected void performDeletion(@NotNull List<FilePath> filesToDelete) {
        List<String> paths = new ArrayList<>();
        for (FilePath f : filesToDelete) {
            if (!f.isDirectory()) paths.add(f.getPath());
        }
        run("Perforce: p4 delete", paths, cli -> P4Ops.deleted(cli, paths, () -> false));
    }

    @Override
    protected void performMoveRename(@NotNull List<MovedFileInfo> movedFiles) {
        List<String[]> pairs = new ArrayList<>();
        List<String> touched = new ArrayList<>();
        for (MovedFileInfo m : movedFiles) {
            if (m.getOldPath().isDirectory()) continue; // a directory move arrives as its files as well
            pairs.add(new String[]{m.getOldPath().getPath(), m.getNewPath().getPath()});
            touched.add(m.getOldPath().getPath());
            touched.add(m.getNewPath().getPath());
        }
        run("Perforce: p4 move", touched, cli -> P4Ops.moved(cli, pairs, () -> false));
    }

    /** Off the UI thread: inline. On it (possibly inside a write action): as a background task, never blocking it. */
    private void run(String title, List<String> paths, Function<P4Cli, String> op) {
        if (paths.isEmpty()) return;
        Project project = myProject;
        P4Sync.Work work = (cli, indicator) -> {
            indicator.setText(title);
            String error = op.apply(cli);
            if (error != null) throw new VcsException(error);
        };
        if (ApplicationManager.getApplication().isDispatchThread()) {
            P4Project.get(project).background(title, false, false, indicator -> finish(project, title, paths,
                    P4Sync.run(project, title, false, work)));
        } else {
            finish(project, title, paths, P4Sync.run(project, title, false, work));
        }
    }

    private static void finish(Project project, String title, List<String> paths, List<VcsException> errors) {
        P4Vcs.refresh(project, paths);
        if (!errors.isEmpty()) {
            ApplicationManager.getApplication().invokeLater(
                    () -> AbstractVcsHelper.getInstance(project).showErrors(errors, title), project.getDisposed());
        }
    }
}
