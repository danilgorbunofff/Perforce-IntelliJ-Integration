package p4gate;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vcs.checkin.CheckinEnvironment;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the platform's Commit action into p4 submit, and the two "schedule" calls the platform makes when a
 * file appears or disappears into p4 add / p4 reconcile -d.
 *
 * The platform assumes these methods return before the work is done, so each one queues a background task and
 * fills the returned list from that task.
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

    /** p4 add: the platform passes the new files, including the not-yet-added parents it expects us to add too. */
    @Override
    public List<VcsException> scheduleUnversionedFilesForAddition(@NotNull List<? extends VirtualFile> files) {
        List<VcsException> errors = new ArrayList<>();
        List<String> paths = new ArrayList<>(files.size());
        for (VirtualFile file : files) {
            if (!paths.contains(file.getPath())) paths.add(file.getPath());
        }
        if (paths.isEmpty()) return errors;
        P4Sync.background(vcs.getProject(), "Perforce add", false, errors, (cli, indicator) -> {
            indicator.setText("p4 add");
            String args = P4Args.file(cli.workdir(), "p4ii-add", paths);
            try {
                P4Cli.Tagged result = cli.tagged(null, () -> false, "add", "-x", args);
                if (result.error() != null) errors.add(new VcsException(result.error()));
            } finally {
                P4Args.delete(args);
            }
        });
        return errors;
    }

    /**
     * p4 delete needs the file to still exist, so a file the user removed from disk is recorded with
     * {@code p4 reconcile -d} instead: that opens files missing from the workspace for delete, which is
     * exactly the state the IDE is reporting.
     */
    @Override
    public List<VcsException> scheduleMissingFileForDeletion(@NotNull List<? extends FilePath> files) {
        List<VcsException> errors = new ArrayList<>();
        List<String> paths = new ArrayList<>(files.size());
        for (FilePath file : files) {
            if (!paths.contains(file.getPath())) paths.add(file.getPath());
        }
        if (paths.isEmpty()) return errors;
        P4Sync.background(vcs.getProject(), "Perforce delete", false, errors, (cli, indicator) -> {
            indicator.setText("p4 reconcile -d");
            String args = P4Args.file(cli.workdir(), "p4ii-delete", paths);
            try {
                P4Cli.Tagged result = cli.tagged(null, () -> false, "reconcile", "-d", "-x", args);
                if (result.error() != null) errors.add(new VcsException(result.error()));
            } finally {
                P4Args.delete(args);
            }
        });
        return errors;
    }

    @Override
    public List<VcsException> commit(@NotNull List<? extends Change> changes, @NotNull String commitMessage) {
        List<VcsException> errors = new ArrayList<>();
        Project project = vcs.getProject();
        P4Sync.background(project, "Perforce submit", false, errors, (cli, indicator) -> {
            // a submit is per changelist: group the selected files by the changelist p4 has them open in
            Map<Long, List<String>> byChange = new java.util.LinkedHashMap<>();
            Set<String> notOpened = new LinkedHashSet<>();
            for (Change change : changes) {
                List<String> paths = P4Vcs.fileArgs(change);
                if (paths.isEmpty()) continue;
                long id = openedChange(cli, indicator, paths);
                if (id < 0) {
                    notOpened.addAll(paths);
                } else {
                    byChange.computeIfAbsent(id, key -> new ArrayList<>()).addAll(paths);
                }
            }
            for (Map.Entry<Long, List<String>> entry : byChange.entrySet()) {
                submit(cli, indicator, entry.getKey(), entry.getValue(), commitMessage, errors);
            }
            if (!notOpened.isEmpty()) {
                errors.add(new VcsException("Not open in Perforce, so nothing was submitted for: "
                        + String.join(", ", notOpened) + "\nUse Add in the Perforce tool window first."));
            }
            if (errors.isEmpty()) {
                ApplicationManager.getApplication().invokeLater(
                        () -> VcsDirtyScopeManager.getInstance(project).markEverythingDirty());
            }
        });
        return errors;
    }

    /**
     * The default changelist must be given its description on the command line ({@code -d}); a numbered
     * changelist is submitted with the description it already carries, so nothing has to open an editor.
     */
    private static void submit(P4Cli cli, ProgressIndicator indicator, long change, List<String> paths,
                               String message, List<VcsException> errors) {
        List<String> args = new ArrayList<>(List.of("submit"));
        if (change > 0) {
            args.add("-c");
            args.add(Long.toString(change));
        } else {
            if (message == null || message.isBlank()) {
                errors.add(new VcsException("The default changelist needs a description to be submitted."));
                return;
            }
            args.add("-d");
            args.add(message);
        }
        indicator.setText(change > 0 ? "p4 submit -c " + change : "p4 submit (default changelist)");
        String file = P4Args.file(cli.workdir(), "p4ii-submit", paths);
        args.add("-x");
        args.add(file);
        try {
            P4Cli.Tagged result = cli.tagged(null, () -> false, args.toArray(String[]::new));
            if (result.error() != null) errors.add(new VcsException(result.error()));
        } finally {
            P4Args.delete(file);
        }
    }

    /** The changelist the files are open in, 0 for the default changelist, -1 when they are not open at all. */
    private static long openedChange(P4Cli cli, ProgressIndicator indicator, List<String> paths) {
        indicator.setText("p4 opened");
        String args = P4Args.file(cli.workdir(), "p4ii-opened", paths);
        try {
            P4Cli.Tagged result = cli.tagged(P4Cli.QUERY_TIMEOUT, () -> false, "opened", "-x", args);
            for (Map<String, String> record : result.records()) {
                P4Data.OpenedFile file = P4Data.openedFile(record);
                if (file != null) return file.change();
            }
            return -1L;
        } finally {
            P4Args.delete(args);
        }
    }
}
