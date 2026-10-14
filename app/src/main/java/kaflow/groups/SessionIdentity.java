package kaflow.groups;

import java.util.Objects;
import java.util.UUID;

/** Credentials are immutable and deliberately redacted from diagnostic rendering. */
public record SessionIdentity(GroupId group, String incarnation, String session) {
    public SessionIdentity {
        Objects.requireNonNull(group);
        canonical(incarnation); canonical(session);
    }
    private static void canonical(String value) {
        if (value == null || value.length() != 36 || !UUID.fromString(value).toString().equals(value)) {
            throw new IllegalArgumentException("invalid session identity");
        }
    }
    @Override public String toString() { return "SessionIdentity[redacted]"; }
}
