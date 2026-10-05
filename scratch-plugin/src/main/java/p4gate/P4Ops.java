package p4gate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The p4 side of the IDE's VCS actions, free of platform types so the exact same code runs in the IDE and in the
 * live tests against a real server. Every method costs a constant number of p4 spawns, whatever the file count,
 * and returns p4's own error text (null = success).
 */
final class P4Ops {
    private static final Pattern CHANGE_CREATED = Pattern.compile("Change (\\d+) created");

    private P4Ops() {
    }

    /** `p4 edit`: opens the files for edit (in the default changelist) and makes them writable. */
    static String edit(P4Cli cli, List<String> localPaths, BooleanSupplier cancelled) {
        return outcome(cli.taggedWithArgs(P4Cli.QUERY_TIMEOUT, cancelled, P4Args.escapeAll(localPaths), "edit"), localPaths.size());
    }

    /** `p4 add -f`: -f takes names literally, so files called e.g. {@code icon@2x.png} can be added. */
    static String add(P4Cli cli, List<String> localPaths, BooleanSupplier cancelled) {
        return outcome(cli.taggedWithArgs(null, cancelled, localPaths, "add", "-f"), localPaths.size());
    }

    /**
     * `p4 add -f` for a file the user asked to add: unlike {@link #add}, a warning means the file was NOT opened for
     * add ("can't add (already opened for edit)", "add of existing file", "ignored file can't be added"), so it is
     * reported. p4 exits 0 for the first two; counting the record it still prints made the tool window say OK.
     */
    static String addStrict(P4Cli cli, List<String> localPaths, BooleanSupplier cancelled) {
        P4Cli.Tagged t = cli.taggedWithArgs(null, cancelled, localPaths, "add", "-f");
        if (t.error() != null) return t.error();
        // p4 reports these at info level (0/1) or warning level (2), depending on the case, so look at every message
        List<String> refused = new ArrayList<>();
        for (P4Cli.Message m : t.messages()) {
            String w = m.text().strip();
            if (w.contains("can't add") || w.contains("add of existing file") || w.contains("ignored file")) refused.add(w);
        }
        return refused.isEmpty() ? null : String.join("\n", refused);
    }

    /** `p4 reconcile -d -f`: opens files that are gone from disk for delete (`p4 delete` needs the file present). */
    static String deleteMissing(P4Cli cli, List<String> localPaths, BooleanSupplier cancelled) {
        return outcome(cli.taggedWithArgs(null, cancelled, localPaths, "reconcile", "-d", "-f"), localPaths.size());
    }

    /**
     * `p4 revert`: drops the open state and restores the depot (#have) content — including a file that was
     * deleted from disk. Files opened for add stay on disk, unopened.
     */
    static String revert(P4Cli cli, List<String> localPaths, BooleanSupplier cancelled) {
        return outcome(cli.taggedWithArgs(null, cancelled, P4Args.escapeAll(localPaths), "revert"), localPaths.size());
    }

    /** `p4 add -f` of files the user just created in the IDE; a file p4 ignores (P4IGNORE) is skipped, not an error. */
    static String addNew(P4Cli cli, List<String> localPaths, BooleanSupplier cancelled) {
        P4Cli.Tagged t = cli.taggedWithArgs(null, cancelled, localPaths, "add", "-f");
        if (t.error() != null) return t.error();
        List<String> real = new ArrayList<>();
        for (String w : t.warnings()) {
            if (!w.contains("ignored file can't be added")) real.add(w);
        }
        return real.isEmpty() ? null : String.join("\n", real);
    }

