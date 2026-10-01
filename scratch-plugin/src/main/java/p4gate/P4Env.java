package p4gate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/** Collects every place a p4 connection's settings can come from: this process's environment, `p4 set` (the OS-level defaults, e.g. Windows registry), and P4CONFIG files found above the workspace directory. */
public final class P4Env {
    public static final List<String> KEYS = List.of("P4PORT", "P4USER", "P4CLIENT", "P4HOST", "P4CONFIG", "P4IGNORE", "P4CHARSET");

    private P4Env() { }

    /** P4* variables present in this process's environment. */
    public static SortedMap<String, String> processEnv() {
        SortedMap<String, String> out = new TreeMap<>();
        for (String key : KEYS) {
            String v = System.getenv(key);
            if (v != null && !v.isBlank()) out.put(key, v);
        }
        return out;
    }

    /** Raw `p4 set` output, run from the cli's workdir: p4's own view of every setting and where it came from. */
    public static String p4Set(P4Cli cli) {
        return cli.run("set").text();
    }

    /** `p4 set` lines for {@link #KEYS}, keeping p4's source annotation, e.g. {@code P4PORT=host:1666 (config '/ws/.p4config')}.
     *  This is the authoritative answer to "which config file did p4 actually read". */
    public static List<String> annotatedSet(String p4SetOutput) {
        List<String> out = new java.util.ArrayList<>();
        for (String line : p4SetOutput.split("\n")) {
            String t = line.strip();
            int eq = t.indexOf('=');
            if (eq > 0 && KEYS.contains(t.substring(0, eq).strip())) out.add(t);
        }
        return out;
    }

    /** Parse lines like `P4PORT=127.0.0.1:1666 (set)` / `P4CONFIG=p4config.txt (set) (config 'noconfig')` — p4 appends one or more annotation groups after the value. */
    public static SortedMap<String, String> parseSet(String p4SetOutput) {
        SortedMap<String, String> out = new TreeMap<>();
        for (String line : p4SetOutput.split("\n")) {
            String t = line.trim(); // also removes trailing \r from CRLF output
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String key = t.substring(0, eq).trim();
            if (!KEYS.contains(key)) continue;
            String val = t.substring(eq + 1).trim();
            // strip trailing p4 annotations (" (set)", " (config 'noconfig')", " (env)") until none remain
            for (int i = 0; i < 3; i++) {
                if (val.endsWith(")")) {
                    int paren = val.lastIndexOf(" (");
                    if (paren > 0) {
                        val = val.substring(0, paren).trim();
                        continue;
                    }
                }
                break;
            }
            if (val.length() >= 2 && val.charAt(0) == '"' && val.charAt(val.length() - 1) == '"') {
                val = val.substring(1, val.length() - 1);
            }
            if (!val.isBlank()) out.put(key, val);
        }
        return out;
    }

    /** Config file name(s) to look for: the P4CONFIG value from env or `p4 set`, then the common defaults. */
    public static List<String> configNames(SortedMap<String, String> env, SortedMap<String, String> set) {
        List<String> names = new java.util.ArrayList<>();
        String n = env.get("P4CONFIG");
        if (n == null) n = set.get("P4CONFIG");
        if (n != null && !n.isBlank()) names.add(n);
        names.add(".p4config");
        names.add("p4config.txt");
        return names.stream().distinct().toList();
    }

    /** Walk up from start to the filesystem root looking for a P4CONFIG file. */
    public static Optional<Path> findConfig(Path start, List<String> names) {
        Path dir = start.toAbsolutePath().normalize();
        while (dir != null) {
            for (String name : names) {
                Path f = dir.resolve(name);
                if (Files.isRegularFile(f)) return Optional.of(f);
            }
            dir = dir.getParent();
        }
        return Optional.empty();
    }

    /** name=value pairs from a P4CONFIG file; '#' starts a comment. */
    public static SortedMap<String, String> parseConfig(Path file) throws IOException {
        SortedMap<String, String> out = new TreeMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String key = t.substring(0, eq).trim();
            if (KEYS.contains(key)) out.put(key, t.substring(eq + 1).trim());
        }
        return out;
    }
}
