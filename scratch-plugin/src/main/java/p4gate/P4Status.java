package p4gate;

import com.intellij.openapi.vcs.FileStatus;

import java.util.Locale;

/** Maps a p4 "action" word to the platform's FileStatus. Pure: no p4 call, no project, unit-tested in P4ParseTest. */
final class P4Status {
    private P4Status() { }

    /**
     * @param action       the {@code action} field of {@code p4 opened -ztag -Mj}
     * @param existsOnDisk whether the file is present in the workspace right now
     */
    static FileStatus of(String action, boolean existsOnDisk) {
        FileStatus status = switch (action == null ? "" : action.strip().toLowerCase(Locale.ROOT)) {
            case "add", "branch", "import", "move/add", "archive" -> FileStatus.ADDED;
            case "delete", "move/delete", "purge" -> FileStatus.DELETED;
            case "integrate", "merge", "resolve" -> FileStatus.MERGE;
            default -> FileStatus.MODIFIED;
        };
        // An opened file that is gone from the workspace is deleted-in-workspace, not modified: the platform shows
        // these in a separate "deleted" group and offers a different rollback action for them.
        return status == FileStatus.MODIFIED && !existsOnDisk ? FileStatus.DELETED_FROM_FS : status;
    }
}
