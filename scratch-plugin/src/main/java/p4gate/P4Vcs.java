package p4gate;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeProvider;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.checkin.CheckinEnvironment;
import com.intellij.openapi.vcs.rollback.RollbackEnvironment;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers the plugin's p4 command line as a version control system, so the platform's own Local Changes
 * view, Commit action and Rollback action work on a Perforce workspace. Every operation still runs through
 * {@link P4Cli}, so nothing here bypasses the connection diagnosis the tool window already does.
 */
public final class P4Vcs extends AbstractVcs {

    private final P4ChangeProvider changeProvider;

    public P4Vcs(Project project) {
        super(project, "Perforce");
        changeProvider = new P4ChangeProvider(this);
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Perforce";
    }

    @Override
    public @NotNull String getShortName() {
        return "Perforce";
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
}
