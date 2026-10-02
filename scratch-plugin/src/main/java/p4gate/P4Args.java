package p4gate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes p4 argument files: {@code p4 -x <file>} appends every line of the file as one argument, which is the
 * only reliable way to hand p4 many paths, or paths with spaces, without hitting the Windows command line limit.
 */
final class P4Args {

    private P4Args() {
    }

    /** One argument per line, in a temp file inside {@code dir} (or the system temp dir when that is unusable). */
    static String file(String dir, String prefix, List<String> args) {
        List<String> lines = new ArrayList<>(args.size());
        for (String arg : args) {
            lines.add(arg == null || arg.isEmpty() ? "\"\"" : arg);
        }
        try {
            return write(directory(dir), prefix, lines);
        } catch (IOException e) {
            try {
                return write(null, prefix, lines);
            } catch (IOException fallback) {
                throw new UncheckedIOException("cannot write a p4 argument file", fallback);
            }
        }
    }

    static void delete(String path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (IOException | InvalidPathException ignored) {
            // temp file: the OS cleans it up eventually
        }
    }

    private static Path directory(String dir) {
        if (dir == null || dir.isBlank()) return null;
        try {
            Path path = Path.of(dir);
            return Files.isDirectory(path) ? path : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static String write(Path dir, String prefix, List<String> lines) throws IOException {
        Path file = dir == null ? Files.createTempFile(prefix, ".p4args") : Files.createTempFile(dir, prefix, ".p4args");
        // one argument per line, always LF: p4 reads the file itself and a stray CR would end up inside the argument
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file.toAbsolutePath().toString();
    }
}
