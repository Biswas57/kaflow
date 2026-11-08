package kaflow.clients;

import java.util.*;

final class Arguments {
    private final Map<String, String> values = new HashMap<>();
    Arguments(String[] args, int start, Set<String> allowed) {
        for (int i = start; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 == args.length || !allowed.contains(args[i].substring(2)))
                throw new IllegalArgumentException("unknown option or missing value");
            if (values.putIfAbsent(args[i].substring(2), args[i + 1]) != null) throw new IllegalArgumentException("duplicate option");
        }
    }
    String text(String key, String fallback) { return values.getOrDefault(key, fallback); }
    boolean has(String key) { return values.containsKey(key); }
    long number(String key, long fallback, long min, long max) {
        long value = Long.parseLong(text(key, Long.toString(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException("option out of range: " + key);
        return value;
    }
    boolean flag(String key) {
        String value = text(key, "false");
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("boolean option: " + key);
        return Boolean.parseBoolean(value);
    }
}
