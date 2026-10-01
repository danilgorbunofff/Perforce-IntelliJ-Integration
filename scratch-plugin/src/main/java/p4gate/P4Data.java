package p4gate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses plain-text p4 CLI output into small models.
 *  Speed invariant (the "does not freeze" claim): each listing is at most TWO process spawns,
 *  never one per changelist. Verified: 151 changelists went from 21.1s (152 spawns) to ~0.3s (2 spawns). */
public final class P4Data {
    private static final Pattern CHANGE_LINE =
            Pattern.compile("^Change (\\d+) on \\S+ by (\\S+)@\\S+.*?'(.*)'$");
    private static final Pattern SUBMITTED_LINE =
            Pattern.compile("^Change (\\d+) on (\\S+) by (\\S+)@(\\S+) '(.*)'$");
    private static final Pattern OPENED_LINE =
            Pattern.compile("^(.+)#\\d+ - ([\\w/]+) (?:change (\\d+)|default change)( .*)?$");
    private static final Pattern STREAMS_LINE =
            Pattern.compile("^Stream (\\S+) (\\S+) (\\S+) '(.*)'$");
    private static final Pattern CLIENT_STREAM = Pattern.compile("^Stream:\\s*(\\S+)");
    private static final Pattern IGNORED_LINE = Pattern.compile("^(\\S.*) ignored$");

    public record Change(long id, String user, String desc, List<String> files) {
        @Override
        public String toString() {
            return "change " + id + " — '" + desc + "' (" + files.size() + " files) by " + user;
        }
    }

    public record SubmittedChange(long id, String date, String user, String client, String desc) {
        @Override
        public String toString() {
            return "change " + id + " on " + date + " by " + user + "@" + client + " '" + desc + "'";
        }
    }

    public record StreamSpec(String name, String type, String parent, String desc) {
        @Override
        public String toString() {
            return name + " (" + type + ", parent " + parent + ")";
        }
    }

    private P4Data() { }

    /** Pending changelists with the files opened in each (default first).
     *  Batched: one `p4 changes -s pending` + one `p4 opened`, files grouped locally by CL number. */
    public static List<Change> pendingChanges() {
        Map<Long, String[]> headers = new LinkedHashMap<>(); // id -> [user, desc]
        P4Cli.Result r = P4Cli.run("changes", "-s", "pending");
        if (r.ok()) {
            for (String line : r.out().split("\n")) {
                Matcher m = CHANGE_LINE.matcher(line.trim());
                if (m.matches()) {
                    headers.put(Long.parseLong(m.group(1)), new String[]{m.group(2), m.group(3)});
                }
            }
        }
        // `p4 opened` (no -c) lists every opened file once, each tagged with its changelist
        Map<Long, List<String>> filesByCl = new TreeMap<>();
        P4Cli.Result o = P4Cli.run("opened");
        if (o.ok()) {
            for (String line : o.out().split("\n")) {
                Matcher m = OPENED_LINE.matcher(line.trim());
                if (m.matches()) {
                    long cl = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
                    filesByCl.computeIfAbsent(cl, k -> new ArrayList<>())
                             .add(m.group(1) + " — " + m.group(2) + " @" + cl);
                }
            }
        }
        List<Change> result = new ArrayList<>();
        result.add(new Change(0, "", "default", filesByCl.getOrDefault(0L, List.of())));
        for (Map.Entry<Long, String[]> e : headers.entrySet()) {
            result.add(new Change(e.getKey(), e.getValue()[0], e.getValue()[1], filesByCl.getOrDefault(e.getKey(), List.of())));
        }
        return result;
    }