    /**
     * Files the user deleted in the IDE (already gone from disk): a file only opened for add is simply reverted
     * (nothing to delete in the depot); everything else is opened for delete with `reconcile -d`, which also
     * turns an opened-for-edit file into a delete (verified on r25.2). Files p4 does not know are ignored.
     */
    static String deleted(P4Cli cli, List<String> localPaths, BooleanSupplier cancelled) {
        P4Data.Listing<P4OpenFile> opened = P4OpenFile.among(cli, cancelled, localPaths);
        if (opened.error() != null) return opened.error();
        Map<String, P4OpenFile> byLocal = P4OpenFile.byLocal(opened.items());
        List<String> addsToDrop = new ArrayList<>();
        List<String> movesToUndo = new ArrayList<>();
        List<String> moveSources = new ArrayList<>();
        List<String> toDelete = new ArrayList<>();
        for (String path : localPaths) {
            P4OpenFile f = byLocal.get(P4OpenFile.normalize(path));
            if (f != null && (f.action().equals("add") || f.action().equals("branch") || f.action().equals("import"))) {
                addsToDrop.add(f.depotFile());
            } else if (f != null && f.action().equals("move/add") && !f.movedFile().isBlank()) {
                // reconcile -d leaves a move/add whose target is gone untouched (verified on r25.2): undo the move,
                // which brings the source back, then delete the source — renamed-then-deleted is a delete
                movesToUndo.add(f.depotFile());
                moveSources.add(f.movedFile());
            } else {
                toDelete.add(path);
            }
        }
        String error = addsToDrop.isEmpty() ? null : outcome(cli.taggedWithArgs(null, cancelled, addsToDrop, "revert"), addsToDrop.size());
        if (error != null) return error;
        if (!movesToUndo.isEmpty()) {
            error = outcome(cli.taggedWithArgs(null, cancelled, movesToUndo, "revert"), movesToUndo.size());
            if (error != null) return error;
            error = outcome(cli.taggedWithArgs(null, cancelled, moveSources, "delete"), moveSources.size());
            if (error != null) return error;
        }
        if (toDelete.isEmpty()) return null;
        P4Cli.Tagged t = cli.taggedWithArgs(null, cancelled, toDelete, "reconcile", "-d", "-f");
        return t.error(); // "no file(s) to reconcile" for a file p4 never knew is a warning: nothing to do
    }

    /**
     * Files the user renamed or moved in the IDE (already moved on disk), as (from, to) local path pairs: recorded
     * as Perforce moves without touching the disk — `p4 edit -k` for sources not yet open, then one
     * `p4 -b 2 -x <pairs> move -k` for all pairs (verified on r25.2). Sources p4 does not track are skipped.
     */
    static String moved(P4Cli cli, List<String[]> pairs, BooleanSupplier cancelled) {
        if (pairs.isEmpty()) return null;
        List<String> from = new ArrayList<>(pairs.size());
        for (String[] pair : pairs) from.add(pair[0]);
        P4Cli.Tagged st = cli.taggedWithArgs(P4Cli.QUERY_TIMEOUT, cancelled, P4Args.escapeAll(from), "fstat", "-T",
                "depotFile,clientFile,action,headAction");
        if (st.error() != null) return st.error();
        Map<String, Map<String, String>> byLocal = new java.util.HashMap<>();
        for (Map<String, String> r : st.records()) {
            String local = r.get("clientFile");
            if (local != null) byLocal.put(P4OpenFile.normalize(local), r);
        }
        List<String> toOpen = new ArrayList<>();
        List<String> moveArgs = new ArrayList<>();
        for (String[] pair : pairs) {
            Map<String, String> r = byLocal.get(P4OpenFile.normalize(pair[0]));
            if (r == null) continue; // not a Perforce file: the IDE rename is all there is to do
            String head = r.getOrDefault("headAction", "");
            boolean open = r.containsKey("action");
            if (!open && (head.isEmpty() || head.contains("delete"))) continue; // not in the depot at head
            if (!open) toOpen.add(r.get("depotFile"));
            moveArgs.add(P4Args.escape(pair[0]));
            moveArgs.add(P4Args.escape(pair[1]));
        }
        if (moveArgs.isEmpty()) return null;
        if (!toOpen.isEmpty()) {
            String error = outcome(cli.taggedWithArgs(null, cancelled, toOpen, "edit", "-k"), toOpen.size());
            if (error != null) return error;
        }
        return outcome(cli.taggedWithArgs(null, cancelled, moveArgs, 2, "move", "-k"), moveArgs.size() / 2);
    }

    /** What a commit did: the submitted change, or the pending change the files were left in, and the error. */
    record SubmitResult(long submitted, long pending, String error) {
        boolean ok() { return error == null; }
    }

