package p4gate;

import com.intellij.openapi.vcs.EditFileProvider;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto-checkout: when the user starts typing into a read-only workspace file, the platform asks this provider to
 * make it writable, and it runs {@code p4 edit} — so the file is opened in Perforce (and shows in Local Changes)
 * instead of merely losing its read-only bit, which would leave an edit Perforce does not know about.
 */
final class P4EditFileProvider implements EditFileProvider {

    private final P4Vcs vcs;

    P4EditFileProvider(P4Vcs vcs) {
        this.vcs = vcs;
    }

    @Override
    public void editFiles(VirtualFile[] files) throws VcsException {
        List<String> paths = new ArrayList<>(files.length);
        for (VirtualFile file : files) {
            if (file.isInLocalFileSystem() && !paths.contains(file.getPath())) paths.add(file.getPath());
        }
        if (paths.isEmpty()) return;
        List<VcsException> errors = P4Sync.run(vcs.getProject(), "Perforce: p4 edit", true, (cli, indicator) -> {
            indicator.setText("p4 edit (" + paths.size() + " files)");
            String error = P4Ops.edit(cli, paths, indicator::isCanceled);
            if (error != null) throw new VcsException(error);
        });
        P4Vcs.refresh(vcs.getProject(), paths); // synchronous: the platform checks isWritable() right after this
        if (!errors.isEmpty()) throw errors.get(0);
    }

    @Override
    public String getRequestText() {
        return "Would you like to open these files for edit in Perforce (p4 edit)?";
    }
}
