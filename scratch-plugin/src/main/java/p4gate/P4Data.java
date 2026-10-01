package p4gate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses plain-text p4 CLI output into small models. */
public final class P4Data {
    private static final Pattern CHANGE_LINE =
            Pattern.compile("^Change (\\d+) on \\S+ by (\\S+)@\\S+.*?'(.*)'$");
    private static final Pattern OPENED_LINE =
            Pattern.compile("^(.+)#\\d+ - (\\w+) (?:change (\\d+)|default change)( .*)?$");

    public record Change(long id, String user, String desc, List<String> files) {
        @Override
        public String toString() {
            return "change " + id + " — '" + desc + "' (" + files.size() + " files) by " + user;
        }
    }

    private P4Data() { }

    /** Pending changelists with the files opened in each (default first). */
    public static List<Change> pendingChanges() {
        Map<Long, String> headers = new LinkedHashMap<>();
        P4Cli.Result r = P4Cli.run("changes", "-s", "pending");
        if (!r.ok()) {
            return List.of();
        }
        for (String line : r.out().split("\n")) {
            Matcher m = CHANGE_LINE.matcher(line.trim());
            if (m.matches()) {
                headers.put(Long.parseLong(m.group(1)), m.group(3));
            }
        }
        List<Change> result = new ArrayList<>();
        result.add(new Change(0, "", "default", openedFiles("default")));
        for (Map.Entry<Long, String> e : headers.entrySet()) {
            result.add(new Change(e.getKey(), "", e.getValue(), openedFiles(String.valueOf(e.getKey()))));
        }
        return result;
    }

    private static List<String> openedFiles(String cl) {
        List<String> files = new ArrayList<>();
        P4Cli.Result r = P4Cli.run("opened", "-c", cl);
        if (!r.ok()) {
            return files;
        }
        for (String line : r.out().split("\n")) {
            Matcher m = OPENED_LINE.matcher(line.trim());
            if (m.matches()) {
                String clNo = m.group(3) == null ? "default" : m.group(3);
                files.add(m.group(1) + " — " + m.group(2) + " @" + clNo);
            }
        }
        return files;
    }

    public static String info() {
        P4Cli.Result r = P4Cli.run("info");
        return r.ok() ? r.out() : r.text();
    }

    /** CLI test driver: p4gate.P4Data against a running p4d. */
    public static void main(String[] args) {
        List<Change> cs = pendingChanges();
        for (Change c : cs) {
            System.out.println(c);
            for (String f : c.files()) {
                System.out.println("    " + f);
            }
        }
        System.out.println("--- p4 info ---");
        System.out.println(info());
    }
}
