package p4gate;

import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The workspace copy of a file, tagged with the depot revision it was synced to. */
final class P4ContentRevision implements ContentRevision {

    private final FilePath file;
    private final VcsRevisionNumber revision;

    P4ContentRevision(FilePath file, VcsRevisionNumber revision) {
        this.file = file;
        this.revision = revision;
    }

    @Override
    public @NotNull VcsRevisionNumber getRevisionNumber() {
        return revision;
    }

    @Override
    public @NotNull String getContent() throws VcsException {
        try {
            return Files.readString(Path.of(file.getPath()));
        } catch (IOException e) {
            throw new VcsException("Cannot read " + file.getPath(), e);
        }
    }

    @Override
    public @NotNull FilePath getFile() {
        return file;
    }

    @Override
    public String toString() {
        return file.getPath() + " (" + revision.asString() + ")";
    }
}
