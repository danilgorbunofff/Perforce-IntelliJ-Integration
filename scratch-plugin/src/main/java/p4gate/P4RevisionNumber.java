package p4gate;

import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import org.jetbrains.annotations.NotNull;

/**
 * A p4 revision of one file: the changelist it was last submitted in.
 * 0 means "no submitted revision is known" and prints as {@code have} in a p4 revision spec, e.g. {@code //depot/a.txt#have}.
 */
final class P4RevisionNumber implements VcsRevisionNumber {
    static final P4RevisionNumber HAVE = new P4RevisionNumber(0);

    private final long change;

    P4RevisionNumber(long change) { this.change = change; }

    long change() { return change; }

    @Override
    public @NotNull String asString() { return change <= 0 ? "have" : String.valueOf(change); }

    @Override
    public int compareTo(@NotNull VcsRevisionNumber other) {
        if (other instanceof P4RevisionNumber p) {
            // not Long.compare: VcsRevisionNumber declares a nested type named Long, which shadows java.lang.Long here
            return change < p.change ? -1 : change > p.change ? 1 : 0;
        }
        return asString().compareTo(other.asString());
    }

    @Override
    public boolean equals(Object o) { return o instanceof P4RevisionNumber p && p.change == change; }

    @Override
    public int hashCode() { return (int) (change ^ (change >>> 32)); }

    @Override
    public String toString() { return asString(); }
}
