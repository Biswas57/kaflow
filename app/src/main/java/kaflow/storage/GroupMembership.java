package kaflow.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kaflow.broker.TopicId;
import kaflow.broker.TopicPartition;
import kaflow.groups.*;
import static kaflow.storage.DurableException.Code.*;

/** Coordinator-only state. No I/O, workers, scheduled tasks or retained heartbeat history. */
final class GroupMembership {
    private final GroupOptions options;
    private final String incarnation = UUID.randomUUID().toString();
    private final Map<GroupId, Group> groups = new LinkedHashMap<>();
    private final List<Group> sweep = new ArrayList<>();
    private int members, cursor;

    GroupMembership(GroupOptions options) { this.options = options; }

    void define(GroupId id, TopicId topic, int partitions) {
        Group existing = groups.get(id);
        if (existing != null) {
            if (!existing.topic.equals(topic)) throw new IllegalStateException("durable group binding invariant");
            return;
        }
        Group group = new Group(id, topic, partitions); groups.put(id, group); sweep.add(group);
    }
    TopicId binding(GroupId id) { Group group = groups.get(id); return group == null ? null : group.topic; }
    int groupCount() { return groups.size(); }
    int memberCount() { return members; }

    void checkJoin(JoinCommand join, long now) {
        Group group = groups.get(join.group());
        if (group != null) {
            expire(group, now);
            if (!group.topic.equals(join.topic())) throw new DurableException(BINDING_CONFLICT);
            if (group.exhausted || group.generation == Long.MAX_VALUE || group.live.size() >= options.maxMembersPerGroup()) throw new DurableException(RESOURCE_EXHAUSTED);
        } else if (groups.size() >= options.maxGroups()) throw new DurableException(RESOURCE_EXHAUSTED);
        if (members >= options.maxMembers()) throw new DurableException(RESOURCE_EXHAUSTED);
    }

    MembershipView join(JoinCommand join, long now) {
        checkJoin(join, now); Group group = required(join.group());
        String token;
        do { token = UUID.randomUUID().toString(); } while (group.live.containsKey(token));
        Member member = new Member(token, now);
        group.generation = increment(group.generation); group.live.put(token, member); members++;
        return view(group, member);
    }

    MembershipView heartbeat(SessionIdentity identity, long now) {
        Group group = required(identity.group()); expire(group, now);
        Member member = live(group, identity);
        member.renewed = now;
        return view(group, member);
    }

    void leave(SessionIdentity identity, long now) {
        Group group = required(identity.group()); expire(group, now); live(group, identity);
        long next = increment(group.generation); // Reject overflow without changing membership.
        group.live.remove(identity.session()); members--; group.generation = next;
    }

    void validate(SessionIdentity identity, long generation, TopicPartition partition, long now) {
        Group group = required(identity.group()); expire(group, now); Member member = live(group, identity);
        if (generation != group.generation || !partition.topic().equals(group.topic)
                || partition.partition() >= group.partitions || !assigned(group, member).contains(partition)) throw new DurableException(FENCED);
    }

    void expireBatch(long now) {
        for (int i = 0, count = Math.min(8, sweep.size()); i < count; i++) {
            if (cursor == sweep.size()) cursor = 0;
            expire(sweep.get(cursor++), now);
        }
    }

    private void expire(Group group, long now) {
        List<String> expired = new ArrayList<>();
        for (Member member : group.live.values()) if (now - member.renewed >= options.lease().toNanos()) expired.add(member.token);
        if (expired.isEmpty()) return;
        if (group.generation == Long.MAX_VALUE) {
            group.exhausted = true; members -= group.live.size(); group.live.clear(); return;
        }
        for (String token : expired) group.live.remove(token);
        members -= expired.size(); group.generation++;
    }

    void invalidate() {
        for (Group group : sweep) group.live.clear();
        members = 0;
    }

    static long increment(long value) {
        if (value == Long.MAX_VALUE) throw new DurableException(RESOURCE_EXHAUSTED);
        return value + 1;
    }

    private Group required(GroupId id) {
        Group group = groups.get(id);
        if (group == null) throw new DurableException(NOT_FOUND);
        return group;
    }
    private Member live(Group group, SessionIdentity identity) {
        Member member = group.live.get(identity.session());
        if (!incarnation.equals(identity.incarnation()) || member == null || group.exhausted) throw new DurableException(FENCED);
        return member;
    }
    private MembershipView view(Group group, Member member) {
        return new MembershipView(new SessionIdentity(group.id, incarnation, member.token), group.generation,
                options.lease().toMillis(), assigned(group, member));
    }
    private List<TopicPartition> assigned(Group group, Member member) {
        int rank = 0;
        for (Member other : group.live.values()) { if (other == member) break; rank++; }
        List<TopicPartition> assignments = new ArrayList<>();
        for (int p = rank; p < group.partitions; p += group.live.size()) assignments.add(new TopicPartition(group.topic, p));
        return List.copyOf(assignments);
    }
    private static final class Group {
        final GroupId id; final TopicId topic; final int partitions;
        final Map<String, Member> live = new LinkedHashMap<>();
        long generation; boolean exhausted;
        Group(GroupId id, TopicId topic, int partitions) { this.id = id; this.topic = topic; this.partitions = partitions; }
    }
    private static final class Member {
        final String token; long renewed;
        Member(String token, long renewed) { this.token = token; this.renewed = renewed; }
    }
}
