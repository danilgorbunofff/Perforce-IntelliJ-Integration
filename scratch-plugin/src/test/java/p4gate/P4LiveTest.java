package p4gate;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Every p4-facing code path the plugin has, driven against a REAL Helix Core server (see {@link P4Lab}).
 * Skipped unless P4_BIN points at p4 + p4d. These are the tests the VCS provider shipped without.
 */
public final class P4LiveTest {
    private static final BooleanSupplier NEVER = () -> false;
    private P4Lab lab;
    private P4Cli cli;

    @Before
    public void start() throws Exception {
        Assume.assumeTrue("P4_BIN not set: live tests skipped", P4Lab.binDir() != null);
        lab = P4Lab.start();
        cli = lab.cli();
    }

    @After
    public void stop() {
        if (lab != null) lab.close();
    }

    private List<P4OpenFile> opened() {
        P4Data.Listing<P4OpenFile> l = P4OpenFile.all(cli, NEVER);
        assertNull("opened listing error", l.error());
        return l.items();
    }

    private P4OpenFile openedAt(String rel) {
        return P4OpenFile.byLocal(opened()).get(P4OpenFile.normalize(lab.local(rel)));
    }

    // ------------------------------------------------------------------ connection diagnosis

    @Test
    public void diagnosisReady() {
        P4Connect.Report r = P4Connect.diagnose(cli);
        assertEquals(r.text(), P4Connect.Verdict.READY, r.verdict());
    }

    @Test
    public void diagnosisUnknownClient() {
        P4Connect.Report r = P4Connect.diagnose(lab.cli(P4Lab.USER, "nosuch_ws", lab.ws));
        assertEquals(r.text(), P4Connect.Verdict.NOT_READY, r.verdict());
        assertTrue(r.firstFailure(), r.firstFailure().contains("nosuch_ws") && r.firstFailure().contains("does not exist"));
    }

    @Test
    public void diagnosisMissingExecutable() {
        P4Connect.Report r = P4Connect.diagnose(new P4Cli(lab.p4() + "-missing", lab.ws.toString(), Map.of()));
        assertEquals(P4Connect.Verdict.NOT_CONNECTED, r.verdict());
        assertTrue(r.text(), r.firstFailure().contains("executable"));
    }

    // ------------------------------------------------------------------ tool window listings

    @Test
    public void pendingAndSubmittedListings() throws Exception {
        long cl = lab.newChange("alice work\nsecond line");
        P4Lab.ok(cli.run("edit", "-c", Long.toString(cl), "a.txt"));
        P4Lab.ok(cli.run("edit", "sub dir/c 2.txt"));
        P4Data.ClientInfo info = P4Data.info(cli);
        assertTrue(info.clientKnown());
        P4Data.Listing<P4Data.Change> pending = P4Data.pendingChanges(cli, info.clientName());
        assertNull(pending.error());
        assertEquals(List.of(0L, cl), pending.items().stream().map(P4Data.Change::id).toList());
        assertEquals("//depot/sub dir/c 2.txt", pending.items().get(0).files().get(0).depotFile());
        assertEquals("alice work", pending.items().get(1).desc());
        P4Data.Listing<P4Data.SubmittedChange> index = P4Data.submittedIndex(cli, info.clientName(), 10);
        assertNull(index.error());
        assertEquals(List.of(1L), index.items().stream().map(P4Data.SubmittedChange::id).toList());
        assertTrue(P4Data.clientStream(cli).items().isEmpty()); // classic client
    }

    @Test
    public void refreshReportsAnUnreachableServer() {
        P4Cli dead = new P4Cli(lab.p4(), lab.ws.toString(), Map.of("P4PORT", "127.0.0.1:1", "P4USER", "x", "P4CLIENT", "x",
                "P4CONFIG", ".none"));
        assertNotNull(P4Data.info(dead).error());
        assertNotNull(P4OpenFile.all(dead, NEVER).error());
    }

    // ------------------------------------------------------------------ the VCS provider's inputs

