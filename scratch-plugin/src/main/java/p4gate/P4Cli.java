package p4gate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Runs the p4 CLI for ONE working directory (one per project — never a JVM-wide setting).
 *  Every call passes `-d <workdir>`: p4 takes its current directory from $PWD rather than the real process cwd
 *  (verified on r25.2), so without -d, P4CONFIG resolution silently happens wherever the IDE was launched from.
 *  Global options (-d, -x, -ztag, -Mj) always go BEFORE the command name: after it, p4 reads them as command
 *  flags (`opened -x` means "exclusive locks", `fstat -x` is an invalid option — both verified on r25.2). */
public final class P4Cli {
    /** Default for read-only queries. Mutating/long commands (sync, submit, reconcile) pass {@code null} = no limit. */
    public static final Duration QUERY_TIMEOUT = Duration.ofSeconds(30);
    private static final BooleanSupplier NEVER = () -> false;

    private final String executable;
    private final String workdir;
    private final Map<String, String> envOverride;

    public P4Cli(String executable, String workdir, Map<String, String> envOverride) {
        this.executable = executable;
        this.workdir = workdir;
        this.envOverride = Map.copyOf(envOverride);
    }

    public String executable() { return executable; }

    public String workdir() { return workdir; }

    public Map<String, String> envOverride() { return envOverride; }

    public record Result(int code, String out, String err) {
        public boolean ok() { return code == 0; }
        public String text() { return err.isBlank() ? out : out.isBlank() ? err : out + "\n" + err; }
    }

    /** Undecoded stdout, for file content (`p4 print`): no charset guess, no line-ending rewrite. */
    public record Raw(int code, byte[] out, String err) {
        public boolean ok() { return code == 0; }
    }

    /** One p4 message from -Mj output: severity 1 info, 2 warning, 3 failed, 4 fatal. */
    public record Message(int severity, String text) { }

    /** Parsed `p4 -ztag -Mj` output: data records, p4 messages, and the raw result. */
    public record Tagged(Result raw, List<Map<String, String>> records, List<Message> messages) {
        /** The first error p4 reported (error-level message, or a non-zero exit), else null. Warnings are not errors. */
        public String error() {
            for (Message m : messages) {
                if (m.severity() >= 3) return m.text().strip();
            }
            // p4 exits 1 when its only messages are warnings (e.g. "ignored file can't be added", r25.2):
            // those are reported through warnings(), and outcome checks decide whether a skipped file matters
            if (!raw.ok() && messages.isEmpty()) {
                String t = raw.text().strip();
                return t.isEmpty() ? "p4 exited with code " + raw.code() : t;
            }
            return null;
        }

        /** Warnings (severity 2), e.g. "file(s) not opened on this client". */
        public List<String> warnings() {
            List<String> out = new ArrayList<>();
            for (Message m : messages) {
                if (m.severity() == 2) out.add(m.text().strip());
            }
            return out;
        }
    }

    /** The exact argv that {@link #run} executes — shown verbatim in the diagnosis. */
    public List<String> commandLine(String... args) {
        return commandLine(List.of(), args);
    }

    /** argv with global options: {@code p4 -d <workdir> <global...> <args...>}. */
    List<String> commandLine(List<String> global, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        cmd.add("-d");
        cmd.add(workdir);
        cmd.addAll(global);
        cmd.addAll(Arrays.asList(args));
        return cmd;
    }

    /** Global options for tagged JSON output, optionally reading extra arguments from an argument file. */
    static List<String> taggedGlobals(String argsFile) {
        return taggedGlobals(argsFile, 0);
    }

    /** ... and with {@code batch > 0}, {@code -b <batch>}: p4 runs the command once per that many arguments. */
    static List<String> taggedGlobals(String argsFile, int batch) {
        List<String> g = new ArrayList<>();
        if (argsFile != null) {
            if (batch > 0) {
                g.add("-b");
                g.add(Integer.toString(batch));
            }
            g.add("-x");
            g.add(argsFile);
        }
        g.add("-ztag");
        g.add("-Mj");
        return g;
    }

    public Result run(String... args) {
        return run(QUERY_TIMEOUT, NEVER, args);
    }

    public Result run(Duration timeout, BooleanSupplier cancelled, String... args) {
        return decode(exec(List.of(), null, timeout, cancelled, args));
    }

    /** Runs with {@code stdin} as the process input, e.g. a spec for `p4 change -i`. */
    public Result runWithInput(byte[] stdin, String... args) {
        return decode(exec(List.of(), stdin, QUERY_TIMEOUT, NEVER, args));
    }

    public Raw runRaw(Duration timeout, BooleanSupplier cancelled, String... args) {
        return exec(List.of(), null, timeout, cancelled, args);
    }

    public Tagged tagged(String... args) {
        return tagged(QUERY_TIMEOUT, NEVER, args);
    }

    public Tagged tagged(Duration timeout, BooleanSupplier cancelled, String... args) {
        return parseTagged(decode(exec(taggedGlobals(null), null, timeout, cancelled, args)));
    }

