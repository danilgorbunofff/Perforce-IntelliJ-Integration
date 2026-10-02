package p4gate;

import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.LocalFilePath;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Dependency-free tests for every parser, argv builder and diagnosis rule. Fixtures are verbatim p4 r25.2 output
 *  (P4/NTX64/2025.2/3051938). No server needed: the live behaviour is covered by {@link P4LiveTest}. */
public final class P4ParseTest {

    static void eq(String what, Object expected, Object actual) {
        assertEquals(what, expected, actual);
    }

    static P4Cli.Tagged tagged(int code, String out, String err) {
        return P4Cli.parseTagged(new P4Cli.Result(code, out, err));
    }

    @Test
    public void json() {
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

    @Test
    public void taggedOutput() {
        P4Cli.Tagged warn = tagged(0, "{\"data\":\"//depot/nonexist.txt - file(s) not opened on this client.\\n\",\"generic\":17,\"severity\":2}\n", "");
        eq("warning is a message, not a record", 0, warn.records().size());
        eq("warning is not an error", null, warn.error());
        eq("warnings are listed", List.of("//depot/nonexist.txt - file(s) not opened on this client."), warn.warnings());

        P4Cli.Tagged err = tagged(1, "{\"data\":\"Client 'nosuch' unknown - use 'client' command to create it.\\n\",\"generic\":2,\"severity\":3}\n", "");
        eq("severity 3 is an error", "Client 'nosuch' unknown - use 'client' command to create it.", err.error());

        P4Cli.Tagged down = tagged(1, "", "Perforce client error:\n\tConnect to server failed; check $P4PORT.\n\tTCP connect to 127.0.0.1:1999 failed.\n");
        eq("connect failure (plain stderr) is an error", true, down.error() != null && down.error().contains("Connect to server failed"));

        // verbatim r25.2 `p4 -ztag -Mj add -f build.log` for an ignored file: exit 1, a "level" message, no severity
        P4Cli.Tagged ignored = tagged(1, "{\"data\":\"C:\\\\ws\\\\build.log - ignored file can't be added.\",\"level\":34}\n"
                + "{\"action\":\"add\",\"depotFile\":\"//depot/M.java\"}\n", "");
        eq("level 34 is a warning", List.of("C:\\ws\\build.log - ignored file can't be added."), ignored.warnings());
        eq("exit 1 with only warnings is not an error", null, ignored.error());
        eq("the record is still read", 1, ignored.records().size());
        eq("exit 1 with nothing said is an error", true, tagged(1, "", "").error() != null);
        eq("level 50 is an error", "bad", tagged(1, "{\"data\":\"bad\",\"level\":50}\n", "").error());

        P4Cli.Tagged truncated = tagged(0, "{\"depotFile\":\"//depot/a", "");
        eq("a truncated JSON line is a message, not a crash", 0, truncated.records().size());
    }

    /** Blocker found in review: -x after the command name is a COMMAND flag (`opened -x` = exclusive locks). */
    @Test
    public void globalOptionsComeBeforeTheCommand() {
        P4Cli cli = new P4Cli("p4", "C:\\ws", Map.of());
        eq("plain argv", List.of("p4", "-d", "C:\\ws", "info"), cli.commandLine("info"));
        eq("tagged with an argument file: -x, -ztag and -Mj precede the command",
                List.of("p4", "-d", "C:\\ws", "-x", "F", "-ztag", "-Mj", "fstat", "-Ro"),
                cli.commandLine(P4Cli.taggedGlobals("F"), "fstat", "-Ro"));
        eq("tagged without an argument file", List.of("p4", "-d", "C:\\ws", "-ztag", "-Mj", "opened"),
                cli.commandLine(P4Cli.taggedGlobals(null), "opened"));
        eq("an empty argument list runs nothing", 0,
                cli.taggedWithArgs(null, () -> false, List.of(), "revert").records().size());
    }

    @Test
    public void missingWorkdirIsReportedNotRun() {
        P4Cli.Result r = new P4Cli("p4", "Z:\\no such dir", Map.of()).run("info");
        eq("missing workdir fails before spawning", -1, r.code());
        eq("and says why", true, r.err().contains("workspace dir does not exist"));
    }

    static final String INFO_KNOWN = "{\"clientCwd\":\"/c/ws alice\",\"clientHost\":\"PC1486\",\"clientName\":\"alice_ws\",\"clientRoot\":\"C:/ws alice\","
            + "\"serverAddress\":\"kubernetes.docker.internal:1777\",\"serverVersion\":\"P4D/NTX64/2025.2/3051938 (2026/08/27)\",\"userName\":\"alice\"}";
    static final String INFO_UNKNOWN = "{\"clientCwd\":\"/c/ws alice\",\"clientHost\":\"PC1486\",\"clientName\":\"*unknown*\","
            + "\"serverAddress\":\"kubernetes.docker.internal:1777\",\"userName\":\"alice\"}";

    @Test
    public void info() {
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

    @Test
    public void pending() {
        List<Map<String, String>> changes = tagged(0,
                "{\"change\":\"5\",\"changeType\":\"public\",\"client\":\"alice_ws\",\"desc\":\"multi line\\n\\nsecond \\\"quoted\\\" para\\n\",\"status\":\"pending\",\"time\":\"1790849023\",\"user\":\"alice\"}\n"
              + "{\"change\":\"3\",\"changeType\":\"public\",\"client\":\"alice_ws\",\"desc\":\"alice work\\n\",\"status\":\"pending\",\"time\":\"1790848344\",\"user\":\"alice\"}\n"
              + "{\"changeType\":\"public\",\"desc\":\"no change field\"}\n", "").records();
        List<Map<String, String>> opened = tagged(0,
                "{\"action\":\"edit\",\"change\":\"3\",\"client\":\"alice_ws\",\"clientFile\":\"//alice_ws/a.txt\",\"depotFile\":\"//depot/a.txt\",\"haveRev\":\"2\",\"rev\":\"2\",\"type\":\"text\",\"user\":\"alice\"}\n"
              + "{\"action\":\"edit\",\"change\":\"default\",\"client\":\"alice_ws\",\"clientFile\":\"//alice_ws/b.txt\",\"depotFile\":\"//depot/b.txt\",\"haveRev\":\"2\",\"rev\":\"2\",\"type\":\"text\",\"user\":\"alice\"}\n"
              + "{\"action\":\"move/add\",\"change\":\"3\",\"client\":\"alice_ws\",\"clientFile\":\"//alice_ws/sub dir/c 2.txt\",\"depotFile\":\"//depot/sub dir/c 2.txt\",\"rev\":\"1\",\"type\":\"text\",\"user\":\"alice\"}\n", "").records();
        List<P4Data.Change> cs = P4Data.groupPending(changes, opened);
        eq("default first", 0L, cs.get(0).id());
        eq("default files", List.of("//depot/b.txt"), cs.get(0).files().stream().map(P4Data.OpenedFile::depotFile).toList());
        eq("numbered order kept, a record without a change number skipped", List.of(0L, 5L, 3L), cs.stream().map(P4Data.Change::id).toList());
        eq("multi-line desc -> first line", "multi line", cs.get(1).desc());
        eq("files grouped by change", 2, cs.get(2).files().size());
        eq("paths with spaces intact", "//depot/sub dir/c 2.txt", cs.get(2).files().get(1).depotFile());
        eq("move/add action", "move/add", cs.get(2).files().get(1).action());
    }

    @Test
    public void submitted() {
        List<P4Data.SubmittedChange> s = P4Data.parseSubmitted(tagged(0,
                "{\"change\":\"1\",\"changeType\":\"public\",\"client\":\"alice_ws\",\"desc\":\"seed\",\"path\":\"//depot/...\",\"status\":\"submitted\",\"time\":\"1790848332\",\"user\":\"alice\"}\n"
              + "{\"change\":\"4\",\"changeType\":\"public\",\"client\":\"bob_ws\",\"desc\":\"BOB private work\\n\",\"oldChange\":\"2\",\"path\":\"//depot/*\",\"status\":\"submitted\",\"time\":\"1790848660\",\"user\":\"bob\"}\n"
              + "{\"change\":\"x\"}\n", "").records());
        eq("newest first, renumbered CL shows its submitted number; junk skipped", List.of(4L, 1L), s.stream().map(P4Data.SubmittedChange::id).toList());
        eq("submitted desc trimmed", "BOB private work", s.get(0).desc());
        eq("submitted time", 1790848660L, s.get(0).time());
    }

    @Test
    public void where() {
        String withSpace = "{\"clientFile\":\"//alice_ws/sub dir/c.txt\",\"depotFile\":\"//depot/sub dir/c.txt\",\"path\":\"C:/ws alice\\\\sub dir\\\\c.txt\"}\n";
        // the old split(" ") parser returned "//alice_ws/sub" for this exact output
        eq("where path with spaces", "C:/ws alice\\sub dir\\c.txt", P4Data.parseWhere(tagged(0, withSpace, "").records()));
        String withUnmap = "{\"clientFile\":\"//alice_ws/x/a.txt\",\"depotFile\":\"//depot/x/a.txt\",\"path\":\"C:/ws/x/a.txt\",\"unmap\":\"\"}\n" + withSpace;
        eq("where skips exclusion lines", "C:/ws alice\\sub dir\\c.txt", P4Data.parseWhere(tagged(0, withUnmap, "").records()));
        eq("where nothing mapped", null, P4Data.parseWhere(List.of()));
    }

    @Test
    public void streams() {
        List<P4Data.StreamSpec> s = P4Data.parseStreams(tagged(0,
                "{\"Stream\":\"//Ace/dev\",\"Type\":\"development\",\"Parent\":\"//Ace/main\",\"Name\":\"dev\",\"Owner\":\"alice\"}\n"
              + "{\"Stream\":\"//Ace/main\",\"Type\":\"mainline\",\"Parent\":\"none\",\"Name\":\"main\",\"Owner\":\"alice\"}\n", "").records());
        eq("streams parsed", 2, s.size());
        eq("stream parent", "//Ace/main", s.get(0).parent());
        eq("mainline parent none", "none", s.get(1).parent());
        P4Cli.Tagged none = tagged(0, "{\"data\":\"No such stream.\\n\",\"generic\":17,\"severity\":2}\n", "");
        eq("no streams is a warning, not an error", null, none.error());
    }

    @Test
    public void setOutput() {
        String out = "P4CLIENT=alice_ws (config 'C:\\ws alice\\.p4config')\n"
                + "P4CONFIG=.p4config (config 'C:\\ws alice\\.p4config' )\n"
                + "P4PORT=127.0.0.1:1777 (config 'C:\\ws alice\\.p4config')\n"
                + "P4USER=alice (config 'C:\\ws alice\\.p4config')\n"
                + "P4_127.0.0.1:1777_CHARSET=none (set)\n"
                + "P4IGNORE=C:\\Program Files (x86)\\ignore.txt (set)\n"
                + "P4HOST=build01 (set) (config 'noconfig')\n";
        SortedMap<String, String> set = P4Env.parseSet(out);
        eq("set value without annotation", "127.0.0.1:1777", set.get("P4PORT"));
        eq("set config name", ".p4config", set.get("P4CONFIG"));
        eq("set ignores non-key lines", false, set.containsKey("P4_127.0.0.1:1777_CHARSET"));
        eq("a parenthesis inside the value is kept", "C:\\Program Files (x86)\\ignore.txt", set.get("P4IGNORE"));
        eq("several annotation groups are stripped", "build01", set.get("P4HOST"));
        eq("annotated lines keep their source", "P4PORT=127.0.0.1:1777 (config 'C:\\ws alice\\.p4config')", P4Env.annotatedSet(out).get(2));
        eq("set -q form", ".myignore", P4Env.parseSetQuiet("P4IGNORE=.myignore\n").get("P4IGNORE"));
        eq("set -q value is never stripped", "C:\\Program Files (x86)\\i.txt",
                P4Env.parseSetQuiet("P4IGNORE=C:\\Program Files (x86)\\i.txt\n").get("P4IGNORE"));
    }

    @Test
    public void hints() {
        // verbatim Windows JVM error when the executable is missing — the old hint missed it
        eq("missing exe (Windows)", true, P4Connect.hint("failed to run 'p4-missing': java.io.IOException: Cannot run program \"p4-missing\" "
                + "(in directory \"C:\\x\"): CreateProcess error=2, The system cannot find the file specified").contains("executable"));
        // a missing working directory is error=267 — the old substring match blamed the executable
        eq("missing workdir (Windows) is not a missing exe", true, P4Connect.hint("failed to run 'p4': java.io.IOException: Cannot run program \"p4\" "
                + "(in directory \"C:\\nope\"): CreateProcess error=267, The directory name is invalid").contains("workspace dir"));
        eq("missing workdir (checked before spawning)", true, P4Connect.hint("workspace dir does not exist: C:\\nope").contains("workspace dir"));
        eq("missing exe (Unix)", true, P4Connect.hint("Cannot run program \"p4\": error=2, No such file or directory").contains("executable"));
        eq("dns", true, P4Connect.hint("Connect to server failed; check $P4PORT.\n\tTCP connect to perforce:1666 failed.\n\tNo such host is known.").contains("does not resolve"));
        eq("refused", true, P4Connect.hint("Connect to server failed; check $P4PORT.\n\tconnect: 127.0.0.1:1999: WSAECONNREFUSED").contains("unreachable"));
        eq("ssl trust", true, P4Connect.hint("The authenticity of '1.2.3.4:1666' can't be established, this may be your first attempt to connect to this P4PORT.").contains("p4 trust"));
        eq("host lock", true, P4Connect.hint("Client 'x' can only be used from host 'build01'.").contains("another machine"));
        eq("unknown client", true, P4Connect.hint("Client 'nosuch' unknown - use 'client' command to create it.").contains("does not exist"));
        eq("password", true, P4Connect.hint("Perforce password (P4PASSWD) invalid or unset.").contains("p4 login"));
    }

    @Test
    public void diagnoseMissingWorkdir() {
        P4Connect.Report r = P4Connect.diagnose(new P4Cli("p4", "Z:\\no such dir", Map.of()));
        eq("verdict", P4Connect.Verdict.NOT_CONNECTED, r.verdict());
        eq("names the workspace dir, not the executable", true, r.firstFailure().contains("workspace dir"));
    }

    @Test
    public void isUnder() {
        eq("same dir", true, P4Connect.isUnder("C:\\ws alice", "C:/ws alice"));
        eq("subdir, case-insensitive", true, P4Connect.isUnder("c:\\WS alice\\sub", "C:/ws alice/"));
        eq("sibling prefix is not inside", false, P4Connect.isUnder("C:\\ws alice2", "C:/ws alice"));
    }

    /** p4 action -> platform FileStatus: what the Local Changes view shows for each opened file. */
    @Test
    public void status() {
        eq("add is new", FileStatus.ADDED, P4Status.of("add", true));
        eq("branch is new", FileStatus.ADDED, P4Status.of("branch", false));
        eq("import is new", FileStatus.ADDED, P4Status.of("import", true));
        eq("move/add is new", FileStatus.ADDED, P4Status.of("move/add", true));
        eq("delete is removed", FileStatus.DELETED, P4Status.of("delete", false));
        eq("move/delete is removed", FileStatus.DELETED, P4Status.of("move/delete", true));
        eq("purge is removed", FileStatus.DELETED, P4Status.of("purge", true));
        eq("edit is modified", FileStatus.MODIFIED, P4Status.of("edit", true));
        eq("action case and padding", FileStatus.MODIFIED, P4Status.of(" EDIT ", true));
        eq("integrate is a merge", FileStatus.MERGE, P4Status.of("integrate", true));
        eq("resolve is a merge", FileStatus.MERGE, P4Status.of("resolve", true));
        eq("an unknown action is treated as a modification", FileStatus.MODIFIED, P4Status.of(null, true));
        eq("modified file missing from disk", FileStatus.DELETED_FROM_FS, P4Status.of("edit", false));
        eq("deleted stays deleted when gone", FileStatus.DELETED, P4Status.of("delete", false));
        eq("added stays added when gone", FileStatus.ADDED, P4Status.of("add", false));
    }

    @Test
    public void revision() {
        eq("have prints as a revision spec", "have", P4RevisionNumber.HAVE.asString());
        eq("a known revision prints as its number", "12", new P4RevisionNumber(12).asString());
        eq("a negative revision is treated as have", "have", new P4RevisionNumber(-1).asString());
        eq("revisions compare by number", -1, new P4RevisionNumber(2).compareTo(new P4RevisionNumber(3)));
        eq("revisions compare by number (2)", 1, new P4RevisionNumber(3).compareTo(new P4RevisionNumber(2)));
        eq("equal revisions compare to 0", 0, new P4RevisionNumber(3).compareTo(new P4RevisionNumber(3)));
        eq("have equals the 0 revision", true, P4RevisionNumber.HAVE.equals(new P4RevisionNumber(0)));
        eq("different revisions are not equal", false, P4RevisionNumber.HAVE.equals(new P4RevisionNumber(4)));
        eq("hashCode follows equals", new P4RevisionNumber(0).hashCode(), P4RevisionNumber.HAVE.hashCode());
        // a revision number from another VCS can only be compared by its printed form
        VcsRevisionNumber foreign = new VcsRevisionNumber() {
            @Override
            public String asString() { return "8"; }

            @Override
            public int compareTo(VcsRevisionNumber other) { return 0; }
        };
        eq("foreign revisions compare by printed form", 1, new P4RevisionNumber(9).compareTo(foreign));
    }

    static Map<String, String> fstat(String depot, String local, String action, String change, String... extra) {
        java.util.HashMap<String, String> m = new java.util.HashMap<>(Map.of("depotFile", depot, "clientFile", local, "action", action, "change", change, "type", "text"));
        for (int i = 0; i + 1 < extra.length; i += 2) m.put(extra[i], extra[i + 1]);
        return m;
    }

    /** `p4 fstat -Ro` records -> opened files with local paths (verbatim shapes from r25.2). */
    @Test
    public void openFiles() {
        List<P4OpenFile> files = P4OpenFile.parse(List.of(
                fstat("//depot/a.txt", "C:\\ws\\a.txt", "edit", "default", "haveRev", "2"),
                fstat("//depot/icon%402x.png", "C:\\ws\\icon@2x.png", "edit", "7", "type", "binary+l"),
                Map.of("depotFile", "//depot/not-opened.txt", "clientFile", "C:\\ws\\not-opened.txt"),
                Map.of("clientFile", "C:\\ws\\orphan.txt", "action", "edit"),
                fstat("//depot/c.txt", "C:\\ws\\c.txt", "integrate", "7", "unresolved", "")));
        eq("records without a depot path, local path or action are dropped", 3, files.size());
        eq("default changelist is 0", 0L, files.get(0).change());
        eq("have revision", 2L, files.get(0).haveRev());
        eq("numbered changelist", 7L, files.get(1).change());
        eq("local path is literal, depot path escaped", "C:\\ws\\icon@2x.png", files.get(1).localPath());
        eq("binary+l is binary", true, files.get(1).binary());
        eq("text is not binary", false, files.get(0).binary());
        eq("unresolved flag", true, files.get(2).unresolved());
        Map<String, P4OpenFile> byLocal = P4OpenFile.byLocal(files);
        eq("lookup ignores separators (and case on Windows)", "//depot/a.txt",
                byLocal.get(P4OpenFile.normalize(java.io.File.separatorChar == '\\' ? "c:/WS/a.txt" : "C:/ws/a.txt")).depotFile());
    }

    @Test
    public void binaryTypes() {
        for (String t : List.of("binary", "ubinary", "binary+F", "xbinary", "apple", "resource", "ctempobj", "BINARY+S2")) {
            eq(t + " is binary", true, P4OpenFile.isBinaryType(t));
        }
        for (String t : List.of("text", "text+k", "unicode", "utf8", "utf16", "symlink", "ktext", "")) {
            eq(t + " is text", false, P4OpenFile.isBinaryType(t));
        }
        eq("null type", false, P4OpenFile.isBinaryType(null));
    }

    static P4OpenFile open(String depot, String local, String action, String moved) {
        return new P4OpenFile(depot, local, action, 0, "text", moved, false, 1);
    }

    /** What Local Changes shows for each kind of opened file. */
    @Test
    public void changePlan() {
        P4OpenFile edit = open("//depot/a.txt", "C:/ws/a.txt", "edit", "");
        P4OpenFile gone = open("//depot/gone.txt", "C:/ws/gone.txt", "edit", "");
        P4OpenFile add = open("//depot/n.txt", "C:/ws/n.txt", "add", "");
        P4OpenFile del = open("//depot/d.txt", "C:/ws/d.txt", "delete", "");
        P4OpenFile from = open("//depot/old.txt", "C:/ws/old.txt", "move/delete", "//depot/new.txt");
        P4OpenFile to = open("//depot/new.txt", "C:/ws/new.txt", "move/add", "//depot/old.txt");
        P4OpenFile conflict = new P4OpenFile("//depot/c.txt", "C:/ws/c.txt", "integrate", 0, "text", "", true, 3);
        List<P4ChangeProvider.Entry> plan = P4ChangeProvider.plan(List.of(edit, gone, add, del, from, to, conflict),
                path -> !path.endsWith("gone.txt") && !path.endsWith("d.txt") && !path.endsWith("old.txt"));
        eq("a move pair is ONE entry", 6, plan.size());
        eq("edit: depot before, disk after", List.of(edit, "C:/ws/a.txt", FileStatus.MODIFIED),
                List.of(plan.get(0).before(), plan.get(0).afterLocal(), plan.get(0).status()));
        eq("opened but missing from disk is a locally deleted file", null, plan.get(1).status());
        eq("locally deleted path", "C:/ws/gone.txt", plan.get(1).afterLocal());
        eq("add has no before side", null, plan.get(2).before());
        eq("add", FileStatus.ADDED, plan.get(2).status());
        eq("delete has no after side", null, plan.get(3).afterLocal());
        eq("delete", FileStatus.DELETED, plan.get(3).status());
        eq("move: before is the moved-from file", from, plan.get(4).before());
        eq("move: after is the new path", "C:/ws/new.txt", plan.get(4).afterLocal());
        eq("unresolved integrate is a conflict", FileStatus.MERGED_WITH_CONFLICTS, plan.get(5).status());
        eq("a move/delete without its partner is a delete", FileStatus.DELETED,
                P4ChangeProvider.plan(List.of(from), p -> false).get(0).status());
    }

    @Test
    public void openedFile() {
        P4Data.OpenedFile def = P4Data.openedFile(Map.of(
                "depotFile", "//depot/b.txt", "clientFile", "//alice_ws/b.txt", "action", "edit", "change", "default", "type", "text"));
        eq("the default changelist is 0", 0L, def.change());
        eq("action is kept", "edit", def.action());
        P4Data.OpenedFile numbered = P4Data.openedFile(Map.of("depotFile", "//depot/a.txt", "action", "add", "change", "12"));
        eq("a numbered changelist keeps its id", 12L, numbered.change());
        eq("a missing type is empty", "", numbered.type());
        eq("a missing depot path is not a file", null, P4Data.openedFile(Map.of("action", "edit")));
        eq("an unparsable changelist falls back to the default", 0L,
                P4Data.openedFile(Map.of("depotFile", "//depot/a.txt", "change", "garbage")).change());
    }

    /** The local paths a platform change turns into, which is what p4 revert/submit are given. */
    @Test
    public void changeArgs() {
        FilePath path = new LocalFilePath("C:/ws/a.txt", false);
        FilePath other = new LocalFilePath("C:/ws/b.txt", false);
        P4ContentRevision a = new P4ContentRevision(path, "//depot/a.txt", P4RevisionNumber.HAVE, () -> null);
        P4ContentRevision b = new P4ContentRevision(other, "//depot/b.txt", P4RevisionNumber.HAVE, () -> null);
        eq("a modified file yields one path", List.of(path.getPath()), P4Vcs.fileArgs(new Change(a, a, FileStatus.MODIFIED)));
        eq("an added file yields the path it was added at", List.of(path.getPath()), P4Vcs.fileArgs(new Change(null, a, FileStatus.ADDED)));
        eq("a deleted file yields the path it was deleted from", List.of(path.getPath()), P4Vcs.fileArgs(new Change(a, null, FileStatus.DELETED)));
        eq("a change spanning two paths yields both", List.of(path.getPath(), other.getPath()), P4Vcs.fileArgs(new Change(a, b, FileStatus.MODIFIED)));
        eq("the depot side is printed at #have", "//depot/a.txt#have", a.spec());
    }

    @Test
    public void contentDecoding() {
        eq("no BOM", 0, P4ContentRevision.bomLength("abc".getBytes()));
        eq("UTF-8 BOM", 3, P4ContentRevision.bomLength(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a'}));
        eq("UTF-16LE BOM", 2, P4ContentRevision.bomLength(new byte[]{(byte) 0xFF, (byte) 0xFE, 'a', 0}));
        eq("UTF-32LE BOM", 4, P4ContentRevision.bomLength(new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 0}));
        eq("UTF-16LE BOM charset", java.nio.charset.StandardCharsets.UTF_16LE,
                P4ContentRevision.bomCharset(new byte[]{(byte) 0xFF, (byte) 0xFE, 'a', 0}));
        eq("UTF-32 is told apart from UTF-16LE", "UTF-32LE",
                P4ContentRevision.bomCharset(new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 0}).name());
        eq("no BOM, no charset", null, P4ContentRevision.bomCharset("x".getBytes()));
        eq("text type -> text revision", P4ContentRevision.class,
                P4ContentRevision.of(new LocalFilePath("C:/ws/a.txt", false), "//depot/a.txt", "text", P4RevisionNumber.HAVE, () -> null).getClass());
        eq("binary type -> binary revision", P4ContentRevision.Binary.class,
                P4ContentRevision.of(new LocalFilePath("C:/ws/a.bin", false), "//depot/a.bin", "binary", P4RevisionNumber.HAVE, () -> null).getClass());
    }

    /** The argument file p4 -x reads: one argument per line, or paths with spaces would be split. */
    @Test
    public void argsFile() throws Exception {
        String file = P4Args.file("p4ii-test", List.of("C:/ws/a.txt", "C:/ws/sub dir/b.txt", ""));
        try {
            eq("one argument per line, LF only, empty argument quoted",
                    "C:/ws/a.txt\nC:/ws/sub dir/b.txt\n\"\"\n", Files.readString(Path.of(file)));
            eq("never written into the workspace (a VFS event there re-triggers the change provider)", true,
                    Path.of(file).startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath())
                            || Path.of(file).toRealPath().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()));
        } finally {
            P4Args.delete(file);
        }
        eq("the argument file is removed afterwards", false, Files.exists(Path.of(file)));
    }