    /** The edge cases the original provider could not survive: @ in a name, a space, binary, move, delete. */
    @Test
    public void openedFilesWithLocalPaths() throws Exception {
        assertNull(P4Ops.edit(cli, List.of(lab.local("a.txt"), lab.local("icon@2x.png")), NEVER));
        P4Lab.ok(cli.run("edit", "sub dir/c 2.txt")); // move needs it open (unopened, p4 only warns, exit 0)
        P4Lab.ok(cli.run("move", "sub dir/c 2.txt", "sub dir/moved.txt"));
        P4Lab.ok(cli.run("delete", "blob.bin"));
        lab.write("new file.txt", "n\n");
        assertNull(P4Ops.add(cli, List.of(lab.local("new file.txt")), NEVER));

        List<P4OpenFile> files = opened();
        assertEquals(6, files.size());
        assertEquals("edit", openedAt("icon@2x.png").action());
        assertEquals("//depot/icon%402x.png", openedAt("icon@2x.png").depotFile());
        assertEquals("add", openedAt("new file.txt").action());
        assertEquals("binary", openedAt("blob.bin").type());
        assertEquals("move/add", openedAt("sub dir/moved.txt").action());
        assertEquals(1L, openedAt("a.txt").haveRev());

        List<P4ChangeProvider.Entry> plan = P4ChangeProvider.plan(files, p -> Files.exists(Path.of(p)));
        assertEquals("the move pair is one entry", 5, plan.size());
        P4ChangeProvider.Entry move = plan.stream().filter(e -> e.afterLocal() != null && e.afterLocal().endsWith("moved.txt")).findFirst().orElseThrow();
        assertEquals("//depot/sub dir/c 2.txt", move.before().depotFile());
    }

    @Test
    public void openedAmongLocalPaths() throws Exception {
        assertNull(P4Ops.edit(cli, List.of(lab.local("icon@2x.png")), NEVER));
        P4Data.Listing<P4OpenFile> l = P4OpenFile.among(cli, NEVER, List.of(lab.local("icon@2x.png"), lab.local("a.txt")));
        assertNull(l.error());
        assertEquals("only the opened one", List.of("//depot/icon%402x.png"), l.items().stream().map(P4OpenFile::depotFile).toList());
    }

    /** The diff's before side is the DEPOT content at #have, not the edited file on disk (the original bug). */
    @Test
    public void contentRevisionPrintsHave() throws Exception {
        assertNull(P4Ops.edit(cli, List.of(lab.local("a.txt"), lab.local("icon@2x.png"), lab.local("blob.bin")), NEVER));
        lab.write("a.txt", "EDITED\n");
        lab.write("icon@2x.png", "EDITED\n");
        Files.write(lab.ws.resolve("blob.bin"), new byte[]{9});
        for (P4OpenFile f : opened()) {
            P4ContentRevision rev = P4ContentRevision.of(new com.intellij.openapi.vcs.LocalFilePath(f.localPath(), false),
                    f.depotFile(), f.type(), new P4RevisionNumber(f.haveRev()), () -> cli);
            byte[] have = rev.getContentAsBytes();
            switch (f.depotFile()) {
                case "//depot/a.txt" -> assertEquals("a1", new String(have).strip());
                case "//depot/icon%402x.png" -> assertEquals("img", new String(have).strip());
                case "//depot/blob.bin" -> {
                    assertTrue(rev instanceof P4ContentRevision.Binary);
                    assertArrayEquals(new byte[]{0, 1, 2, (byte) 0xFF, 'b'}, have);
                }
                default -> throw new AssertionError(f.depotFile());
            }
        }
    }

