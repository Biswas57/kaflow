package kaflow.groups;

import java.time.Duration;
import java.util.Objects;

/** Independent control, membership and durable metadata budgets. */
public record GroupOptions(int maxGroups, int maxMembersPerGroup, int maxMembers,
                           int maxPendingJoins, int maxControls, long maxControlBytes,
                           int maxCatalogBytes, Duration lease) {
    public GroupOptions {
        Objects.requireNonNull(lease);
        if (maxGroups < 1 || maxGroups > 256 || maxMembersPerGroup < 1 || maxMembersPerGroup > 64
                || maxMembers < 1 || maxMembers > 1024 || maxPendingJoins < 1 || maxPendingJoins > 128
                || maxControls < 1 || maxControls > 512 || maxControlBytes < 0 || maxControlBytes > 8L*1024*1024
                || maxCatalogBytes < 48 || maxCatalogBytes > 128*1024
                || lease.compareTo(Duration.ofMillis(1)) < 0 || lease.compareTo(Duration.ofMinutes(5)) > 0
                || lease.toNanos() % 1_000_000 != 0) throw new IllegalArgumentException("group options outside bounds");
    }
    public static GroupOptions defaults() {
        return new GroupOptions(128, 32, 256, 32, 128, 2L*1024*1024, 128*1024, Duration.ofSeconds(30));
    }
}
