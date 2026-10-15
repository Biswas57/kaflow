package kaflow.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import kaflow.broker.TopicPartition;
import kaflow.groups.SessionIdentity;

/** Coordinator-owned removable timer slots and indexes. No retained tombstones or wake history. */
final class FetchWaiters<T> {
    record Entry<T>(long id, TopicPartition partition, SessionIdentity session, T value) { }
    private final LinkedHashMap<Long, Entry<T>> timers = new LinkedHashMap<>();
    private final Map<TopicPartition, LinkedHashMap<Long, Entry<T>>> partitions = new LinkedHashMap<>();
    private final Map<SessionIdentity, LinkedHashMap<Long, Entry<T>>> sessions = new LinkedHashMap<>();
    private final LinkedHashMap<TopicPartition, Boolean> notifications = new LinkedHashMap<>();

    void add(long id, TopicPartition partition, SessionIdentity session, T value) {
        var entry = new Entry<>(id, partition, session, value);
        if (timers.putIfAbsent(id, entry) != null) throw new IllegalStateException("duplicate waiter");
        partitions.computeIfAbsent(partition, ignored -> new LinkedHashMap<>()).put(id, entry);
        sessions.computeIfAbsent(session, ignored -> new LinkedHashMap<>()).put(id, entry);
    }
    void remove(long id) {
        var entry = timers.remove(id);
        if (entry == null) return;
        remove(partitions, entry.partition(), id); remove(sessions, entry.session(), id);
        if (!partitions.containsKey(entry.partition())) notifications.remove(entry.partition());
    }
    private static <K, T> void remove(Map<K, LinkedHashMap<Long, Entry<T>>> index, K key, long id) {
        var values = index.get(key); values.remove(id); if (values.isEmpty()) index.remove(key);
    }
    Entry<T> maintenance() {
        if (timers.isEmpty()) return null;
        var iterator = timers.entrySet().iterator(); var first = iterator.next();
        var entry = first.getValue(); iterator.remove(); timers.put(entry.id(), entry);
        return entry;
    }
    void published(TopicPartition partition) {
        if (partitions.containsKey(partition)) notifications.putIfAbsent(partition, Boolean.TRUE);
    }
    Entry<T> notified() {
        if (notifications.isEmpty()) return null;
        var iterator = notifications.keySet().iterator(); var partition = iterator.next(); iterator.remove();
        return first(partition);
    }
    Entry<T> first(TopicPartition partition) {
        var values = partitions.get(partition);
        return values == null ? null : values.values().iterator().next();
    }
    int size() { return timers.size(); }
    int partitions() { return partitions.size(); }
    int sessions() { return sessions.size(); }
    int notifications() { return notifications.size(); }
}
