package p4gate;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
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

    private final Project project;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile P4Cli cli;

    public P4Project(Project project) {
        this.project = project;
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

    public P4Cli cli() { return cli; }

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
        if (exclusive && !busy.compareAndSet(false, true)) {
            return false;
        }
        new Task.Backgroundable(project, title, cancellable) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                work.accept(indicator);
            }

            @Override
            public void onFinished() {
                if (exclusive) busy.set(false);
            }
        }.queue();
        return true;
    }
}
