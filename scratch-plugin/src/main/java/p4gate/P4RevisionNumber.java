package p4gate;

import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import org.jetbrains.annotations.NotNull;

/**
 * A p4 revision of one file: its revision number ({@code #n}), the haveRev the workspace was synced to.
 * 0 means "not known" and prints as {@code have}, matching the spec that is printed ({@code //depot/a.txt#have}).
 */
final class P4RevisionNumber implements VcsRevisionNumber {
    static final P4RevisionNumber HAVE = new P4RevisionNumber(0);

    private final long rev;

    P4RevisionNumber(long rev) { this.rev = rev; }

    long rev() { return rev; }

    @Override
    public @NotNull String asString() { return rev <= 0 ? "have" : String.valueOf(rev); }

    @Override
    public int compareTo(@NotNull VcsRevisionNumber other) {
        if (other instanceof P4RevisionNumber p) {
            // not Long.compare: VcsRevisionNumber declares a nested type named Long, which shadows java.lang.Long here
            return rev < p.rev ? -1 : rev > p.rev ? 1 : 0;
        }
        return asString().compareTo(other.asString());
    }

    @Override
    public boolean equals(Object o) { return o instanceof P4RevisionNumber p && p.rev == rev; }

    @Override
    public int hashCode() { return (int) (rev ^ (rev >>> 32)); }

    @Override
    public String toString() { return asString(); }
}
