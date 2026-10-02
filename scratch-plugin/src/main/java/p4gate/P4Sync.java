package p4gate;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runs the p4 work behind a platform VCS action and returns its errors only once the work is DONE, so the
 * platform reports a failed submit/edit/revert instead of a success.
 * On a worker thread (where the platform calls commit and rollback) the work runs inline under the caller's
 * progress indicator. On the UI thread it runs under a modal, synchronous progress dialog: no p4 process ever
 * runs on the UI thread, and the caller still gets the real result.
 */
final class P4Sync {

    private P4Sync() {
    }

    /** What a VCS action does once a p4 command line is available. */
    interface Work {
        void run(P4Cli cli, ProgressIndicator indicator) throws VcsException;
    }

    static List<VcsException> run(Project project, String title, boolean cancellable, Work work) {
        List<VcsException> errors = Collections.synchronizedList(new ArrayList<>());
        if (ApplicationManager.getApplication().isDispatchThread()) {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    () -> execute(project, errors, work, current()), title, cancellable, project);
        } else {
            execute(project, errors, work, current());
        }
        return new ArrayList<>(errors);
    }

    private static ProgressIndicator current() {
        ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
        return indicator != null ? indicator : new EmptyProgressIndicator();
    }

    private static void execute(Project project, List<VcsException> errors, Work work, ProgressIndicator indicator) {
        P4Cli cli = P4Project.openCli(project);
        if (cli == null) {
            errors.add(new VcsException("No p4 executable is configured for this project."));
            return;
        }
        try {
            work.run(cli, indicator);
        } catch (VcsException e) {
            errors.add(e);
        } catch (ProcessCanceledException e) {
            errors.add(new VcsException("Cancelled."));
        } catch (RuntimeException e) {
            errors.add(new VcsException(e));
        }
    }
}
