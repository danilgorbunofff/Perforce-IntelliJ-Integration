package p4gate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;

/** Dependency-free tests for every parser and the diagnosis rules. Fixtures are verbatim p4 r25.2 output
 *  (P4/NTX64/2025.2/3051938). Run by build.ps1; exit code 1 on any failure. */
public final class P4ParseTest {
    private static int failures = 0;
    private static int checks = 0;

    public static void main(String[] args) {
        json();
        tagged();
        info();
        pending();
        submitted();
        where();
        streams();
        setOutput();
        hints();
        isUnder();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void eq(String what, Object expected, Object actual) {
        checks++;
        if (!Objects.equals(expected, actual)) {
            failures++;
            System.out.println("FAIL " + what + "\n  expected: " + expected + "\n  actual:   " + actual);
        }
    }

    static P4Cli.Tagged tagged(int code, String out, String err) {
        return P4Cli.parseTagged(new P4Cli.Result(code, out, err));
    }

    static void json() {
        Map<String, String> m = P4Json.parseObject(
                "{\"change\":\"5\",\"desc\":\"multi line\\n\\nsecond \\\"quoted\\\" para\\n\",\"severity\":3,\"p\":\"C:\\\\ws\\\\a b\",\"u\":\"\\u00e9\"}");
        eq("json string", "5", m.get("change"));
        eq("json escapes", "multi line\n\nsecond \"quoted\" para\n", m.get("desc"));
        eq("json number kept as text", "3", m.get("severity"));
        eq("json backslashes", "C:\\ws\\a b", m.get("p"));
        eq("json unicode", "é", m.get("u"));
        eq("json empty object", Map.of(), P4Json.parseObject("{}"));
        eq("plain text is not json", null, P4Json.parseObject("Perforce client error:"));
    }

    static void tagged() {
        P4Cli.Tagged warn = tagged(0, "{\"data\":\"//depot/nonexist.txt - file(s) not opened on this client.\\n\",\"generic\":17,\"severity\":2}\n", "");
        eq("warning is a message, not a record", 0, warn.records().size());
        eq("warning is not an error", null, warn.error());

        P4Cli.Tagged err = tagged(1, "{\"data\":\"Client 'nosuch' unknown - use 'client' command to create it.\\n\",\"generic\":2,\"severity\":3}\n", "");
        eq("severity 3 is an error", "Client 'nosuch' unknown - use 'client' command to create it.", err.error());

        P4Cli.Tagged down = tagged(1, "", "Perforce client error:\n\tConnect to server failed; check $P4PORT.\n\tTCP connect to 127.0.0.1:1999 failed.\n");
        eq("connect failure (plain stderr) is an error", true, down.error() != null && down.error().contains("Connect to server failed"));
    }

    static final String INFO_KNOWN = "{\"clientCwd\":\"/c/ws alice\",\"clientHost\":\"PC1486\",\"clientName\":\"alice_ws\",\"clientRoot\":\"C:/ws alice\","
            + "\"serverAddress\":\"kubernetes.docker.internal:1777\",\"serverVersion\":\"P4D/NTX64/2025.2/3051938 (2026/08/27)\",\"userName\":\"alice\"}";
    static final String INFO_UNKNOWN = "{\"clientCwd\":\"/c/ws alice\",\"clientHost\":\"PC1486\",\"clientName\":\"*unknown*\","
            + "\"serverAddress\":\"kubernetes.docker.internal:1777\",\"userName\":\"alice\"}";

    static void info() {
        P4Data.ClientInfo ok = P4Data.parseInfo(tagged(0, INFO_KNOWN + "\n", ""));
        eq("info client", "alice_ws", ok.clientName());
        eq("info client known", true, ok.clientKnown());
        eq("info root", "C:/ws alice", ok.clientRoot());
        eq("info no error", null, ok.error());

        // exit code 0, but nothing will work: the old verdict said "connected" here
        P4Data.ClientInfo unknown = P4Data.parseInfo(tagged(0, INFO_UNKNOWN + "\n", ""));
        eq("info unknown client detected", false, unknown.clientKnown());
        eq("info unknown client is not a connect error", null, unknown.error());

        P4Data.ClientInfo down = P4Data.parseInfo(tagged(1, "", "Perforce client error:\n\tConnect to server failed; check $P4PORT.\n"));
        eq("info connect failure", true, down.error() != null);
    }

    static void pending() {
        List<Map<String, String>> changes = tagged(0,
                "{\"change\":\"5\",\"changeType\":\"public\",\"client\":\"alice_ws\",\"desc\":\"multi line\\n\\nsecond \\\"quoted\\\" para\\n\",\"status\":\"pending\",\"time\":\"1790849023\",\"user\":\"alice\"}\n"
              + "{\"change\":\"3\",\"changeType\":\"public\",\"client\":\"alice_ws\",\"desc\":\"alice work\\n\",\"status\":\"pending\",\"time\":\"1790848344\",\"user\":\"alice\"}\n", "").records();
        List<Map<String, String>> opened = tagged(0,
                "{\"action\":\"edit\",\"change\":\"3\",\"client\":\"alice_ws\",\"clientFile\":\"//alice_ws/a.txt\",\"depotFile\":\"//depot/a.txt\",\"haveRev\":\"2\",\"rev\":\"2\",\"type\":\"text\",\"user\":\"alice\"}\n"
              + "{\"action\":\"edit\",\"change\":\"default\",\"client\":\"alice_ws\",\"clientFile\":\"//alice_ws/b.txt\",\"depotFile\":\"//depot/b.txt\",\"haveRev\":\"2\",\"rev\":\"2\",\"type\":\"text\",\"user\":\"alice\"}\n"
              + "{\"action\":\"move/add\",\"change\":\"3\",\"client\":\"alice_ws\",\"clientFile\":\"//alice_ws/sub dir/c 2.txt\",\"depotFile\":\"//depot/sub dir/c 2.txt\",\"rev\":\"1\",\"type\":\"text\",\"user\":\"alice\"}\n", "").records();
        List<P4Data.Change> cs = P4Data.groupPending(changes, opened);
        eq("default first", 0L, cs.get(0).id());
        eq("default files", List.of("//depot/b.txt"), cs.get(0).files().stream().map(P4Data.OpenedFile::depotFile).toList());
        eq("numbered order kept", List.of(0L, 5L, 3L), cs.stream().map(P4Data.Change::id).toList());
        eq("multi-line desc -> first line", "multi line", cs.get(1).desc());
        eq("files grouped by change", 2, cs.get(2).files().size());
        eq("paths with spaces intact", "//depot/sub dir/c 2.txt", cs.get(2).files().get(1).depotFile());
        eq("move/add action", "move/add", cs.get(2).files().get(1).action());
    }

    static void submitted() {
        List<P4Data.SubmittedChange> s = P4Data.parseSubmitted(tagged(0,
                "{\"change\":\"1\",\"changeType\":\"public\",\"client\":\"alice_ws\",\"desc\":\"seed\",\"path\":\"//depot/...\",\"status\":\"submitted\",\"time\":\"1790848332\",\"user\":\"alice\"}\n"
              + "{\"change\":\"4\",\"changeType\":\"public\",\"client\":\"bob_ws\",\"desc\":\"BOB private work\\n\",\"oldChange\":\"2\",\"path\":\"//depot/*\",\"status\":\"submitted\",\"time\":\"1790848660\",\"user\":\"bob\"}\n", "").records());
        eq("newest first, renumbered CL shows its submitted number", List.of(4L, 1L), s.stream().map(P4Data.SubmittedChange::id).toList());
        eq("submitted desc trimmed", "BOB private work", s.get(0).desc());
        eq("submitted time", 1790848660L, s.get(0).time());
    }

    static void where() {
        String withSpace = "{\"clientFile\":\"//alice_ws/sub dir/c.txt\",\"depotFile\":\"//depot/sub dir/c.txt\",\"path\":\"C:/ws alice\\\\sub dir\\\\c.txt\"}\n";
        // the old split(" ") parser returned "//alice_ws/sub" for this exact output
        eq("where path with spaces", "C:/ws alice\\sub dir\\c.txt", P4Data.parseWhere(tagged(0, withSpace, "").records()));
        String withUnmap = "{\"clientFile\":\"//alice_ws/x/a.txt\",\"depotFile\":\"//depot/x/a.txt\",\"path\":\"C:/ws/x/a.txt\",\"unmap\":\"\"}\n" + withSpace;
        eq("where skips exclusion lines", "C:/ws alice\\sub dir\\c.txt", P4Data.parseWhere(tagged(0, withUnmap, "").records()));
        eq("where nothing mapped", null, P4Data.parseWhere(List.of()));
    }

    static void streams() {
        List<P4Data.StreamSpec> s = P4Data.parseStreams(tagged(0,
                "{\"Stream\":\"//Ace/dev\",\"Type\":\"development\",\"Parent\":\"//Ace/main\",\"Name\":\"dev\",\"Owner\":\"alice\"}\n"
              + "{\"Stream\":\"//Ace/main\",\"Type\":\"mainline\",\"Parent\":\"none\",\"Name\":\"main\",\"Owner\":\"alice\"}\n", "").records());
        eq("streams parsed", 2, s.size());
        eq("stream parent", "//Ace/main", s.get(0).parent());
        eq("mainline parent none", "none", s.get(1).parent());
        P4Cli.Tagged none = tagged(0, "{\"data\":\"No such stream.\\n\",\"generic\":17,\"severity\":2}\n", "");
        eq("no streams is a warning, not an error", null, none.error());
    }

    static void setOutput() {
        String out = "P4CLIENT=alice_ws (config 'C:\\ws alice\\.p4config')\n"
                + "P4CONFIG=.p4config (config 'C:\\ws alice\\.p4config' )\n"
                + "P4PORT=127.0.0.1:1777 (config 'C:\\ws alice\\.p4config')\n"
                + "P4USER=alice (config 'C:\\ws alice\\.p4config')\n"
                + "P4_127.0.0.1:1777_CHARSET=none (set)\n";
        SortedMap<String, String> set = P4Env.parseSet(out);
        eq("set value without annotation", "127.0.0.1:1777", set.get("P4PORT"));
        eq("set config name", ".p4config", set.get("P4CONFIG"));
        eq("set ignores non-key lines", false, set.containsKey("P4_127.0.0.1:1777_CHARSET"));
        eq("annotated lines keep their source", "P4PORT=127.0.0.1:1777 (config 'C:\\ws alice\\.p4config')", P4Env.annotatedSet(out).get(2));
        eq("set -q form", ".myignore", P4Env.parseSet("P4IGNORE=.myignore\n").get("P4IGNORE"));
    }

    static void hints() {
        // verbatim Windows JVM error when the executable is missing — the old hint missed it
        eq("missing exe (Windows)", true, P4Connect.hint("failed to run 'p4-missing': java.io.IOException: Cannot run program \"p4-missing\" "
                + "(in directory \"C:\\x\"): CreateProcess error=2, The system cannot find the file specified").contains("executable"));
        eq("missing exe (Unix)", true, P4Connect.hint("Cannot run program \"p4\": error=2, No such file or directory").contains("executable"));
        eq("dns", true, P4Connect.hint("Connect to server failed; check $P4PORT.\n\tTCP connect to perforce:1666 failed.\n\tNo such host is known.").contains("does not resolve"));
        eq("refused", true, P4Connect.hint("Connect to server failed; check $P4PORT.\n\tconnect: 127.0.0.1:1999: WSAECONNREFUSED").contains("unreachable"));
        eq("ssl trust", true, P4Connect.hint("The authenticity of '1.2.3.4:1666' can't be established, this may be your first attempt to connect to this P4PORT.").contains("p4 trust"));
        eq("host lock", true, P4Connect.hint("Client 'x' can only be used from host 'build01'.").contains("another machine"));
        eq("unknown client", true, P4Connect.hint("Client 'nosuch' unknown - use 'client' command to create it.").contains("does not exist"));
        eq("password", true, P4Connect.hint("Perforce password (P4PASSWD) invalid or unset.").contains("p4 login"));
    }

    static void isUnder() {
        eq("same dir", true, P4Connect.isUnder("C:\\ws alice", "C:/ws alice"));
        eq("subdir, case-insensitive", true, P4Connect.isUnder("c:\\WS alice\\sub", "C:/ws alice/"));
        eq("sibling prefix is not inside", false, P4Connect.isUnder("C:\\ws alice2", "C:/ws alice"));
    }
}
