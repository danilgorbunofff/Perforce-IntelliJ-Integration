package p4gate;

import com.intellij.openapi.vcs.VcsKey;
import com.intellij.openapi.vcs.VcsRootChecker;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides which directories are Perforce roots without asking the server: a directory that directly holds a
 * P4CONFIG file. That is where p4 itself resolves the client from, and the platform's root detection walks up
 * from the project to find it, so a project nested inside a workspace is mapped to the workspace's config dir.
 * A project that is not in a workspace stays free of Perforce change lists.
 * An application-level extension: no constructor parameters (the platform cannot inject a project's VCS here).
 */
public final class P4RootChecker extends VcsRootChecker {

    @Override
    public @NotNull VcsKey getSupportedVcs() {
        return P4Vcs.getKey();
    }

    @Override
    public boolean isVcsDir(@NotNull String dirName) {
        return isConfigName(dirName, configNames(System.getenv("P4CONFIG")));
    }

    @Override
    public boolean isRoot(@NotNull VirtualFile dir) {
        if (!dir.isDirectory()) return false;
        for (String name : configNames(System.getenv("P4CONFIG"))) {
            VirtualFile child = dir.findChild(name);
            if (child != null && !child.isDirectory()) return true;
        }
        return false;
    }

    @Override
    public boolean validateRoot(@NotNull VirtualFile dir) {
        return isRoot(dir);
    }

    /** The P4CONFIG name from the IDE's environment (a bare file name only), then the two common names. */
    static List<String> configNames(String p4configEnv) {
        List<String> names = new ArrayList<>(3);
        if (p4configEnv != null && !p4configEnv.isBlank() && !p4configEnv.contains("/") && !p4configEnv.contains("\\")) {
            names.add(p4configEnv.strip());
        }
        for (String n : List.of(".p4config", "p4config.txt")) {
            if (!isConfigName(n, names)) names.add(n);
        }
        return names;
    }

    static boolean isConfigName(String name, List<String> names) {
        for (String n : names) {
            if (n.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
