package kaflow.groups;

import java.util.Objects;

/** Exact, lossless UTF-8 identity. Never a filesystem path. */
public record GroupId(String value) {
    public GroupId { validate(value, 255, false); }
    public static int validate(String value, int limit, boolean emptyAllowed) {
        Objects.requireNonNull(value);
        if ((!emptyAllowed && value.isBlank()) || value.length() > limit) throw new IllegalArgumentException("invalid identifier");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new IllegalArgumentException("invalid UTF-8 identity");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("invalid UTF-8 identity");
        }
        int bytes = utf8Length(value);
        if (bytes > limit) throw new IllegalArgumentException("identifier byte limit");
        return bytes;
    }
    public int byteLength() { return utf8Length(value); }
    private static int utf8Length(String value) {
        int bytes = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) bytes++;
            else if (c < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(c)) { bytes += 4; i++; }
            else bytes += 3;
        }
        return bytes;
    }
}
