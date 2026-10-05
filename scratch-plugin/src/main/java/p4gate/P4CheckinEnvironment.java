package p4gate;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.CommitContext;
import com.intellij.openapi.vcs.checkin.CheckinEnvironment;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns the platform's Commit action into a p4 submit of exactly the selected files with the commit message
 * ({@link P4Ops#submit}), and the two "schedule" calls the platform makes when a file appears or disappears into
 * p4 add / p4 reconcile -d. Each returns only when p4 has finished, with p4's own errors.
 */
final class P4CheckinEnvironment implements CheckinEnvironment {

    private final P4Vcs vcs;

    P4CheckinEnvironment(P4Vcs vcs) {
        this.vcs = vcs;
    }

    @Override
    public String getCheckinOperationName() {
        return "Submit";
    }

    @Override
    public String getHelpId() {
        return null;
    }

    @Override
    public boolean isRefreshAfterCommitNeeded() {
        return true;
    }

    @Override
    public List<VcsException> scheduleUnversionedFilesForAddition(@NotNull List<? extends VirtualFile> files) {
        List<String> paths = new ArrayList<>(files.size());
        for (VirtualFile file : files) {
            if (!paths.contains(file.getPath())) paths.add(file.getPath());
        }
        if (paths.isEmpty()) return List.of();
        List<VcsException> errors = P4Sync.run(vcs.getProject(), "Perforce: p4 add", false, (cli, indicator) -> {
            indicator.setText("p4 add");
            String error = P4Ops.add(cli, paths, indicator::isCanceled);
            if (error != null) throw new VcsException(error);
        });
        P4Vcs.refresh(vcs.getProject(), paths);
        return errors;
    }

    /**
     * p4 delete needs the file to still exist, so a file the user removed from disk is recorded with
     * {@code p4 reconcile -d} instead: that opens files missing from the workspace for delete, which is
     * exactly the state the IDE is reporting.
     */
    @Override
    public List<VcsException> scheduleMissingFileForDeletion(@NotNull List<? extends FilePath> files) {
        List<String> paths = new ArrayList<>(files.size());
        for (FilePath file : files) {
            if (!paths.contains(file.getPath())) paths.add(file.getPath());
        }
        if (paths.isEmpty()) return List.of();
        List<VcsException> errors = P4Sync.run(vcs.getProject(), "Perforce: p4 reconcile -d", false, (cli, indicator) -> {
            indicator.setText("p4 reconcile -d");
            String error = P4Ops.deleteMissing(cli, paths, indicator::isCanceled);
            if (error != null) throw new VcsException(error);
        });
        P4Vcs.refresh(vcs.getProject(), paths);
        return errors;
    }

    /**
     * The overload the platform's commit pipeline actually calls (IDEA 2025.3: commit(changes, message, CommitContext,
     * feedback)). The two-argument commit(changes, message) is only an entry default that delegates to this one, and
     * the next default in the chain does nothing: overriding the two-argument form alone made the IDE report
     * "N files committed" without ever running p4 submit.
     */
    @Override
    public List<VcsException> commit(@NotNull List<? extends Change> changes, @NotNull String commitMessage,
                                     @NotNull CommitContext commitContext, @NotNull Set<? super String> feedback) {
        Project project = vcs.getProject();
        List<String> paths = new ArrayList<>();
        for (Change change : changes) {
            for (String path : P4Vcs.fileArgs(change)) {
                if (!paths.contains(path)) paths.add(path);
            }
        }
        if (paths.isEmpty()) return List.of();
        // not cancellable: P4Ops.submit only checks cancellation before the submit itself starts
        List<VcsException> errors = P4Sync.run(project, "Perforce: submit", false, (cli, indicator) -> {
            indicator.setText("p4 submit (" + paths.size() + " files)");
            P4Ops.SubmitResult result = P4Ops.submit(cli, paths, commitMessage, indicator::isCanceled);
            if (!result.ok()) throw new VcsException(result.error());
            indicator.setText("Submitted change " + result.submitted());
        });
        P4Vcs.refresh(project, paths); // submitted files become read-only again; a failed one may have moved CL
        return errors;
    }
}