    /**
     * Submits exactly the given files with the given description. p4 can only submit a whole changelist
     * ({@code submit -c N} takes no file arguments — verified on r25.2), so the files are moved into a NEW
     * changelist that carries the commit message, and that changelist is submitted. Source changelists left empty
     * by the move are deleted (p4 refuses to delete one that still has open or shelved files).
     * Nothing is changed when a file is not open or the description is empty.
     */
    static SubmitResult submit(P4Cli cli, List<String> localPaths, String description, BooleanSupplier cancelled) {
        if (description == null || description.isBlank()) {
            return new SubmitResult(0, 0, "A Perforce submit needs a description: enter a commit message.");
        }
        P4Data.Listing<P4OpenFile> opened = P4OpenFile.among(cli, cancelled, localPaths);
        if (opened.error() != null) return new SubmitResult(0, 0, opened.error());
        Map<String, P4OpenFile> byLocal = P4OpenFile.byLocal(opened.items());
        List<String> notOpened = new ArrayList<>();
        Set<String> depotPaths = new LinkedHashSet<>();
        Set<Long> sources = new LinkedHashSet<>();
        for (String path : localPaths) {
            P4OpenFile f = byLocal.get(P4OpenFile.normalize(path));
            if (f == null) {
                notOpened.add(path);
            } else {
                depotPaths.add(f.depotFile());
                if (f.change() > 0) sources.add(f.change());
            }
        }
        if (!notOpened.isEmpty()) {
            return new SubmitResult(0, 0, "Not open in Perforce, so nothing was submitted: " + String.join(", ", notOpened));
        }
        if (depotPaths.isEmpty()) return new SubmitResult(0, 0, "Nothing to submit.");

        // jobs the source changelists fix: the new changelist must fix them too, or the submit silently leaves
        // them open (and fails where a trigger requires a job)
        Map<Long, List<String>> jobsBySource = new java.util.LinkedHashMap<>();
        Set<String> jobs = new LinkedHashSet<>();
        for (long source : sources) {
            P4Cli.Tagged fixes = cli.tagged("fixes", "-c", Long.toString(source));
            if (fixes.error() != null) return new SubmitResult(0, 0, fixes.error());
            List<String> js = new ArrayList<>();
            for (Map<String, String> r : fixes.records()) {
                String job = r.get("Job");
                if (job != null && !job.isBlank()) js.add(job);
            }
            jobsBySource.put(source, js);
            jobs.addAll(js);
        }

        P4Cli.Result created = cli.runWithInput(changeSpec(description).getBytes(StandardCharsets.UTF_8), "change", "-i");
        long change = createdChange(created.out());
        if (!created.ok() || change <= 0) {
            return new SubmitResult(0, 0, "Could not create a changelist: " + created.text().strip());
        }
        String reopened = outcome(cli.taggedWithArgs(null, cancelled, List.copyOf(depotPaths), "reopen", "-c", Long.toString(change)), depotPaths.size());
        if (reopened != null) {
            return new SubmitResult(0, change, reopened + "\nSome files may already be in pending change " + change + ".");
        }
        if (!jobs.isEmpty()) {
            String fixed = outcome(cli.taggedWithArgs(null, cancelled, List.copyOf(jobs), "fix", "-c", Long.toString(change)), jobs.size());
            if (fixed != null) {
                return new SubmitResult(0, change, fixed + "\nThe files are in pending change " + change
                        + "; its jobs could not be attached, so it was not submitted.");
            }
        }
        // not cancellable: killing p4 half-way through a submit leaves a locked, half-transferred changelist
        P4Cli.Tagged submit = cli.tagged(null, () -> false, "submit", "-c", Long.toString(change));
        if (submit.error() != null) {
            return new SubmitResult(0, change, submit.error() + "\nThe files are in pending change " + change
                    + " with your description; fix the problem, then submit that change.");
        }
        long submitted = change;
        for (Map<String, String> r : submit.records()) {
            if (r.containsKey("submittedChange")) submitted = P4OpenFile.changeId(r.get("submittedChange"));
        }
        for (long source : sources) {
            if (!cli.tagged("opened", "-c", Long.toString(source)).records().isEmpty()) continue; // still has work
            List<String> js = jobsBySource.getOrDefault(source, List.of());
            if (!js.isEmpty()) cli.taggedWithArgs(null, () -> false, js, "fix", "-d", "-c", Long.toString(source));
            cli.run("change", "-d", Long.toString(source)); // fails if it still has shelved files; that is fine
        }
        return new SubmitResult(submitted, 0, null);
    }

    /** A new-changelist spec. Every description line is tab-indented, or p4 reads it as the next field. */
    static String changeSpec(String description) {
        StringBuilder b = new StringBuilder("Change:\tnew\n\nDescription:\n");
        for (String line : description.strip().replace("\r\n", "\n").split("\n", -1)) {
            b.append('\t').append(line).append('\n');
        }
        return b.toString();
    }

    /** "Change 12 created." -> 12, else 0. (`change -i` runs without -Mj: with it, p4 expects JSON input.) */
    static long createdChange(String output) {
        Matcher m = CHANGE_CREATED.matcher(output);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    /**
     * Error text of an operation over {@code expected} files: p4's error, or — when p4 acted on fewer files than it
     * was given — its warnings (e.g. "file(s) not on client"), since a silently skipped file is still a failure.
     * A warning beside full success is not a failure (`edit` warns "also opened by bob" and still opens the file).
     * Reverting a move reports both halves, so a revert can reach the count with a file skipped as "not opened";
     * that file is already in the state a revert asks for.
     */
    static String outcome(P4Cli.Tagged t, int expected) {
        if (t.error() != null) return t.error();
        List<String> warnings = t.warnings();
        if (!warnings.isEmpty() && t.records().size() < expected) return String.join("\n", warnings);
        return null;
    }
}