    @Test
    public void contentRevisionOfAMissingRevisionFails() {
        P4ContentRevision rev = new P4ContentRevision(new com.intellij.openapi.vcs.LocalFilePath(lab.local("nope.txt"), false),
                "//depot/nope.txt", P4RevisionNumber.HAVE, () -> cli);
        try {
            rev.getContentAsBytes();
            throw new AssertionError("expected a VcsException");
        } catch (com.intellij.openapi.vcs.VcsException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("nope.txt"));
        }
    }

    // ------------------------------------------------------------------ edit / add / delete / revert

    @Test
    public void editMakesTheFileWritableAndOpened() throws Exception {
        Path icon = lab.ws.resolve("icon@2x.png");
        assertFalse("synced files are read-only", Files.isWritable(icon));
        assertNull(P4Ops.edit(cli, List.of(icon.toString()), NEVER));
        assertTrue(Files.isWritable(icon));
        assertEquals("edit", openedAt("icon@2x.png").action());
    }

    @Test
    public void editOfAFileOutsideTheDepotIsAFailure() throws Exception {
        lab.write("never-added.txt", "x\n");
        assertNotNull("a silently skipped file must be reported", P4Ops.edit(cli, List.of(lab.local("never-added.txt")), NEVER));
    }

    /** Add current file said "OK" for a file p4 refused ("can't add (already opened for edit)": a warning, exit 0). */
    @Test
    public void addStrictReportsWhatP4Refused() throws Exception {
        assertNull(P4Ops.edit(cli, List.of(lab.local("a.txt")), NEVER));
        String edited = P4Ops.addStrict(cli, List.of(lab.local("a.txt")), NEVER);
        assertNotNull("a file already opened for edit cannot be added", edited);
        assertTrue(edited, edited.contains("can't add"));
        lab.write("fresh.txt", "new\n");
        assertNull("a genuinely new file is added", P4Ops.addStrict(cli, List.of(lab.local("fresh.txt")), NEVER));
        assertNull("adding it again is a harmless no-op (p4: 'currently opened for add'), like editing an opened file",
                P4Ops.addStrict(cli, List.of(lab.local("fresh.txt")), NEVER));
    }

    @Test
    public void addWithWildcardCharactersAndDeleteMissing() throws Exception {
        lab.write("new@1.txt", "n\n");
        assertNull(P4Ops.add(cli, List.of(lab.local("new@1.txt")), NEVER));
        assertEquals("//depot/new%401.txt", openedAt("new@1.txt").depotFile());

        Path blob = lab.ws.resolve("blob.bin");
        blob.toFile().setWritable(true);
        Files.delete(blob);
        assertNull(P4Ops.deleteMissing(cli, List.of(blob.toString()), NEVER));
        assertEquals("delete", openedAt("blob.bin").action());
    }

    /** Rollback = real revert: content restored, and a file deleted from disk comes back. */
    @Test
    public void revertRestoresContentAndMissingFiles() throws Exception {
        assertNull(P4Ops.edit(cli, List.of(lab.local("a.txt"), lab.local("icon@2x.png")), NEVER));
        lab.write("a.txt", "EDITED\n");
        Files.delete(lab.ws.resolve("icon@2x.png"));
        assertNull(P4Ops.revert(cli, List.of(lab.local("a.txt"), lab.local("icon@2x.png")), NEVER));
        assertEquals("a1\n", lab.read("a.txt"));
        assertEquals("img\n", lab.read("icon@2x.png"));
        assertTrue(opened().isEmpty());
    }

    @Test
    public void revertOfAnAddKeepsTheFile() throws Exception {
        lab.write("keep.txt", "k\n");
        assertNull(P4Ops.add(cli, List.of(lab.local("keep.txt")), NEVER));
        assertNull(P4Ops.revert(cli, List.of(lab.local("keep.txt")), NEVER));
        assertTrue(Files.exists(lab.ws.resolve("keep.txt")));
        assertTrue(opened().isEmpty());
    }

    // ------------------------------------------------------------------ IDE file operations (the VFS listener's p4 side)

    /** The IDE already renamed/moved the files on disk; p4 must record real moves without touching the disk. */
    @Test
    public void ideRenamesBecomePerforceMoves() throws Exception {
        assertNull(P4Ops.edit(cli, List.of(lab.local("sub dir/c 2.txt")), NEVER)); // one already open, one not
        for (String[] mv : new String[][]{{"a.txt", "renamed.txt"}, {"icon@2x.png", "sub dir/icon@3x.png"}, {"sub dir/c 2.txt", "c3.txt"}}) {
            lab.ws.resolve(mv[0]).toFile().setWritable(true);
            Files.move(lab.ws.resolve(mv[0]), lab.ws.resolve(mv[1]));
        }
        lab.write("untracked.txt", "u\n");
        Files.move(lab.ws.resolve("untracked.txt"), lab.ws.resolve("untracked2.txt"));
        String error = P4Ops.moved(cli, List.of(
                new String[]{lab.local("a.txt"), lab.local("renamed.txt")},
                new String[]{lab.local("icon@2x.png"), lab.local("sub dir/icon@3x.png")},
                new String[]{lab.local("sub dir/c 2.txt"), lab.local("c3.txt")},
                new String[]{lab.local("untracked.txt"), lab.local("untracked2.txt")}), NEVER);
        assertNull(error, error);
        assertEquals("move/add", openedAt("renamed.txt").action());
        assertEquals("//depot/a.txt", openedAt("renamed.txt").movedFile());
        assertEquals("move/add", openedAt("sub dir/icon@3x.png").action());
        assertEquals("move/add", openedAt("c3.txt").action());
        assertNull("an untracked file is simply not Perforce's business", openedAt("untracked2.txt"));
        assertEquals("disk untouched", "a1\n", lab.read("renamed.txt"));
        assertEquals("3 moves = 6 open files", 6, opened().size());
    }

    @Test
    public void ideRenameOfAnAddedFileRenamesTheAdd() throws Exception {
        lab.write("n2.txt", "n\n");
        assertNull(P4Ops.add(cli, List.of(lab.local("n2.txt")), NEVER));
        Files.move(lab.ws.resolve("n2.txt"), lab.ws.resolve("n3.txt"));
        assertNull(P4Ops.moved(cli, List.<String[]>of(new String[]{lab.local("n2.txt"), lab.local("n3.txt")}), NEVER));
        assertEquals("add", openedAt("n3.txt").action());
        assertNull(openedAt("n2.txt"));
    }

    @Test
    public void ideDeletesBecomePerforceDeletes() throws Exception {
        lab.write("n1.txt", "n\n");
        assertNull(P4Ops.add(cli, List.of(lab.local("n1.txt")), NEVER));
        assertNull(P4Ops.edit(cli, List.of(lab.local("a.txt")), NEVER));
        lab.write("untracked.txt", "u\n");
        for (String f : List.of("a.txt", "n1.txt", "blob.bin", "untracked.txt")) {
            lab.ws.resolve(f).toFile().setWritable(true);
            Files.delete(lab.ws.resolve(f));
        }
        String error = P4Ops.deleted(cli, List.of(lab.local("a.txt"), lab.local("n1.txt"), lab.local("blob.bin"), lab.local("untracked.txt")), NEVER);
        assertNull(error, error);
        assertEquals("an edited file becomes a delete", "delete", openedAt("a.txt").action());
        assertEquals("delete", openedAt("blob.bin").action());
        assertNull("a deleted add is simply dropped", openedAt("n1.txt"));
        assertEquals(2, opened().size());
    }

    @Test
    public void ideCreatedFilesAreAddedExceptIgnoredOnes() throws Exception {
        Files.writeString(lab.ws.resolve("p4ignore.txt"), "*.log\n");
        lab.write("Main.java", "class Main {}\n");
        lab.write("build.log", "noise\n");
        assertNull(P4Ops.addNew(cli, List.of(lab.local("Main.java"), lab.local("build.log")), NEVER));
        assertEquals("add", openedAt("Main.java").action());
        assertNull("ignored by P4IGNORE: skipped silently", openedAt("build.log"));
    }

    // ------------------------------------------------------------------ commit

    /** p4 cannot submit part of a changelist: the selected files move to a new change carrying the message. */
    @Test
    public void submitExactlyTheSelectedFilesWithTheMessage() throws Exception {
        long cl = lab.newChange("old description");
        P4Lab.ok(cli.run("edit", "-c", Long.toString(cl), "a.txt", "sub dir/c 2.txt"));
        lab.write("a.txt", "a2\n");
        P4Ops.SubmitResult r = P4Ops.submit(cli, List.of(lab.local("a.txt")), "commit dialog message\n\nbody", NEVER);
        assertNull(r.error(), r.error());
        assertTrue(r.submitted() > cl);

        P4Cli.Tagged described = cli.tagged("describe", "-s", Long.toString(r.submitted()));
        assertEquals("commit dialog message\n\nbody\n", described.records().get(0).get("desc"));
        assertEquals("//depot/a.txt", described.records().get(0).get("depotFile0"));
        assertFalse("only the selected file", described.records().get(0).containsKey("depotFile1"));
        P4OpenFile left = openedAt("sub dir/c 2.txt");
        assertEquals("the other file stays in its change", cl, left.change());
        assertEquals("the source change keeps its files and description", "pending",
                cli.tagged("change", "-o", Long.toString(cl)).records().get(0).get("Status"));
    }

    @Test
    public void submitOfAWholeChangeDeletesTheEmptiedSource() throws Exception {
        long cl = lab.newChange("old");
        P4Lab.ok(cli.run("edit", "-c", Long.toString(cl), "a.txt"));
        lab.write("a.txt", "a2\n");
        P4Ops.SubmitResult r = P4Ops.submit(cli, List.of(lab.local("a.txt")), "msg", NEVER);
        assertNull(r.error(), r.error());
        assertNotNull("the empty source change is gone",
                cli.tagged("change", "-o", Long.toString(cl)).error());
    }

    @Test
    public void submitOfTheDefaultChangeAndOfAMove() throws Exception {
        P4Lab.ok(cli.run("edit", "sub dir/c 2.txt"));
        P4Lab.ok(cli.run("move", "sub dir/c 2.txt", "sub dir/moved.txt"));
        assertNull(P4Ops.edit(cli, List.of(lab.local("icon@2x.png")), NEVER));
        P4Ops.SubmitResult r = P4Ops.submit(cli, List.of(lab.local("sub dir/c 2.txt"), lab.local("sub dir/moved.txt"),
                lab.local("icon@2x.png")), "move and edit", NEVER);
        assertNull(r.error(), r.error());
        assertTrue(opened().isEmpty());
        assertEquals("c", lab.read("sub dir/moved.txt").strip());
    }

    @Test
    public void submitRefusesFilesThatAreNotOpenAndChangesNothing() throws Exception {
        P4Lab.ok(cli.run("edit", "a.txt"));
        P4Ops.SubmitResult r = P4Ops.submit(cli, List.of(lab.local("a.txt"), lab.local("icon@2x.png")), "msg", NEVER);
        assertNotNull(r.error());
        assertTrue(r.error(), r.error().contains("icon@2x.png"));
        assertEquals("no change was created", 1, P4Data.pendingChanges(cli, P4Lab.CLIENT).items().size());
        assertEquals(0L, openedAt("a.txt").change());
    }

    @Test
    public void submitRefusesAnEmptyMessage() throws Exception {
        P4Lab.ok(cli.run("edit", "a.txt"));
        assertNotNull(P4Ops.submit(cli, List.of(lab.local("a.txt")), "  ", NEVER).error());
    }

    /** Out-of-date file: p4 refuses the submit; the files stay in a pending change with the message. */
    @Test
    public void aRefusedSubmitLeavesAPendingChange() throws Exception {
        Path bobWs = lab.base.resolve("ws bob");
        lab.createClient("bob", "bob_ws", bobWs);
        P4Cli bob = lab.cli("bob", "bob_ws", bobWs);
        P4Lab.ok(bob.run("sync"));
        P4Lab.ok(bob.run("edit", "a.txt"));
        bobWs.resolve("a.txt").toFile().setWritable(true);
        Files.writeString(bobWs.resolve("a.txt"), "bob\n");
        P4Lab.ok(bob.run("submit", "-d", "bob wins"));

        P4Lab.ok(cli.run("edit", "a.txt"));
        lab.write("a.txt", "alice\n");
        P4Ops.SubmitResult r = P4Ops.submit(cli, List.of(lab.local("a.txt")), "alice change", NEVER);
        assertNotNull(r.error());
        assertTrue(r.pending() > 0);
        assertTrue(r.error(), r.error().contains("pending change " + r.pending()));
        assertEquals(r.pending(), openedAt("a.txt").change());
    }

    // ------------------------------------------------------------------ tool window operations

    @Test
    public void reconcileOpensExactlyThePreviewedFiles() throws Exception {
        lab.write("r@new.txt", "x\n");
        lab.ws.resolve("a.txt").toFile().setWritable(true);
        lab.write("a.txt", "edited outside the IDE\n");
        P4Cli.Tagged preview = cli.tagged(null, NEVER, "reconcile", "-n", "-f", "//...");
        assertNull(preview.error());
        List<Map<String, String>> recs = preview.records();
        assertTrue("the @ file is offered", recs.stream().anyMatch(r -> r.get("depotFile").equals("//depot/r%40new.txt")));
        P4Cli.Tagged done = P4Panel.reconcileExactly(cli, recs, NEVER);
        assertNull(done.error());
        assertEquals("add", openedAt("r@new.txt").action());
        assertEquals("edit", openedAt("a.txt").action());
    }

    @Test
    public void ignoreRuleIsWrittenAndHonored() throws Exception {
        String local = lab.write("build.log", "noise\n").toString();
        String result = P4Panel.writeIgnoreRule(cli, local);
        assertTrue(result, result.startsWith("now ignored"));
        assertEquals(List.of(local), P4Data.ignored(cli, List.of(local)));
    }

    @Test
    public void submitInToolWindowRenumbersAndLists() throws Exception {
        long cl = lab.newChange("tool window submit");
        P4Lab.ok(cli.run("edit", "-c", Long.toString(cl), "a.txt"));
        P4Lab.ok(cli.run(null, NEVER, "submit", "-c", Long.toString(cl)));
        assertEquals(cl, P4Data.submittedIndex(cli, P4Lab.CLIENT, 1).items().get(0).id());
    }

    /** The tree is a snapshot: a file opened after the last Refresh (auto-checkout) must survive a discard-revert. */
    @Test
    public void discardRevertTouchesOnlyTheConfirmedFiles() throws Exception {
        P4Lab.ok(cli.run("edit", "a.txt"));
        lab.write("a.txt", "shown in the dialog\n");
        P4Data.Change shown = P4Data.pendingChanges(cli, P4Lab.CLIENT).items().get(0);
        assertNull(P4Ops.edit(cli, List.of(lab.local("sub dir/c 2.txt")), NEVER));
        lab.write("sub dir/c 2.txt", "never shown\n");
        assertNull(P4Panel.revertConfirmed(cli, shown).error());
        assertEquals("a1\n", lab.read("a.txt"));
        assertEquals("never shown\n", lab.read("sub dir/c 2.txt"));
        assertEquals("edit", openedAt("sub dir/c 2.txt").action());
    }

    @Test
    public void toolWindowSubmitRefusesAChangelistThatChangedSinceRefresh() throws Exception {
        long cl = lab.newChange("reviewed");
        P4Lab.ok(cli.run("edit", "-c", Long.toString(cl), "a.txt"));
        P4Data.Change shown = P4Data.pendingChanges(cli, P4Lab.CLIENT).items().get(1);
        P4Lab.ok(cli.run("edit", "-c", Long.toString(cl), "sub dir/c 2.txt"));
        P4Cli.Result refused = P4Panel.submitConfirmed(cli, shown);
        assertFalse(refused.ok());
        assertTrue(refused.text(), refused.text().contains("Nothing was submitted"));
        assertEquals("nothing submitted", List.of(1L), P4Data.submittedIndex(cli, P4Lab.CLIENT, 5).items().stream().map(P4Data.SubmittedChange::id).toList());
        P4Data.Change fresh = P4Data.pendingChanges(cli, P4Lab.CLIENT).items().get(1);
        P4Cli.Result submitted = P4Panel.submitConfirmed(cli, fresh);
        assertTrue(submitted.text(), submitted.ok());
        assertTrue(opened().isEmpty());
    }

    /** Jobs fixed by the changelist the files were in are fixed by the submitted one (and closed). */
    @Test
    public void commitCarriesJobFixes() throws Exception {
        P4Cli.Result job = cli.runWithInput("Job:\tnew\nStatus:\topen\nUser:\talice\nDescription:\n\ta bug\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), "job", "-i");
        String name = job.out().replaceAll("(?s).*Job (\\S+) saved.*", "$1").strip();
        long cl = lab.newChange("work");
        P4Lab.ok(cli.run("edit", "-c", Long.toString(cl), "a.txt"));
        P4Lab.ok(cli.run("fix", "-c", Long.toString(cl), name));
        lab.write("a.txt", "fixed\n");
        P4Ops.SubmitResult r = P4Ops.submit(cli, List.of(lab.local("a.txt")), "fixes the bug", NEVER);
        assertNull(r.error(), r.error());
        List<Map<String, String>> fixes = cli.tagged("fixes", "-j", name).records();
        assertEquals("one fix, by the submitted change: " + fixes, 1, fixes.size());
        assertEquals(Long.toString(r.submitted()), fixes.get(0).get("Change"));
        assertEquals("closed", cli.tagged("jobs", "-e", name).records().get(0).get("Status"));
        assertNotNull("the emptied source change is gone", cli.tagged("change", "-o", Long.toString(cl)).error());
    }

    /** Renamed in the IDE, then deleted in the IDE: the depot file is opened for delete. */
    @Test
    public void ideDeleteOfARenamedFileDeletesTheSource() throws Exception {
        P4Lab.ok(cli.run("edit", "a.txt"));
        P4Lab.ok(cli.run("move", "a.txt", "renamed.txt"));
        lab.ws.resolve("renamed.txt").toFile().setWritable(true);
        Files.delete(lab.ws.resolve("renamed.txt"));
        assertNull(P4Ops.deleted(cli, List.of(lab.local("renamed.txt")), NEVER));
        assertEquals(1, opened().size());
        assertEquals("delete", openedAt("a.txt").action());
        assertFalse(Files.exists(lab.ws.resolve("a.txt")));
    }

    @Test
    public void shelveThenReplaceTheShelf() throws Exception {
        long cl = lab.newChange("shelf");
        P4Lab.ok(cli.run("edit", "-c", Long.toString(cl), "a.txt"));
        assertTrue(P4Panel.shelve(cli, cl, false).ok());
        P4Cli.Result again = P4Panel.shelve(cli, cl, false);
        assertTrue(again.text(), P4Panel.alreadyShelved(again));
        assertTrue(P4Panel.shelve(cli, cl, true).ok());
        assertFalse(cli.tagged("describe", "-S", "-s", Long.toString(cl)).records().isEmpty());
    }

    @Test
    public void diffAndAnnotate() throws Exception {
        P4Lab.ok(cli.run("edit", "a.txt"));
        lab.write("a.txt", "a2\n");
        P4Cli.Result diff = P4Panel.diff(cli, "//depot/a.txt", NEVER);
        assertTrue(diff.text(), diff.ok() && diff.text().contains("> a2"));
        P4Cli.Result annotate = P4Panel.annotate(cli, "//depot/icon%402x.png", NEVER);
        assertTrue(annotate.text(), annotate.ok() && annotate.text().contains("1: img"));
    }

    /** Sync + auto-merge leaves a real conflict opened; Accept theirs / yours resolve exactly one file each. */
    @Test
    public void conflictResolutionAsTheToolWindowRunsIt() throws Exception {
        Path bobWs = lab.base.resolve("ws bob");
        lab.createClient("bob", "bob_ws", bobWs);
        P4Cli bob = lab.cli("bob", "bob_ws", bobWs);
        P4Lab.ok(bob.run("sync"));
        P4Lab.ok(bob.run("edit", "a.txt", "icon%402x.png"));
        Files.writeString(bobWs.resolve("a.txt"), "bob\n");
        Files.writeString(bobWs.resolve("icon@2x.png"), "bob-img\n");
        P4Lab.ok(bob.run("submit", "-d", "bob"));

        assertNull(P4Ops.edit(cli, List.of(lab.local("a.txt"), lab.local("icon@2x.png")), NEVER));
        lab.write("a.txt", "alice\n");
        lab.write("icon@2x.png", "alice-img\n");
        P4Cli.Result[] r = P4Panel.syncAndAutoMerge(cli, NEVER);
        assertTrue(r[1].text(), r[1].text().contains("1 conflicting"));
        assertEquals(2, cli.tagged("resolve", "-n", "//...").records().size());
        assertTrue(P4Panel.resolve(cli, "-at", "//depot/a.txt").ok());
        assertTrue(P4Panel.resolve(cli, "-ay", "//depot/icon%402x.png").ok());
        assertEquals("bob\n", lab.read("a.txt"));
        assertEquals("alice-img\n", lab.read("icon@2x.png"));
        assertTrue(cli.tagged("resolve", "-n", "//...").records().isEmpty());
    }

    @Test
    public void streamsTreeAndStreamClient() throws Exception {
        P4Lab.ok(cli.runWithInput("Depot:\tstreams\nType:\tstream\nMap:\tstreams/...\nStreamDepth:\t//streams/1\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), "depot", "-i"));
        for (String[] s : new String[][]{{"main", "none", "mainline"}, {"dev", "//streams/main", "development"}}) {
            P4Lab.ok(cli.runWithInput(("Stream:\t//streams/" + s[0] + "\nOwner:\talice\nName:\t" + s[0] + "\nParent:\t" + s[1]
                    + "\nType:\t" + s[2] + "\nParentView:\tinherit\nPaths:\n\tshare ...\n").getBytes(java.nio.charset.StandardCharsets.UTF_8), "stream", "-i"));
        }
        var tree = P4StreamsPanel.buildTree(P4Data.streams(cli));
        assertEquals(1, tree.getChildCount());
        assertEquals("//streams/main", ((P4Data.StreamSpec) ((javax.swing.tree.DefaultMutableTreeNode) tree.getChildAt(0)).getUserObject()).name());
        assertEquals(1, tree.getChildAt(0).getChildCount());

        Path sws = lab.base.resolve("ws stream");
        Files.createDirectories(sws);
        P4Cli sc = lab.cli(P4Lab.USER, "s_ws", sws);
        StringBuilder spec = new StringBuilder();
        for (String line : P4Lab.ok(sc.run("client", "-S", "//streams/dev", "-o", "s_ws")).out().split("\n")) {
            spec.append(line.startsWith("Root:") ? "Root:\t" + sws : line.startsWith("Host:") ? "Host:" : line).append('\n');
        }
        P4Lab.ok(sc.runWithInput(spec.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), "client", "-i"));
        assertEquals(List.of("//streams/dev"), P4Data.clientStream(sc).items());
    }

    /** The rule is anchored to its directory, and names that are ignore-file syntax still work. */
    @Test
    public void ignoreRuleIsAnchoredAndTakesAnyName() throws Exception {
        String top = lab.write("sub dir/c.log", "x\n").toString();
        String deeper = lab.write("sub dir/deeper/c.log", "x\n").toString();
        assertTrue(P4Panel.writeIgnoreRule(cli, top).startsWith("now ignored"));
        assertTrue("a same-named file deeper down is not ignored", P4Data.ignored(cli, List.of(deeper)).isEmpty());
        for (String name : List.of("#notes.txt", "!keep.txt")) {
            String result = P4Panel.writeIgnoreRule(cli, lab.write(name, "x\n").toString());
            assertTrue(result, result.startsWith("now ignored"));
        }
    }

    /** A server that accepts the connection and never answers: the query is killed at its timeout, or on cancel.
     *  (Not a 1 ms timeout on a fast command: one that finishes before the first poll is rightly never killed.) */
    @Test
    public void queriesTimeOutInsteadOfHanging() throws Exception {
        try (java.net.ServerSocket silent = new java.net.ServerSocket(0)) {
            java.util.List<java.net.Socket> held = new java.util.concurrent.CopyOnWriteArrayList<>();
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) held.add(silent.accept());
                } catch (java.io.IOException closed) {
                    // the test is over
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            P4Cli hung = new P4Cli(lab.p4(), lab.ws.toString(), Map.of("P4PORT", "127.0.0.1:" + silent.getLocalPort(),
                    "P4USER", "x", "P4CLIENT", "x", "P4CONFIG", ".none"));
            long t0 = System.nanoTime();
            P4Cli.Result r = hung.run(java.time.Duration.ofSeconds(2), NEVER, "info");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertFalse(r.ok());
            assertTrue(r.err(), r.err().contains("timed out"));
            assertTrue("killed at the timeout, not later: " + ms + " ms", ms < 5_000);

            long t1 = System.nanoTime();
            P4Cli.Result c = hung.run(null, () -> System.nanoTime() - t1 > 300_000_000L, "info");
            assertEquals("cancelled", c.err());
        }
    }
}
