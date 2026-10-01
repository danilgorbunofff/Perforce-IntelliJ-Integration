package p4gate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs the p4 CLI executable and captures its output. Honours P4CONFIG / P4PORT / P4USER / P4CLIENT via the environment, like any p4 invocation. */
public final class P4Cli {
    /** Override with -Dp4.executable=/path/to/p4 (or p4.executable in the IDE VM options). */
    public static final String EXECUTABLE = System.getProperty("p4.executable", "p4");

    public record Result(int code, String out, String err) {
        public boolean ok() { return code == 0; }
        public String text() { return err.isBlank() ? out : out + "\n" + err; }
    }

    private P4Cli() { }

    public static Result run(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(EXECUTABLE);
        cmd.addAll(Arrays.asList(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-1, sb.toString(), "p4 timed out after 30s");
            }
            return new Result(p.exitValue(), sb.toString(), "");
        } catch (Exception e) {
            return new Result(-1, "", "failed to run '" + EXECUTABLE + "': " + e);
        }
    }
}
