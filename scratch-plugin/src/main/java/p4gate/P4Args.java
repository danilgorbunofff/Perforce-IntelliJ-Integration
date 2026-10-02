package p4gate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * p4 arguments: argument files for {@code p4 -x <file>} (every line becomes one argument — the only reliable way
 * to hand p4 many paths, or paths with spaces, without hitting the Windows command line limit), and the escaping
 * p4 needs for file names that contain its revision/wildcard characters.
 */
final class P4Args {

    private P4Args() {
    }

    /**
     * One argument per line, in a temp file in the SYSTEM temp dir. Never in the workspace: a file appearing there
     * is a VFS event, which re-runs the change provider, which would write the next argument file — a refresh loop.
     */
    static String file(String prefix, List<String> args) {
        List<String> lines = new ArrayList<>(args.size());
        for (String arg : args) {
            lines.add(arg == null || arg.isEmpty() ? "\"\"" : arg);
        }
        try {
            Path file = Files.createTempFile(prefix, ".p4args");
            // always LF: p4 reads the file itself and a stray CR would end up inside the argument
            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            return file.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write a p4 argument file", e);
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

    /**
     * A local file name as p4 must be given it: {@code @ # % *} are revision/wildcard syntax, so a file called
     * {@code icon@2x.png} has to be passed as {@code icon%402x.png} (verified on r25.2: unescaped, p4 answers
     * "Invalid changelist/client/label/date '@2x.png'"). Depot paths printed by p4 are already in this form.
     * Not for {@code p4 add -f} / {@code reconcile -f}, which take names literally.
     */
    static String escape(String path) {
        StringBuilder b = new StringBuilder(path.length() + 8);
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            switch (c) {
                case '%' -> b.append("%25");
                case '@' -> b.append("%40");
                case '#' -> b.append("%23");
                case '*' -> b.append("%2A");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    static List<String> escapeAll(Collection<String> paths) {
        List<String> out = new ArrayList<>(paths.size());
        for (String path : paths) out.add(escape(path));
        return out;
    }
}
