package p4gate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A throwaway Helix Core server for live tests: p4d in rsh mode (spawned by p4 per command, no port opened),
 * a fresh root and a workspace in a temp dir, seeded with files whose names exercise the edge cases (a space,
 * an {@code @}, a binary). Enabled when {@code P4_BIN} names a directory that holds p4 and p4d.
 */
final class P4Lab implements AutoCloseable {
    static final String USER = "alice";
    static final String CLIENT = "alice_ws";

    final Path base;
    final Path root;
    final Path ws;
    private final Path bin;

    private P4Lab(Path bin) throws IOException {
        this.bin = bin;
        base = Files.createTempDirectory("p4ii-lab");
        root = Files.createDirectories(base.resolve("root"));
        ws = Files.createDirectories(base.resolve("ws alice"));
    }

    /** null when live tests are disabled (no P4_BIN). */
    static Path binDir() {
        String dir = System.getenv("P4_BIN");
        if (dir == null || dir.isBlank()) return null;
        Path p = Path.of(dir);
        return Files.isRegularFile(p.resolve(exe("p4"))) && Files.isRegularFile(p.resolve(exe("p4d"))) ? p : null;
    }

    private static String exe(String name) {
        return System.getProperty("os.name").toLowerCase().contains("win") ? name + ".exe" : name;
    }

    /** A started, seeded lab: client alice_ws, submitted change 1 with a.txt, sub dir/c 2.txt, icon@2x.png, blob.bin. */
    static P4Lab start() throws IOException {
        Path bin = binDir();
        if (bin == null) throw new IllegalStateException("P4_BIN not set");
        P4Lab lab = new P4Lab(bin);
        lab.createClient(CLIENT, lab.ws);
        lab.write("a.txt", "a1\n");
        lab.write("sub dir/c 2.txt", "c\n");
        lab.write("icon@2x.png", "img\n");
        Files.write(lab.ws.resolve("blob.bin"), new byte[]{0, 1, 2, (byte) 0xFF, 'b'});
        ok(lab.cli().run("add", "a.txt", "sub dir/c 2.txt", "blob.bin"));
        ok(lab.cli().run("add", "-f", "icon@2x.png"));
        ok(lab.cli().run("submit", "-d", "seed"));
        return lab;
    }

    String p4() {
        return bin.resolve(exe("p4")).toString();
    }

    /** What the plugin's env override would be: the connection, and a P4CONFIG name nobody creates. */
    Map<String, String> env(String user, String client) {
        Map<String, String> env = new HashMap<>();
        env.put("P4PORT", "rsh:" + bin.resolve(exe("p4d")) + " -r \"" + root + "\" -L log -i -J off");
        env.put("P4USER", user);
        env.put("P4CLIENT", client);
        env.put("P4CONFIG", ".p4ii-lab-noconfig"); // the developer's own P4CONFIG/registry must not leak in
        return env;
    }

    P4Cli cli() {
        return new P4Cli(p4(), ws.toString(), env(USER, CLIENT));
    }

    P4Cli cli(String user, String client, Path dir) {
        return new P4Cli(p4(), dir.toString(), env(user, client));
    }

    void createClient(String name, Path clientRoot) throws IOException {
        createClient(USER, name, clientRoot);
    }

    void createClient(String user, String name, Path clientRoot) throws IOException {
        Files.createDirectories(clientRoot);
        P4Cli c = cli(user, name, clientRoot);
        P4Cli.Result spec = ok(c.run("client", "-o", name));
        StringBuilder b = new StringBuilder();
        for (String line : spec.out().split("\n")) {
            b.append(line.startsWith("Root:") ? "Root:\t" + clientRoot : line.startsWith("Host:") ? "Host:" : line).append('\n');
        }
        ok(c.runWithInput(b.toString().getBytes(StandardCharsets.UTF_8), "client", "-i"));
    }

    Path write(String rel, String content) throws IOException {
        Path f = ws.resolve(rel);
        Files.createDirectories(f.getParent());
        if (Files.exists(f)) f.toFile().setWritable(true);
        Files.writeString(f, content);
        return f;
    }

    String read(String rel) throws IOException {
        return Files.readString(ws.resolve(rel)).replace("\r\n", "\n");
    }

    String local(String rel) {
        return ws.resolve(rel).toString();
    }

    /** A numbered pending change with the given description; returns its number. */
    long newChange(String description) {
        P4Cli.Result r = ok(cli().runWithInput(P4Ops.changeSpec(description).getBytes(StandardCharsets.UTF_8), "change", "-i"));
        return P4Ops.createdChange(r.out());
    }

    static P4Cli.Result ok(P4Cli.Result r) {
        if (!r.ok()) throw new AssertionError("p4 failed (" + r.code() + "): " + r.text());
        return r;
    }

    @Override
    public void close() {
        try (Stream<Path> files = Files.walk(base)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                p.toFile().setWritable(true);
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // a temp dir: leftovers are harmless
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // best effort
        }
    }
}