    /**
     * Tagged call that appends {@code fileArgs} to the command via an argument file ({@code p4 -x <file> ...}):
     * no Windows command-line limit, spaces kept, and p4 batches long lists itself. An empty list runs nothing.
     */
    public Tagged taggedWithArgs(Duration timeout, BooleanSupplier cancelled, List<String> fileArgs, String... command) {
        return taggedWithArgs(timeout, cancelled, fileArgs, 0, command);
    }

    /**
     * As above, with {@code -b <batch>}: e.g. {@code p4 -b 2 -x <pairs> move} runs one move per (from, to) pair,
     * all inside ONE p4 process — commands that take exactly N arguments still cost a single spawn.
     */
    public Tagged taggedWithArgs(Duration timeout, BooleanSupplier cancelled, List<String> fileArgs, int batch, String... command) {
        if (fileArgs.isEmpty()) return new Tagged(new Result(0, "", ""), List.of(), List.of());
        String file = P4Args.file("p4ii", fileArgs);
        try {
            return parseTagged(decode(exec(taggedGlobals(file, batch), null, timeout, cancelled, command)));
        } finally {
            P4Args.delete(file);
        }
    }

    static Tagged parseTagged(Result r) {
        List<Map<String, String>> records = new ArrayList<>();
        List<Message> messages = new ArrayList<>();
        for (String line : r.out().split("\n")) {
            if (line.isBlank()) continue;
            Map<String, String> obj;
            try {
                obj = P4Json.parseObject(line);
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                obj = null;
            }
            if (obj == null) {
                messages.add(new Message(r.ok() ? 1 : 3, line.strip())); // plain text: not tagged data
            } else if (obj.containsKey("data") && obj.containsKey("severity")) {
                messages.add(new Message(parseIntOr(obj.get("severity"), 3), obj.get("data")));
            } else if (obj.containsKey("data") && obj.containsKey("level")) {
                // the other -Mj message shape (r25.2): level = severity << 4 | generic, e.g. 34 = warning
                messages.add(new Message(parseIntOr(obj.get("level"), 48) >> 4, obj.get("data")));
            } else {
                records.add(obj);
            }
        }
        if (!r.err().isBlank()) {
            messages.add(new Message(r.ok() ? 2 : 3, r.err().strip()));
        }
        return new Tagged(r, records, messages);
    }

    private static int parseIntOr(String s, int dflt) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static Result decode(Raw raw) {
        return new Result(raw.code(), utf8(raw.out()), raw.err());
    }

    /** @param timeout null = no limit (long operations); the process is killed when it elapses or when cancelled. */
    private Raw exec(List<String> global, byte[] stdin, Duration timeout, BooleanSupplier cancelled, String... args) {
        if (workdir == null || !new File(workdir).isDirectory()) {
            return new Raw(-1, new byte[0], "workspace dir does not exist: " + workdir);
        }
        List<String> cmd = commandLine(global, args);
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workdir));
            pb.environment().putAll(envOverride);
            p = pb.start();
        } catch (Exception e) {
            return new Raw(-1, new byte[0], "failed to run '" + executable + "': " + e);
        }
        // p4 must never wait on a prompt: stdin is closed (after the input, if any), so it reads EOF and fails instead
        try (OutputStream in = p.getOutputStream()) {
            if (stdin != null) in.write(stdin);
        } catch (Exception ignored) {
            // the process already exited; its exit code and stderr say why
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outPump = pump(p.getInputStream(), out);
        Thread errPump = pump(p.getErrorStream(), err);
        long deadline = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        try {
            while (!p.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    kill(p, outPump, errPump);
                    return new Raw(-1, out.toByteArray(), "cancelled");
                }
                if (System.nanoTime() > deadline) {
                    kill(p, outPump, errPump);
                    return new Raw(-1, out.toByteArray(), "p4 timed out after " + timeout.toSeconds() + "s and was killed");
                }
            }
            outPump.join(10_000);
            errPump.join(10_000);
        } catch (InterruptedException e) {
            kill(p, outPump, errPump);
            Thread.currentThread().interrupt();
            return new Raw(-1, out.toByteArray(), "interrupted");
        } catch (RuntimeException e) {
            // the cancellation check may throw (the platform's ProcessCanceledException): never leave p4 running
            kill(p, outPump, errPump);
            throw e;
        }
        return new Raw(p.exitValue(), out.toByteArray(), utf8(err.toByteArray()));
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream sink) {
        Thread t = new Thread(() -> {
            try (in) {
                in.transferTo(sink);
            } catch (Exception ignored) {
                // stream closed by kill()
            }
        }, "p4-output-pump");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void kill(Process p, Thread... pumps) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        for (Thread t : pumps) {
            try {
                t.join(1000); // a grandchild may still hold the pipe; do not wait for it
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** p4 output is decoded as UTF-8; servers in a non-unicode mode with non-ASCII paths may show replacement chars. */
    private static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
