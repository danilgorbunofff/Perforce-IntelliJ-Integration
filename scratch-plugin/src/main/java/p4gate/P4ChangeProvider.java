package p4gate;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManagerGate;
import com.intellij.openapi.vcs.changes.ChangeProvider;
import com.intellij.openapi.vcs.changes.ChangelistBuilder;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vcs.changes.VcsDirtyScope;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Feeds the platform's Local Changes view from the files p4 says are open in this client ({@link P4OpenFile#all}:
 * two p4 spawns, whatever the size of the workspace or of the dirty scope). Nothing is inferred from a directory
 * scan, so a file the user never opened can never appear as changed. The platform's dirty scope only selects
 * which of those files are reported: every reported path must belong to it.
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
        progress.setText("Collecting Perforce changes...");
        P4Cli cli = P4Project.openCli(vcs.getProject());
        if (cli == null) throw new VcsException("No p4 executable is configured for this project.");

        P4Data.Listing<P4OpenFile> opened = P4OpenFile.all(cli, progress::isCanceled);
        progress.checkCanceled();
        if (opened.error() != null) throw new VcsException("Perforce: " + opened.error());

        Supplier<P4Cli> p4 = () -> P4Project.openCli(vcs.getProject());
        for (Entry e : plan(opened.items(), P4ChangeProvider::exists)) {
            progress.checkCanceled();
            FilePath beforePath = e.before() == null ? null : VcsUtil.getFilePath(e.before().localPath(), false);
            FilePath afterPath = e.afterLocal() == null ? null : VcsUtil.getFilePath(e.afterLocal(), false);
            boolean inScope = (beforePath != null && scope.belongsTo(beforePath)) || (afterPath != null && scope.belongsTo(afterPath));
            if (!inScope) continue;
            if (e.status() == null) {
                builder.processLocallyDeletedFile(afterPath);
                continue;
            }
            ContentRevision before = e.before() == null ? null : P4ContentRevision.of(beforePath, e.before().depotFile(),
                    e.before().type(), new P4RevisionNumber(e.before().haveRev()), p4);
            ContentRevision after = afterPath == null ? null : CurrentContentRevision.create(afterPath);
            builder.processChange(new Change(before, after, e.status()), vcs.getKeyInstanceMethod());
        }
    }

    /**
     * One reported item. {@code before} is the depot side (printed at #have), {@code afterLocal} the workspace file.
     * {@code status == null} means "opened, but gone from disk": a locally deleted file, not a change.
     */
    record Entry(P4OpenFile before, String afterLocal, FileStatus status) { }

    /** Pure mapping from opened files to what Local Changes shows. A move/add + move/delete pair is one rename. */
    static List<Entry> plan(List<P4OpenFile> files, Predicate<String> existsOnDisk) {
        Map<String, P4OpenFile> byDepot = new HashMap<>();
        for (P4OpenFile f : files) byDepot.put(f.depotFile(), f);
        Set<String> consumed = new HashSet<>();
        List<Entry> out = new ArrayList<>();
        for (P4OpenFile f : files) {
            if (f.action().equals("move/add")) {
                P4OpenFile from = byDepot.get(f.movedFile());
                if (from != null && from.action().equals("move/delete")) consumed.add(from.depotFile());
            }
        }
        for (P4OpenFile f : files) {
            if (consumed.contains(f.depotFile())) continue;
            boolean exists = existsOnDisk.test(f.localPath());
            FileStatus base = P4Status.of(f.action(), true);
            if (base == FileStatus.DELETED) {
                out.add(new Entry(f, null, FileStatus.DELETED));
            } else if (!exists) {
                out.add(new Entry(null, f.localPath(), null));
            } else if (f.action().equals("move/add") && byDepot.containsKey(f.movedFile()) && consumed.contains(f.movedFile())) {
                out.add(new Entry(byDepot.get(f.movedFile()), f.localPath(), FileStatus.MODIFIED));
            } else if (base == FileStatus.ADDED) {
                out.add(new Entry(null, f.localPath(), FileStatus.ADDED));
            } else {
                out.add(new Entry(f, f.localPath(), f.unresolved() ? FileStatus.MERGED_WITH_CONFLICTS : base));
            }
        }
        return out;
    }

    private static boolean exists(String path) {
        try {
            return Files.exists(Path.of(path));
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