    /** Recently submitted changelists — the "real changelist index" of complaint row 4.
     *  Note: p4d may RENUMBER a pending changelist when it is submitted while higher numbers exist
     *  (verified: pending CL 2 became submitted 154); the index always reflects submitted numbers. */
    public static List<SubmittedChange> submittedIndex(int max) {
        List<SubmittedChange> out = new ArrayList<>();
        P4Cli.Result r = P4Cli.run("changes", "-s", "submitted", "-m", String.valueOf(max));
        if (!r.ok()) {
            return out;
        }
        for (String line : r.out().split("\n")) {
            Matcher m = SUBMITTED_LINE.matcher(line.trim());
            if (m.matches()) {
                out.add(new SubmittedChange(Long.parseLong(m.group(1)), m.group(2), m.group(3), m.group(4), m.group(5)));
            }
        }
        out.sort((a, b) -> Long.compare(b.id(), a.id())); // newest first
        return out;
    }

    /** The stream the current client is dedicated to, or null (classic client). */
    public static String clientStream() {
        P4Cli.Result r = P4Cli.run("client", "-o");
        if (!r.ok()) {
            return null;
        }
        for (String line : r.out().split("\n")) {
            Matcher m = CLIENT_STREAM.matcher(line.trim());
            if (m.matches()) {
                return m.group(1);
            }
        }
        return null;
    }

    /** All streams the server knows, with parent links (stream tree). */
    public static List<StreamSpec> streams() {
        List<StreamSpec> out = new ArrayList<>();
        P4Cli.Result r = P4Cli.run("streams");
        if (!r.ok()) {
            return out;
        }
        for (String line : r.out().split("\n")) {
            Matcher m = STREAMS_LINE.matcher(line.trim());
            if (m.matches()) {
                out.add(new StreamSpec(m.group(1), m.group(2), m.group(3), m.group(4)));
            }
        }
        return out;
    }

    /** Local path p4 maps a depot file to in this client (for ignore-file work); null when not in client view. */
    public static String where(String depotFile) {
        P4Cli.Result r = P4Cli.run("where", depotFile);
        if (!r.ok()) {
            return null;
        }
        String out = r.out().trim();
        if (out.contains("not in client view")) {
            return null;
        }
        String[] parts = out.split(" ");
        // `p4 where` line: <depot> <ws> <local>; the ws field can be "-" when mapping is viewless
        return parts.length >= 3 ? parts[2] : null;
    }

    /** Which of the given LOCAL file paths p4 considers ignored (per P4IGNORE files). */
    public static List<String> ignored(List<String> localFiles) {
        List<String> args = new ArrayList<>();
        args.add("ignores");
        args.add("-i");
        args.addAll(localFiles);
        P4Cli.Result r = P4Cli.run(args.toArray(String[]::new));
        List<String> out = new ArrayList<>();
        if (!r.ok()) {
            return out;
        }
        for (String line : r.out().split("\n")) {
            Matcher m = IGNORED_LINE.matcher(line.trim());
            if (m.matches()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    public static String info() {
        P4Cli.Result r = P4Cli.run("info");
        return r.ok() ? r.out() : r.text();
    }

    /** CLI test driver: p4gate.P4Data against a running p4d. */
    public static void main(String[] args) {
        List<Change> cs = pendingChanges();
        System.out.println("--- pending (batched: 2 spawns) ---");
        for (Change c : cs) {
            System.out.println(c);
            for (String f : c.files()) {
                System.out.println("    " + f);
            }
        }
        System.out.println("--- submitted index (newest first, top 5) ---");
        for (SubmittedChange sc : submittedIndex(5)) {
            System.out.println(sc);
        }
        System.out.println("--- client stream ---");
        String st = clientStream();
        System.out.println(st == null ? "(classic client, no stream)" : st);
        System.out.println("--- streams (tree data) ---");
        for (StreamSpec s : streams()) {
            System.out.println(s);
        }
        System.out.println("--- ignored? (local paths) ---");
        System.out.println("noise1.txt -> " + ignored(List.of("noise1.txt")));
        System.out.println("--- where (depot -> local) ---");
        System.out.println("//depot/f0002.txt -> " + where("//depot/f0002.txt"));
    }
}
