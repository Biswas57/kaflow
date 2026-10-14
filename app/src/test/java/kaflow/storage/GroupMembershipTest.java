package kaflow.storage;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import kaflow.broker.TopicId;
import kaflow.broker.TopicPartition;
import kaflow.groups.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static kaflow.storage.DurableException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class GroupMembershipTest {
    static final GroupId GROUP = new GroupId("billing");
    static final TopicId TOPIC = new TopicId("orders");
    static final JoinCommand JOIN = new JoinCommand(GROUP, TOPIC, "worker");
    static GroupOptions options(int groups, int perGroup, int members, int joins, int controls, long bytes, int metadata) {
        return new GroupOptions(groups, perGroup, members, joins, controls, bytes, metadata, Duration.ofMillis(100));
    }
    static final GroupOptions OPTIONS = options(8, 8, 16, 8, 16, 65536, 131072);
    static List<Integer> partitions(MembershipView view) { return view.assignments().stream().map(TopicPartition::partition).toList(); }
    static void failure(DurableException.Code code, org.junit.jupiter.api.function.Executable call) {
        assertEquals(code, assertThrows(DurableException.class, call).code());
    }
    private GroupMembership state(int partitions) { var state = new GroupMembership(OPTIONS); state.define(GROUP, TOPIC, partitions); return state; }

    @Test void literalJoinOrderAssignmentsAndIndependentGroups() {
        var state = state(5);
        var a = state.join(JOIN, 0); assertEquals(List.of(0,1,2,3,4), partitions(a)); assertEquals(1, a.generation());
        var b = state.join(JOIN, 1); assertEquals(List.of(1,3), partitions(b)); assertEquals(2, b.generation());
        var c = state.join(JOIN, 2); assertEquals(List.of(2), partitions(c));
        a = state.heartbeat(a.identity(),3); b = state.heartbeat(b.identity(),3);
        assertEquals(3,a.generation()); assertEquals(List.of(0,3),partitions(a)); assertEquals(List.of(1,4),partitions(b));
        assertEquals(5, java.util.stream.Stream.of(a,b,c).flatMap(v -> v.assignments().stream()).distinct().count());
        var other = new GroupId("shipping"); state.define(other,TOPIC,5);
        var independent = state.join(new JoinCommand(other,TOPIC,"worker"),4);
        assertEquals(List.of(0,1,2,3,4),partitions(independent)); assertEquals(1,independent.generation());
        assertEquals(3,state.heartbeat(a.identity(),5).generation());
        assertThrows(UnsupportedOperationException.class, () -> independent.assignments().clear());
    }

    @Test void moreMembersThanPartitionsAndDuplicateLabelsHaveIndependentAuthority() {
        var state=state(2); var a=state.join(JOIN,0); var b=state.join(JOIN,0); var c=state.join(JOIN,0);
        assertNotEquals(a.identity(),b.identity()); assertEquals(List.of(),partitions(c)); assertEquals(3,state.memberCount());
        state.leave(a.identity(),1); failure(FENCED,()->state.leave(a.identity(),1));
        assertEquals(List.of(0),partitions(state.heartbeat(b.identity(),1)));
        assertEquals(List.of(1),partitions(state.heartbeat(c.identity(),1)));
        assertEquals(4,state.heartbeat(b.identity(),1).generation());
        failure(FENCED,()->state.heartbeat(new SessionIdentity(GROUP, b.identity().incarnation(), UUID.randomUUID().toString()),2));
    }

    @Test void exactExpiryAndDelayedHeartbeatCannotReviveSession() {
        var state=state(3); var a=state.join(JOIN,0); var b=state.join(JOIN,50_000_000);
        assertEquals(2,state.heartbeat(b.identity(),99_999_999).generation());
        failure(FENCED,()->state.heartbeat(a.identity(),100_000_000));
        var survivor=state.heartbeat(b.identity(),100_000_000);
        assertEquals(3,survivor.generation()); assertEquals(List.of(0,1,2),partitions(survivor));
        failure(FENCED,()->state.leave(a.identity(),100_000_001));
        assertEquals(1,state.memberCount());
        state.expireBatch(200_000_000); assertEquals(0,state.memberCount());
        assertEquals(5,state.join(JOIN,200_000_000).generation());
    }

    @Test void currentOwnershipRequiresAllFencingDimensionsAndFreshApplication() {
        var state=state(3); var a=state.join(JOIN,0);
        state.validate(a.identity(),1,new TopicPartition(TOPIC,0),0);
        var b=state.join(JOIN,1);
        failure(FENCED,()->state.validate(a.identity(),1,new TopicPartition(TOPIC,0),2));
        failure(FENCED,()->state.validate(a.identity(),2,new TopicPartition(TOPIC,1),2));
        failure(FENCED,()->state.validate(b.identity(),2,new TopicPartition(new TopicId("different"),1),2));
        failure(FENCED,()->state.validate(b.identity(),2,new TopicPartition(TOPIC,3),2));
        failure(FENCED,()->state.validate(new SessionIdentity(GROUP,UUID.randomUUID().toString(),a.identity().session()),2,new TopicPartition(TOPIC,0),2));
        assertEquals(2,state.heartbeat(a.identity(),3).generation());
        state.validate(a.identity(),2,new TopicPartition(TOPIC,0),3);
        failure(FENCED,()->state.validate(a.identity(),2,new TopicPartition(TOPIC,0),100_000_003));
    }

    @Test void rejectionsDoNotRenewOrRebalanceAndRenewalsHaveConstantTimerState() {
        var state=state(1); var a=state.join(JOIN,0);
        failure(BINDING_CONFLICT,()->state.checkJoin(new JoinCommand(GROUP,new TopicId("other"),"worker"),1));
        failure(FENCED,()->state.validate(a.identity(),99,new TopicPartition(TOPIC,0),50_000_000));
        failure(FENCED,()->state.heartbeat(a.identity(),100_000_000));
        var b=state.join(JOIN,100_000_000);
        for(int i=0;i<1000;i++) assertEquals(3,state.heartbeat(b.identity(),100_000_000L+i).generation());
        assertEquals(1,state.memberCount()); assertEquals(1,state.groupCount());
        assertFalse(b.toString().contains(b.identity().session())); assertFalse(b.identity().toString().contains(b.identity().session()));
        state.invalidate(); assertEquals(0,state.memberCount()); failure(FENCED,()->state.heartbeat(b.identity(),100_001_000));
    }

    @Test void memberAndGroupCapacityIncludesZeroAssignments() {
        var opts=options(1,2,3,1,1,65536,131072);var state=new GroupMembership(opts);state.define(GROUP,TOPIC,1);
        var a=state.join(JOIN,0);var b=state.join(JOIN,0);assertTrue(b.assignments().isEmpty());
        failure(RESOURCE_EXHAUSTED,()->state.join(JOIN,0));assertEquals(2,state.heartbeat(a.identity(),0).generation());
        failure(RESOURCE_EXHAUSTED,()->state.checkJoin(new JoinCommand(new GroupId("extra"),TOPIC,""),0));
        var total=new GroupMembership(options(3,3,1,1,1,65536,131072));total.define(GROUP,TOPIC,1);
        total.define(new GroupId("other"),TOPIC,1);total.join(JOIN,0);
        failure(RESOURCE_EXHAUSTED,()->total.join(new JoinCommand(new GroupId("other"),TOPIC,""),0));
        assertEquals(Long.MAX_VALUE,GroupMembership.increment(Long.MAX_VALUE-1));
        failure(RESOURCE_EXHAUSTED,()->GroupMembership.increment(Long.MAX_VALUE));
    }

    @Test void generationExhaustionRejectsMutationAndExpiryFencesWholeGroup() throws Exception {
        var state=state(2);var a=state.join(JOIN,0);
        // Reach the otherwise impractical numeric boundary without a production setter/API.
        var groupsField=GroupMembership.class.getDeclaredField("groups");groupsField.setAccessible(true);
        var group=((java.util.Map<?,?>)groupsField.get(state)).get(GROUP);
        var generation=group.getClass().getDeclaredField("generation");generation.setAccessible(true);generation.setLong(group,Long.MAX_VALUE);
        failure(RESOURCE_EXHAUSTED,()->state.join(JOIN,0));failure(RESOURCE_EXHAUSTED,()->state.leave(a.identity(),0));
        assertEquals(Long.MAX_VALUE,state.heartbeat(a.identity(),0).generation());assertEquals(1,state.memberCount());
        state.expireBatch(100_000_000);assertEquals(0,state.memberCount());
        failure(FENCED,()->state.heartbeat(a.identity(),100_000_000));failure(RESOURCE_EXHAUSTED,()->state.join(JOIN,100_000_000));
    }

    @ParameterizedTest @ValueSource(strings={"", "  ", "\uD800", "\uDC00"})
    void rejectsLossyOrBlankGroupIdentities(String invalid) { assertThrows(IllegalArgumentException.class,()->new GroupId(invalid)); }

    @Test void identifierBoundsAreUtf8BytesAndLabelsNeverNormalize() {
        assertEquals(255,new GroupId("é".repeat(127)+"x").byteLength());
        assertThrows(IllegalArgumentException.class,()->new GroupId("é".repeat(128)));
        assertNotEquals(new GroupId(" a"),new GroupId("a"));
        assertThrows(IllegalArgumentException.class,()->new JoinCommand(GROUP,TOPIC,"x".repeat(129)));
        assertThrows(IllegalArgumentException.class,()->new JoinCommand(GROUP,new TopicId("\uD800"),""));
        assertThrows(IllegalArgumentException.class,()->new SessionIdentity(GROUP,"bad","bad"));
        assertThrows(IllegalArgumentException.class,()->new GroupOptions(1,1,1,1,1,1,48,Duration.ofNanos(1)));
    }
}
