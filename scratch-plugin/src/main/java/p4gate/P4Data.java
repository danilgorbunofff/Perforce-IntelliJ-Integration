package p4gate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads p4 state as tagged JSON (`p4 -ztag -Mj`), never by regex over human-readable output.
 *  Speed invariant (the "does not freeze" claim): every listing costs a constant number of p4 spawns,
 *  independent of how many changelists or files exist — never one spawn per changelist.
 *  Correctness invariant: every listing is scoped to the current client, and every failure is returned, not swallowed. */
public final class P4Data {
    private static final Pattern IGNORED_LINE = Pattern.compile("^(\\S.*) ignored$");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault());

    /** A listing plus the reason it may be incomplete. error == null means p4 answered cleanly. */
    public record Listing<T>(List<T> items, String error) {
        static <T> Listing<T> failed(String error) { return new Listing<>(List.of(), error); }
    }

    public record OpenedFile(String depotFile, String clientFile, String action, long change, String type) {
        @Override
        public String toString() {
            return depotFile + " — " + action + (type.isEmpty() ? "" : " (" + type + ")");
        }
    }

    public record Change(long id, String user, String desc, List<OpenedFile> files) {
        @Override
        public String toString() {
            return id == 0
                    ? "default changelist (" + files.size() + " files)"
                    : "change " + id + " — '" + desc + "' (" + files.size() + " files) by " + user;
        }
    }

    public record SubmittedChange(long id, long time, String user, String client, String desc) {
        public String date() { return time == 0 ? "" : DATE.format(Instant.ofEpochSecond(time)); }
    }

    public record StreamSpec(String name, String type, String parent, String title) {
        @Override
        public String toString() {
            return name + " (" + type + ", parent " + parent + ")";
        }
    }

    /** `p4 info` as the plugin needs it. clientKnown is false when p4 prints "Client unknown." (exit code is still 0). */
    public record ClientInfo(String clientName, boolean clientKnown, String clientRoot, String clientHost,
                             String userName, String serverAddress, String serverVersion, String error) { }

    private P4Data() { }

    // ---------------------------------------------------------------- fetch (each is a constant number of spawns)

    public static ClientInfo info(P4Cli cli) {
        return parseInfo(cli.tagged("info"));
    }

    /** Pending changelists OF THIS CLIENT with their opened files: `changes -s pending -l -c <client>` + `opened`. */
    public static Listing<Change> pendingChanges(P4Cli cli, String client) {
        P4Cli.Tagged changes = cli.tagged("changes", "-s", "pending", "-l", "-c", client);
        if (changes.error() != null) return Listing.failed(changes.error());
        P4Cli.Tagged opened = cli.tagged("opened"); // `opened` without -a is already limited to the current client
        if (opened.error() != null) return Listing.failed(opened.error());
        return new Listing<>(groupPending(changes.records(), opened.records()), null);
    }

    /** Submitted changelists that touch this client's view, newest first. Re-fetched on every refresh (no cache). */
    public static Listing<SubmittedChange> submittedIndex(P4Cli cli, String client, int max) {
        P4Cli.Tagged t = cli.tagged("changes", "-s", "submitted", "-l", "-m", String.valueOf(max), "//" + client + "/...");
        if (t.error() != null) return Listing.failed(t.error());
        return new Listing<>(parseSubmitted(t.records()), null);
    }

    /** The stream the current client is dedicated to: a one-item listing, or empty for a classic client. */
    public static Listing<String> clientStream(P4Cli cli) {
        P4Cli.Tagged t = cli.tagged("client", "-o");
        if (t.error() != null) return Listing.failed(t.error());
        for (Map<String, String> r : t.records()) {
            String s = r.get("Stream");
            if (s != null && !s.isBlank()) return new Listing<>(List.of(s), null);
        }
        return new Listing<>(List.of(), null);
    }

    public static Listing<StreamSpec> streams(P4Cli cli) {
        P4Cli.Tagged t = cli.tagged("streams");
        if (t.error() != null) return Listing.failed(t.error());
        return new Listing<>(parseStreams(t.records()), null); // "No such stream." is a warning: empty, not an error
    }

    /** Local path of a depot file in this client, or null when unmapped. */
    public static String where(P4Cli cli, String depotFile) {
        P4Cli.Tagged t = cli.tagged("where", depotFile);
        return t.error() != null ? null : parseWhere(t.records());
    }

    /** Which of the given LOCAL paths p4 considers ignored. `p4 ignores` prints only the ignored ones (r25.2). */
    public static List<String> ignored(P4Cli cli, List<String> localFiles) {
        List<String> args = new ArrayList<>(List.of("ignores", "-i"));
        args.addAll(localFiles);
        P4Cli.Result r = cli.run(args.toArray(String[]::new));
        List<String> out = new ArrayList<>();
        if (!r.ok()) return out;
        for (String line : r.out().split("\n")) {
            Matcher m = IGNORED_LINE.matcher(line.strip());
            if (m.matches()) out.add(m.group(1));
        }
        return out;
    }

    /** Effective value of one p4 setting as p4 itself resolves it from the workdir (env, P4CONFIG file, registry). */
    public static String effectiveSetting(P4Cli cli, String name) {
        P4Cli.Result r = cli.run("set", "-q", name);
        if (!r.ok()) return null;
        return P4Env.parseSet(r.out()).get(name);
    }

    /** The client name p4 was asked to use: `p4 info` only says "*unknown*" for a missing client.
     *  With P4CLIENT unset anywhere, p4 defaults the client name to the host name. */
    public static String requestedClient(P4Cli cli, ClientInfo info) {
        String s = effectiveSetting(cli, "P4CLIENT");
        return s != null && !s.isBlank() ? s : info.clientHost() + " (P4CLIENT unset — p4 defaults to the host name)";
    }

    // ---------------------------------------------------------------- pure parsing (unit-tested)

    static ClientInfo parseInfo(P4Cli.Tagged t) {
        if (t.error() != null || t.records().isEmpty()) {
            return new ClientInfo("", false, "", "", "", "", "", t.error() != null ? t.error() : "p4 info returned no data");
        }
        Map<String, String> r = t.records().get(0);
        String client = r.getOrDefault("clientName", "");
        boolean known = !client.isEmpty() && !client.equals("*unknown*") && r.containsKey("clientRoot");
        return new ClientInfo(client, known, r.getOrDefault("clientRoot", ""), r.getOrDefault("clientHost", ""),
                r.getOrDefault("userName", ""), r.getOrDefault("serverAddress", ""), r.getOrDefault("serverVersion", ""), null);
    }

    /** Default changelist first, then numbered changelists in p4's order (newest first); files grouped by change. */
    static List<Change> groupPending(List<Map<String, String>> changeRecords, List<Map<String, String>> openedRecords) {
        Map<Long, List<OpenedFile>> filesByCl = new LinkedHashMap<>();
        for (Map<String, String> r : openedRecords) {
            String c = r.getOrDefault("change", "default");
            long cl = c.equals("default") ? 0 : Long.parseLong(c);
            filesByCl.computeIfAbsent(cl, k -> new ArrayList<>()).add(new OpenedFile(
                    r.getOrDefault("depotFile", "?"), r.getOrDefault("clientFile", ""),
                    r.getOrDefault("action", "?"), cl, r.getOrDefault("type", "")));
        }
        List<Change> result = new ArrayList<>();
        result.add(new Change(0, "", "default", filesByCl.getOrDefault(0L, List.of())));
        for (Map<String, String> r : changeRecords) {
            long id = Long.parseLong(r.get("change"));
            result.add(new Change(id, r.getOrDefault("user", ""), firstLine(r.getOrDefault("desc", "")),
                    filesByCl.getOrDefault(id, List.of())));
        }
        return result;
    }

    static List<SubmittedChange> parseSubmitted(List<Map<String, String>> records) {
        List<SubmittedChange> out = new ArrayList<>();
        for (Map<String, String> r : records) {
            out.add(new SubmittedChange(Long.parseLong(r.get("change")), parseLongOr(r.get("time"), 0),
                    r.getOrDefault("user", ""), r.getOrDefault("client", ""), firstLine(r.getOrDefault("desc", ""))));
        }
        out.sort((a, b) -> Long.compare(b.id(), a.id()));
        return out;
    }

    static List<StreamSpec> parseStreams(List<Map<String, String>> records) {
        List<StreamSpec> out = new ArrayList<>();
        for (Map<String, String> r : records) {
            if (!r.containsKey("Stream")) continue;
            out.add(new StreamSpec(r.get("Stream"), r.getOrDefault("Type", "?"), r.getOrDefault("Parent", "none"),
                    r.getOrDefault("Name", "")));
        }
        return out;
    }

    /** First mapping that is not an exclusion ("unmap") line. */
    static String parseWhere(List<Map<String, String>> records) {
        for (Map<String, String> r : records) {
            if (r.containsKey("unmap")) continue;
            String path = r.get("path");
            if (path != null && !path.isBlank()) return path;
        }
        return null;
    }

    static String firstLine(String desc) {
        String s = desc.strip();
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl).strip();
    }

    private static long parseLongOr(String s, long dflt) {
        try {
            return s == null ? dflt : Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** CLI driver: java -Dp4.executable=... p4gate.P4Data [workdir] — runs the same calls as the Changelists tab. */
    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : System.getProperty("user.dir");
        P4Cli cli = new P4Cli(System.getProperty("p4.executable", "p4"), dir, Map.of());
        ClientInfo info = info(cli);
        if (info.error() != null || !info.clientKnown()) {
            System.out.println("NOT READY: " + (info.error() != null ? info.error() : "client '" + requestedClient(cli, info) + "' unknown"));
            System.exit(2);
        }
        System.out.println("--- pending (client " + info.clientName() + ") ---");
        Listing<Change> pending = pendingChanges(cli, info.clientName());
        if (pending.error() != null) System.out.println("ERROR: " + pending.error());
        for (Change c : pending.items()) {
            System.out.println(c);
            for (OpenedFile f : c.files()) System.out.println("    " + f);
        }
        System.out.println("--- submitted (client view, top 5) ---");
        Listing<SubmittedChange> sub = submittedIndex(cli, info.clientName(), 5);
        if (sub.error() != null) System.out.println("ERROR: " + sub.error());
        for (SubmittedChange s : sub.items()) System.out.println(s.id() + " " + s.date() + " " + s.user() + "@" + s.client() + " '" + s.desc() + "'");
        System.out.println("--- client stream ---");
        Listing<String> st = clientStream(cli);
        System.out.println(st.error() != null ? "ERROR: " + st.error() : st.items().isEmpty() ? "(classic client)" : st.items().get(0));
    }
}
