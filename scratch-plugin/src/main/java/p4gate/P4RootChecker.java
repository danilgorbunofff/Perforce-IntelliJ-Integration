package p4gate;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsKey;
import com.intellij.openapi.vcs.VcsRootChecker;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Decides which directories belong to Perforce without asking the server: a directory that holds, or is under,
 * a .p4config file is part of a workspace — the same rule p4 itself uses to resolve a client from the workdir.
 * This is what keeps a project that is not a Perforce workspace free of Perforce change lists.
 */
public final class P4RootChecker extends VcsRootChecker {

    private static final String P4CONFIG = ".p4config";

    private final P4Vcs vcs;

    public P4RootChecker(P4Vcs vcs) {
        this.vcs = vcs;
    }

    @Override
    public @NotNull VcsKey getSupportedVcs() {
        return vcs.getKeyInstanceMethod();
    }

    @Override
    public boolean isVcsDir(@NotNull String dirName) {
        return P4CONFIG.equalsIgnoreCase(dirName);
    }

    @Override
    public boolean isRoot(@NotNull VirtualFile dir) {
        return dir.isDirectory() && (dir.findChild(P4CONFIG) != null || hasConfigInAncestors(dir));
    }

    @Override
    public boolean validateRoot(@NotNull VirtualFile dir) {
        return isRoot(dir);
    }

    @Override
    public boolean shouldAlwaysRunInitialDetection() {
        return true;
    }

    /** A project root is mapped to Perforce when it sits in a workspace; everything else is left alone. */
    @Override
    public @NotNull Collection<VirtualFile> detectProjectMappings(@NotNull Project project,
                                                                 @NotNull Collection<VirtualFile> contentRoots,
                                                                 @NotNull Set<VirtualFile> excluded) {
        List<VirtualFile> roots = new ArrayList<>();
        for (VirtualFile root : contentRoots) {
            if (!excluded.contains(root) && isRoot(root)) roots.add(root);
        }
        return roots;
    }

    private static boolean hasConfigInAncestors(VirtualFile dir) {
        for (VirtualFile parent = dir.getParent(); parent != null; parent = parent.getParent()) {
            if (parent.findChild(P4CONFIG) != null) return true;
        }
        return false;
    }
}
