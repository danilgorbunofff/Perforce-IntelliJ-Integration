package p4gate;

import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.rollback.RollbackEnvironment;
import com.intellij.openapi.vcs.rollback.RollbackProgressListener;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The platform's Rollback action: p4 revert -k, which drops the changelist entry and keeps the file as it is
 * on disk. That is what "rollback" means for a Perforce workspace — the user's edits are never thrown away
 * without being asked (the tool window's Discard is the destructive one, behind a confirmation).
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

    /** Perforce has no checked-out flag to restore: a modified file is simply re-synced by the user. */
    @Override
    public void rollbackModifiedWithoutCheckout(@NotNull List<? extends VirtualFile> files,
                                                @NotNull List<? super VcsException> errors,
                                                @NotNull RollbackProgressListener listener) {
    }

    /** `p4 revert -k` reverts the listed files from whichever changelist holds them, so no lookup is needed. */
    private void revert(List<String> paths, List<? super VcsException> errors, RollbackProgressListener listener) {
        if (paths.isEmpty()) return;
        P4Cli cli = P4Project.openCli(vcs.getProject());
        if (cli == null) {
            errors.add(new VcsException("No p4 executable is configured for this project."));
            return;
        }
        listener.determinate();
        String args = P4Args.file(cli.workdir(), "p4ii-revert", paths);
        try {
            P4Cli.Tagged result = cli.tagged(null, () -> {
                listener.checkCanceled();
                return false;
            }, "revert", "-k", "-x", args);
            if (result.error() != null) errors.add(new VcsException(result.error()));
        } finally {
            P4Args.delete(args);
        }
        LocalFileSystem vfs = LocalFileSystem.getInstance();
        for (String path : paths) {
            VirtualFile file = vfs.refreshAndFindFileByPath(path);
            if (file != null) file.refresh(false, false);
        }
    }
}
