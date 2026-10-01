package p4gate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
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
 *  (verified on r25.2), so without -d, P4CONFIG resolution silently happens wherever the IDE was launched from. */
public final class P4Cli {
    /** Default for read-only queries. Mutating/long commands (sync, submit, reconcile) pass {@code null} = no limit. */
    public static final Duration QUERY_TIMEOUT = Duration.ofSeconds(30);

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

    /** One p4 message from -Mj output: severity 1 info, 2 warning, 3 failed, 4 fatal. */
    public record Message(int severity, String text) { }

    /** Parsed `p4 -ztag -Mj` output: data records, p4 messages, and the raw result. */
    public record Tagged(Result raw, List<Map<String, String>> records, List<Message> messages) {
        /** The first error p4 reported (error-level message, or a non-zero exit), else null. Warnings are not errors. */
        public String error() {
            for (Message m : messages) {
                if (m.severity() >= 3) return m.text().strip();
            }
            if (!raw.ok()) {
                String t = raw.text().strip();
                return t.isEmpty() ? "p4 exited with code " + raw.code() : t;
            }
            return null;
        }
    }

    /** The exact argv that {@link #run} executes — shown verbatim in the diagnosis. */
    public List<String> commandLine(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        cmd.add("-d");
        cmd.add(workdir);
        cmd.addAll(Arrays.asList(args));
        return cmd;
    }

    public Result run(String... args) {
        return run(QUERY_TIMEOUT, () -> false, args);
    }

    public Tagged tagged(String... args) {
        return tagged(QUERY_TIMEOUT, () -> false, args);
    }

    public Tagged tagged(Duration timeout, BooleanSupplier cancelled, String... args) {
        String[] full = new String[args.length + 2];
        full[0] = "-ztag";
        full[1] = "-Mj";
        System.arraycopy(args, 0, full, 2, args.length);
        return parseTagged(run(timeout, cancelled, full));
    }

    static Tagged parseTagged(Result r) {
        List<Map<String, String>> records = new ArrayList<>();
        List<Message> messages = new ArrayList<>();
        for (String line : r.out().split("\n")) {
            if (line.isBlank()) continue;
            Map<String, String> obj;
            try {
                obj = P4Json.parseObject(line);
            } catch (IllegalArgumentException e) {
                obj = null;
            }
            if (obj == null) {
                messages.add(new Message(r.ok() ? 1 : 3, line.strip())); // plain text: not tagged data
            } else if (obj.containsKey("data") && obj.containsKey("severity")) {
                messages.add(new Message(parseIntOr(obj.get("severity"), 3), obj.get("data")));
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

    /** @param timeout null = no limit (long operations); the process is killed when it elapses or when cancelled. */
    public Result run(Duration timeout, BooleanSupplier cancelled, String... args) {
        List<String> cmd = commandLine(args);
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workdir));
            pb.environment().putAll(envOverride);
            p = pb.start();
        } catch (Exception e) {
            return new Result(-1, "", "failed to run '" + executable + "': " + e);
        }
        try {
            p.getOutputStream().close(); // p4 must never wait on a prompt: it reads EOF and fails with a message instead
        } catch (Exception ignored) {
            // the process already exited
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
                    return new Result(-1, utf8(out), "cancelled");
                }
                if (System.nanoTime() > deadline) {
                    kill(p, outPump, errPump);
                    return new Result(-1, utf8(out), "p4 timed out after " + timeout.toSeconds() + "s and was killed");
                }
            }
            outPump.join(2000);
            errPump.join(2000);
        } catch (InterruptedException e) {
            kill(p, outPump, errPump);
            Thread.currentThread().interrupt();
            return new Result(-1, utf8(out), "interrupted");
        }
        return new Result(p.exitValue(), utf8(out), utf8(err));
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
    private static String utf8(ByteArrayOutputStream b) {
        return b.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
