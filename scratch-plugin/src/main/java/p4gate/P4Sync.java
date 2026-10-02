package p4gate;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Runs the Perforce commands behind the platform's VCS actions off the UI thread.
 *
 * The platform calls {@code commit} and the two schedule methods from a worker thread and reads the errors
 * they return, so the work runs inline there — that way a failed submit is reported to the user. On the UI
 * thread (any call the platform makes from the EDT) the work is queued instead, because no p4 process may
 * ever run on the UI thread.
 */
final class P4Sync {

    private P4Sync() {
    }

    /** What a VCS action does once a p4 command line is available. */
    interface Work {
        void run(P4Cli cli, ProgressIndicator indicator) throws VcsException;
    }

    /** Queues {@code work} as a background task; anything it throws lands in {@code errors}, never in the IDE log. */
    static void background(Project project, String title, boolean cancellable, List<VcsException> errors, Work work) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            run(project, errors, work, new EmptyProgressIndicator());
            return;
        }
        new Task.Backgroundable(project, title, cancellable) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                P4Sync.run(project, errors, work, indicator);
            }
        }.queue();
    }

    private static void run(Project project, List<VcsException> errors, Work work, ProgressIndicator indicator) {
        indicator.setIndeterminate(true);
        P4Cli cli = P4Project.openCli(project);
        if (cli == null) {
            errors.add(new VcsException("No p4 executable is configured for this project."));
            return;
        }
        try {
            work.run(cli, indicator);
        } catch (VcsException e) {
            errors.add(e);
        } catch (RuntimeException e) {
            errors.add(new VcsException(e));
        }
    }
}
