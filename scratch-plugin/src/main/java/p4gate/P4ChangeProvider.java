package p4gate;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.LocalFilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManagerGate;
import com.intellij.openapi.vcs.changes.ChangeProvider;
import com.intellij.openapi.vcs.changes.ChangelistBuilder;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vcs.changes.VcsDirtyScope;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Feeds the platform's Local Changes view from the two calls the plugin already trusts: {@code p4 opened}
 * says what the workspace holds, {@code p4 fstat} says which local path that depot path maps to. Nothing is
 * inferred from a directory scan, so a file the user never opened can never appear as changed.
 */
final class P4ChangeProvider implements ChangeProvider {

    private final P4Vcs vcs;

    P4ChangeProvider(P4Vcs vcs) {
        this.vcs = vcs;
    }

    /** Perforce reports an edited buffer through `p4 opened` like any other edit: document tracking adds nothing. */
    @Override
    public boolean isModifiedDocumentTrackingRequired() {
        return false;
    }

    @Override
    public void getChanges(@NotNull VcsDirtyScope scope, @NotNull ChangelistBuilder builder,
                           @NotNull ProgressIndicator progress, @NotNull ChangeListManagerGate gate)
            throws VcsException {
        progress.setIndeterminate(true);
        progress.setText("Collecting Perforce changes...");

        P4Cli cli = P4Project.openCli(vcs.getProject());
        if (cli == null) {
            throw new VcsException("No p4 executable is configured for this project.");
        }

        List<P4ClientFile> mapped;
        List<P4Data.OpenedFile> opened;
        if (scope.wasEveryThingDirty()) {
            // the whole workspace was invalidated: `p4 opened` (this client only) is small and complete
            P4Cli.Tagged result = cli.tagged(P4Cli.QUERY_TIMEOUT, () -> false, "opened");
            if (result.error() != null) throw new VcsException(result.error());
            opened = openedFiles(result);
            mapped = clientFiles(cli, depotFiles(opened));
        } else {
            List<String> dirty = localPaths(scope);
            if (dirty.isEmpty()) return;
            mapped = clientFiles(cli, dirty);
            List<String> depotPaths = P4ClientFile.depotPathsOf(mapped, dirty);
            if (depotPaths.isEmpty()) return;
            P4Data.Listing<P4Data.OpenedFile> listing = P4Data.openedFiles(cli, depotPaths);
            if (listing.error() != null) throw new VcsException(listing.error());
            opened = listing.items();
        }
        if (opened.isEmpty()) return;

        Map<String, String> localByDepot = P4ClientFile.byDepot(mapped);
        for (P4Data.OpenedFile file : opened) {
            progress.checkCanceled();
            String local = localByDepot.get(file.depotFile());
            if (local == null) continue;
            FilePath path = new LocalFilePath(local, false);
            FileStatus status = P4Status.of(file.action(), exists(path));
            ContentRevision before = status == FileStatus.ADDED ? null : new P4ContentRevision(path, P4RevisionNumber.HAVE);
            ContentRevision after = status == FileStatus.DELETED || status == FileStatus.DELETED_FROM_FS
                    ? null : CurrentContentRevision.create(path);
            builder.processChange(new Change(before, after, status), vcs.getKeyInstanceMethod());
        }
    }

    /** Local paths of everything the platform just invalidated; a dirty directory means everything under it. */
    private static List<String> localPaths(VcsDirtyScope scope) {
        List<String> paths = new ArrayList<>();
        for (FilePath path : scope.getDirtyFiles()) {
            String local = path.getPath();
            if (path.isDirectory() && !local.endsWith("...")) {
                local = local + (local.endsWith("/") || local.endsWith("\\") ? "..." : "/...");
            }
            if (!paths.contains(local)) paths.add(local);
        }
        return paths;
    }

    private static List<P4Data.OpenedFile> openedFiles(P4Cli.Tagged result) {
        List<P4Data.OpenedFile> files = new ArrayList<>(result.records().size());
        for (Map<String, String> record : result.records()) {
            P4Data.OpenedFile file = P4Data.openedFile(record);
            if (file != null) files.add(file);
        }
        return files;
    }

    private static List<String> depotFiles(List<P4Data.OpenedFile> opened) {
        List<String> depotPaths = new ArrayList<>(opened.size());
        for (P4Data.OpenedFile file : opened) {
            if (!depotPaths.contains(file.depotFile())) depotPaths.add(file.depotFile());
        }
        return depotPaths;
    }

    /** `p4 fstat` of the given paths, in one call: the client view mapping and nothing else. */
    private static List<P4ClientFile> clientFiles(P4Cli cli, List<String> paths) {
        if (paths.isEmpty()) return List.of();
        String args = P4Args.file(cli.workdir(), "p4ii-fstat", paths);
        try {
            P4Cli.Tagged result = cli.tagged(P4Cli.QUERY_TIMEOUT, () -> false, "fstat", "-T", "depotFile,clientFile", "-x", args);
            return P4ClientFile.parse(result.records());
        } finally {
            P4Args.delete(args);
        }
    }

    private static boolean exists(FilePath path) {
        try {
            return Files.exists(Path.of(path.getPath()));
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
