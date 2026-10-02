package p4gate;

import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.rollback.RollbackEnvironment;
import com.intellij.openapi.vcs.rollback.RollbackProgressListener;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The platform's Rollback action: {@code p4 revert}, which drops the open state and restores the depot content,
 * exactly what the platform's own Rollback confirmation tells the user. A file deleted from disk is restored the
 * same way. Files opened for add stay on disk, unopened. The non-destructive {@code revert -k} stays in the
 * tool window as "Revert (keep files)".
 */
final class P4RollbackEnvironment implements RollbackEnvironment {

    private final P4Vcs vcs;

    P4RollbackEnvironment(P4Vcs vcs) {
        this.vcs = vcs;
    }

    @Override
    public String getRollbackOperationName() {
        return "Revert";
    }

    @Override
    public void rollbackChanges(@NotNull List<? extends Change> changes, @NotNull List<VcsException> errors,
                                @NotNull RollbackProgressListener listener) {
        List<String> paths = new ArrayList<>();
        for (Change change : changes) {
            for (String path : P4Vcs.fileArgs(change)) {
                if (!paths.contains(path)) paths.add(path);
            }
        }
        revert(paths, errors, listener);
    }

    @Override
    public void rollbackMissingFileDeletion(@NotNull List<? extends FilePath> files,
                                            @NotNull List<? super VcsException> errors,
                                            @NotNull RollbackProgressListener listener) {
        List<String> paths = new ArrayList<>(files.size());
        for (FilePath file : files) {
            if (!paths.contains(file.getPath())) paths.add(file.getPath());
        }
        revert(paths, errors, listener);
    }

    /** Never reported: the change provider lists opened files only, and Perforce has no "modified, not opened" state. */
    @Override
    public void rollbackModifiedWithoutCheckout(@NotNull List<? extends VirtualFile> files,
                                                @NotNull List<? super VcsException> errors,
                                                @NotNull RollbackProgressListener listener) {
    }

    private void revert(List<String> paths, List<? super VcsException> errors, RollbackProgressListener listener) {
        if (paths.isEmpty()) return;
        listener.determinate();
        errors.addAll(P4Sync.run(vcs.getProject(), "Perforce: revert", true, (cli, indicator) -> {
            String error = P4Ops.revert(cli, paths, () -> {
                listener.checkCanceled();
                return false;
            });
            if (error != null) throw new VcsException(error);
        }));
        P4Vcs.refresh(vcs.getProject(), paths);
    }
}
