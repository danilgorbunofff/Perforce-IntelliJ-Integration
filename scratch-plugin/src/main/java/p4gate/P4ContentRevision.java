package p4gate;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.BinaryContentRevision;
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * The depot content of a file at the revision the workspace has ({@code //depot/path#have}), fetched lazily with
 * {@code p4 print -q} — Perforce keeps no pristine copy on disk, so reading the workspace file here would make
 * every diff empty. Fetched once per revision object and cached.
 */
class P4ContentRevision implements ByteBackedContentRevision {
    private static final Duration PRINT_TIMEOUT = Duration.ofMinutes(5);

    private final FilePath file;
    private final String depotFile;
    private final P4RevisionNumber revision;
    private final Supplier<P4Cli> cli;
    private volatile byte[] content;

    P4ContentRevision(FilePath file, String depotFile, P4RevisionNumber revision, Supplier<P4Cli> cli) {
        this.file = file;
        this.depotFile = depotFile;
        this.revision = revision;
        this.cli = cli;
    }

    /** A text or a binary revision, by the p4 filetype. */
    static P4ContentRevision of(FilePath file, String depotFile, String type, P4RevisionNumber revision, Supplier<P4Cli> cli) {
        return P4OpenFile.isBinaryType(type)
                ? new Binary(file, depotFile, revision, cli)
                : new P4ContentRevision(file, depotFile, revision, cli);
    }

    /** The revision spec printed: always #have, the revision the workspace was synced to. */
    String spec() {
        return depotFile + "#have";
    }

    @Override
    public byte @NotNull [] getContentAsBytes() throws VcsException {
        byte[] bytes = content;
        if (bytes == null) {
            bytes = print();
            content = bytes;
        }
        return bytes;
    }

    private byte[] print() throws VcsException {
        P4Cli p4 = cli.get();
        if (p4 == null) throw new VcsException("No p4 executable is configured for this project.");
        boolean inIde = ApplicationManager.getApplication() != null;
        P4Cli.Raw r = p4.runRaw(PRINT_TIMEOUT, () -> {
            if (inIde) ProgressManager.checkCanceled(); // the diff/commit dialog's own progress cancels the print
            return false;
        }, "print", "-q", spec());
        // `print` of a missing revision exits 0 and complains on stderr
        if (!r.ok() || (r.out().length == 0 && !r.err().isBlank())) {
            throw new VcsException("p4 print " + spec() + " failed: " + r.err().strip());
        }
        return r.out();
    }

    @Override
    public String getContent() throws VcsException {
        byte[] bytes = getContentAsBytes();
        Charset bom = bomCharset(bytes);
        if (bom != null) {
            int skip = bomLength(bytes);
            return new String(bytes, skip, bytes.length - skip, bom);
        }
        return new String(bytes, file.getCharset());
    }

    /** Length of the byte-order mark the content starts with (UTF-8, UTF-16 or UTF-32), else 0. */
    static int bomLength(byte[] b) {
        Charset c = bomCharset(b);
        if (c == null) return 0;
        return c == StandardCharsets.UTF_8 ? 3 : c.name().startsWith("UTF-32") ? 4 : 2;
    }

    /** The charset a byte-order mark announces, or null (UTF-32LE is checked before UTF-16LE: same first bytes). */
    static Charset bomCharset(byte[] b) {
        int b0 = b.length > 0 ? b[0] & 0xFF : -1, b1 = b.length > 1 ? b[1] & 0xFF : -1;
        int b2 = b.length > 2 ? b[2] & 0xFF : -1, b3 = b.length > 3 ? b[3] & 0xFF : -1;
        if (b0 == 0 && b1 == 0 && b2 == 0xFE && b3 == 0xFF) return Charset.forName("UTF-32BE");
        if (b0 == 0xFF && b1 == 0xFE && b2 == 0 && b3 == 0) return Charset.forName("UTF-32LE");
        if (b0 == 0xEF && b1 == 0xBB && b2 == 0xBF) return StandardCharsets.UTF_8;
        if (b0 == 0xFE && b1 == 0xFF) return StandardCharsets.UTF_16BE;
        if (b0 == 0xFF && b1 == 0xFE) return StandardCharsets.UTF_16LE;
        return null;
    }

    @Override
    public @NotNull VcsRevisionNumber getRevisionNumber() {
        return revision;
    }

    @Override
    public @NotNull FilePath getFile() {
        return file;
    }

    @Override
    public String toString() {
        return file.getPath() + " (" + spec() + ")";
    }

    /** Binary filetypes: the IDE shows them as binary instead of decoding them as text. */
    static final class Binary extends P4ContentRevision implements BinaryContentRevision {
        Binary(FilePath file, String depotFile, P4RevisionNumber revision, Supplier<P4Cli> cli) {
            super(file, depotFile, revision, cli);
        }

        @Override
        public byte[] getBinaryContent() throws VcsException {
            return getContentAsBytes();
        }
    }
}