    /** Verified on r25.2: `p4 edit icon@2x.png` fails ("Invalid changelist/client/label/date '@2x.png'"). */
    @Test
    public void escaping() {
        eq("@ # * %", "C:\\ws\\icon%402x.png %23 %2A %25", P4Args.escape("C:\\ws\\icon@2x.png # * %"));
        eq("% is escaped first, never twice", "100%25", P4Args.escape("100%"));
        eq("plain paths untouched", "C:/ws/sub dir/a.txt", P4Args.escape("C:/ws/sub dir/a.txt"));
        eq("all", List.of("a%40b", "c"), P4Args.escapeAll(List.of("a@b", "c")));
    }

    @Test
    public void submitSpec() {
        eq("every description line is tab-indented (an unindented line ends the field)",
                "Change:\tnew\n\nDescription:\n\tfix the thing\n\t\n\tdetails\n", P4Ops.changeSpec("fix the thing\r\n\r\ndetails\n"));
        eq("change -i output", 42L, P4Ops.createdChange("Change 42 created.\n"));
        eq("no change number", 0L, P4Ops.createdChange("Error in change specification."));
    }

    @Test
    public void operationOutcome() {
        eq("success", null, P4Ops.outcome(tagged(0, "{\"depotFile\":\"//depot/a.txt\",\"action\":\"edit\"}\n", ""), 1));
        eq("an error wins", "boom", P4Ops.outcome(tagged(1, "{\"data\":\"boom\\n\",\"severity\":3}\n", ""), 1));
        eq("a skipped file is a failure", "//depot/x - file(s) not on client.",
                P4Ops.outcome(tagged(0, "{\"data\":\"//depot/x - file(s) not on client.\\n\",\"severity\":2}\n", ""), 1));
        eq("a warning beside full success is fine", null, P4Ops.outcome(tagged(0,
                "{\"depotFile\":\"//depot/a.txt\"}\n{\"data\":\"note\\n\",\"severity\":2}\n", ""), 1));
    }

    @Test
    public void rootCheckerNames() {
        eq("defaults", List.of(".p4config", "p4config.txt"), P4RootChecker.configNames(null));
        eq("the env name comes first", List.of("p4.env", ".p4config", "p4config.txt"), P4RootChecker.configNames("p4.env"));
        eq("a path-valued P4CONFIG is not a file name", List.of(".p4config", "p4config.txt"), P4RootChecker.configNames("C:\\cfg\\p4.env"));
        eq("no duplicates", List.of(".P4CONFIG", "p4config.txt"), P4RootChecker.configNames(".P4CONFIG"));
        assertTrue(P4RootChecker.isConfigName("P4CONFIG.TXT", P4RootChecker.configNames(null)));
    }
}
