package p4gate;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * A file opened in the current client, with the local path p4 maps it to: one record of
 * {@code p4 fstat -Ro -T <FIELDS>}. This is the only source the VCS integration reads, so a file the user never
 * opened can never show up as changed, and no directory is ever scanned.
 */
record P4OpenFile(String depotFile, String localPath, String action, long change, String type,
                  String movedFile, boolean unresolved, long haveRev) {

    static final String FIELDS = "depotFile,clientFile,action,change,type,movedFile,unresolved,haveRev";

    /** Every opened file of the current client: `p4 opened` (cheap, client-scoped), then one fstat of exactly those. */
    static P4Data.Listing<P4OpenFile> all(P4Cli cli, BooleanSupplier cancelled) {
        P4Cli.Tagged opened = cli.tagged(P4Cli.QUERY_TIMEOUT, cancelled, "opened");
        if (opened.error() != null) return P4Data.Listing.failed(opened.error());
        Set<String> depotPaths = new LinkedHashSet<>(); // a set: a list's contains() is quadratic on big changelists
        for (Map<String, String> r : opened.records()) {
            String depot = r.get("depotFile");
            if (depot != null && !depot.isBlank()) depotPaths.add(depot);
        }
        return fstat(cli, cancelled, List.copyOf(depotPaths)); // depot paths are printed already escaped
    }

    /** The opened files among the given LOCAL paths (anything not opened is simply absent from the result). */
    static P4Data.Listing<P4OpenFile> among(P4Cli cli, BooleanSupplier cancelled, List<String> localPaths) {
        return fstat(cli, cancelled, P4Args.escapeAll(localPaths));
    }

    private static P4Data.Listing<P4OpenFile> fstat(P4Cli cli, BooleanSupplier cancelled, List<String> args) {
        if (args.isEmpty()) return new P4Data.Listing<>(List.of(), null);
        P4Cli.Tagged t = cli.taggedWithArgs(P4Cli.QUERY_TIMEOUT, cancelled, args, "fstat", "-Ro", "-T", FIELDS);
        if (t.error() != null) return P4Data.Listing.failed(t.error());
        return new P4Data.Listing<>(parse(t.records()), null);
    }

    /** fstat records -> opened files; records without a depot path, local path or action are not opened files. */
    static List<P4OpenFile> parse(List<Map<String, String>> records) {
        Map<String, P4OpenFile> files = new LinkedHashMap<>();
        for (Map<String, String> r : records) {
            String depot = r.get("depotFile");
            String local = r.get("clientFile");
            String action = r.get("action");
            if (blank(depot) || blank(local) || blank(action)) continue;
            files.put(depot, new P4OpenFile(depot, local, action.strip(), changeId(r.get("change")), r.getOrDefault("type", ""),
                    r.getOrDefault("movedFile", ""), r.containsKey("unresolved"), number(r.get("haveRev"))));
        }
        return List.copyOf(files.values());
    }

    /** Local path (normalized) -> opened file. */
    static Map<String, P4OpenFile> byLocal(List<P4OpenFile> files) {
        Map<String, P4OpenFile> out = new LinkedHashMap<>();
        for (P4OpenFile f : files) out.put(normalize(f.localPath()), f);
        return out;
    }

    /** p4 prints local paths with backslashes, the VFS with slashes; Windows compares case-insensitively. */
    static String normalize(String path) {
        String p = path.replace('\\', '/');
        return File.separatorChar == '\\' ? p.toLowerCase(Locale.ROOT) : p;
    }

    /** "default" (or anything unparsable) is the default changelist, id 0. */
    static long changeId(String change) {
        if (change == null) return 0;
        try {
            return Long.parseLong(change.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Binary content (shown as binary, never decoded): the binary base types, plus the Mac resource types. */
    static boolean isBinaryType(String type) {
        if (type == null) return false;
        String base = type.strip().toLowerCase(Locale.ROOT);
        int plus = base.indexOf('+');
        if (plus >= 0) base = base.substring(0, plus);
        return base.contains("binary") || base.contains("tempobj") || base.equals("apple") || base.equals("resource");
    }

    boolean binary() {
        return isBinaryType(type);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static long number(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
