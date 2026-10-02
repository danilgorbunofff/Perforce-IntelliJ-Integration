package p4gate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A depot path and the local path of the client view entry that maps it. */
final class P4ClientFile {

    final String depotPath;
    final String clientPath;

    P4ClientFile(String depotPath, String clientPath) {
        this.depotPath = depotPath;
        this.clientPath = clientPath;
    }

    /** Records of {@code p4 -ztag fstat -T depotFile,clientFile}: the client view mapping, straight from p4. */
    static List<P4ClientFile> parse(List<Map<String, String>> records) {
        List<P4ClientFile> files = new ArrayList<>(records.size());
        for (Map<String, String> record : records) {
            String depot = record.get("depotFile");
            String client = record.get("clientFile");
            if (depot != null && !depot.isBlank() && client != null && !client.isBlank()) {
                files.add(new P4ClientFile(depot, client));
            }
        }
        return files;
    }

    /** Depot path to local path, so an opened file can be reported at the path the IDE shows. */
    static Map<String, String> byDepot(List<P4ClientFile> files) {
        Map<String, String> paths = new LinkedHashMap<>();
        for (P4ClientFile file : files) {
            paths.put(file.depotPath, file.clientPath);
        }
        return paths;
    }

    /** Depot paths of the given local paths, in the order the local paths were given. */
    static List<String> depotPathsOf(List<P4ClientFile> files, List<String> localPaths) {
        Map<String, String> byLocal = new LinkedHashMap<>();
        for (P4ClientFile file : files) {
            byLocal.put(normalize(file.clientPath), file.depotPath);
        }
        List<String> depotPaths = new ArrayList<>(localPaths.size());
        for (String local : localPaths) {
            String depot = byLocal.get(normalize(local));
            if (depot != null && !depotPaths.contains(depot)) depotPaths.add(depot);
        }
        return depotPaths;
    }

    /** p4 prints local paths with backslashes, the VFS with slashes: compare them the way Windows does. */
    private static String normalize(String path) {
        return path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
    }
}
