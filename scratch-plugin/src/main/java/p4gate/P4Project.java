package p4gate;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import kotlinx.coroutines.CoroutineScope;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Per-project p4 state: which executable, which working directory (where P4CONFIG resolution starts),
 *  and the session's env override. Nothing here is JVM-global, so two open projects never share a connection. */
@Service(Service.Level.PROJECT)
public final class P4Project {
    private static final String KEY_EXE = "p4gate.executable";
    private static final String KEY_DIR = "p4gate.workdir";
    private static final Logger LOG = Logger.getInstance(P4Project.class);

    private final Project project;
    private final CoroutineScope scope;
    private final java.util.concurrent.atomic.AtomicReference<Object> busy = new java.util.concurrent.atomic.AtomicReference<>(); // owner of the running exclusive operation, or null
    private volatile P4Cli cli;

    /** The platform injects the scope: cancelled when the project closes or the plugin is unloaded. */
    public P4Project(Project project, CoroutineScope scope) {
        this.project = project;
        this.scope = scope;
        PropertiesComponent props = PropertiesComponent.getInstance(project);
        String exe = props.getValue(KEY_EXE, System.getProperty("p4.executable", "p4"));
        String base = project.getBasePath();
        String dir = props.getValue(KEY_DIR, base != null ? base : System.getProperty("user.home"));
        this.cli = new P4Cli(exe, dir, Map.of());
    }

    public static P4Project get(Project project) {
        return project.getService(P4Project.class);
    }

    public Project project() { return project; }

    /** For components that live as long as the project, e.g. the VCS file listener. */
    public CoroutineScope scope() { return scope; }

    public P4Cli cli() { return cli; }

    /** The p4 command line of this project, or null when no executable is configured. */
    public static P4Cli openCli(Project project) {
        P4Project service = get(project);
        if (service == null) return null;
        P4Cli cli = service.cli();
        String exe = cli.executable();
        return exe == null || exe.isBlank() ? null : cli;
    }

    /** Persists executable + working dir for this project; keeps the current env override. */
    public void configure(String executable, String workdir) {
        PropertiesComponent props = PropertiesComponent.getInstance(project);
        props.setValue(KEY_EXE, executable);
        props.setValue(KEY_DIR, workdir);
        cli = new P4Cli(executable, workdir, cli.envOverride());
    }

    /** Session-only overrides (e.g. imported from a P4CONFIG file) applied to every p4 call of THIS project. */
    public void setEnvOverride(Map<String, String> env) {
        cli = new P4Cli(cli.executable(), cli.workdir(), env);
    }

    /** After p4 changed workspace state outside the VCS actions (tool window): Local Changes re-reads p4. */
    public void vcsDirty() {
        if (!project.isDisposed()) VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
    }

    /** Runs on the EDT unless the project has been closed meanwhile. */
    public void ui(Runnable r) {
        ApplicationManager.getApplication().invokeLater(r, project.getDisposed());
    }

    /**
     * Runs p4 work as a platform background task (progress in the status bar, pooled thread, cancellable when allowed).
     * exclusive = mutating operation: at most one at a time per project, so a double click can never submit twice.
     * @return false when an exclusive operation is already running (nothing was started)
     */
    public boolean background(String title, boolean cancellable, boolean exclusive, Consumer<ProgressIndicator> work) {
        Object token = new Object();
        if (exclusive && !busy.compareAndSet(null, token)) {
            return false;
        }
        new Task.Backgroundable(project, title, cancellable) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    work.accept(indicator);
                } catch (ProcessCanceledException e) {
                    throw e;
                } catch (RuntimeException e) {
                    // a failure is shown to the user, never left as an IDE error report with a frozen panel
                    LOG.warn(title + " failed", e);
                    ui(() -> Messages.showErrorDialog(project, title + " failed:\n" + e, "Perforce"));
                } finally {
                    // released when the WORK ends, not in onFinished (that runs on the EDT after the current event):
                    // a follow-up the work queued with ui() - "Replace shelf?" then shelve -f - must find it free
                    if (exclusive) busy.compareAndSet(token, null);
                }
            }

            @Override
            public void onFinished() {
                // also covers a task that never ran; only ever releases this task's own hold
                if (exclusive) busy.compareAndSet(token, null);
            }
        }.queue();
        return true;
    }
}
