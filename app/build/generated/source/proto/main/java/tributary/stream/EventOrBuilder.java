// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: tributary.proto

// Protobuf Java Version: 3.25.3
package tributary.stream;

public interface EventOrBuilder extends
    // @@protoc_insertion_point(interface_extends:tributary.stream.Event)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>string topic_id = 1;</code>
   * @return The topicId.
   */
  java.lang.String getTopicId();
  /**
   * <code>string topic_id = 1;</code>
   * @return The bytes for topicId.
   */
  com.google.protobuf.ByteString
      getTopicIdBytes();

  /**
   * <code>string partition_id = 2;</code>
   * @return The partitionId.
   */
  java.lang.String getPartitionId();
  /**
   * <code>string partition_id = 2;</code>
   * @return The bytes for partitionId.
   */
  com.google.protobuf.ByteString
      getPartitionIdBytes();

  /**
   * <code>uint64 offset = 3;</code>
   * @return The offset.
   */
  long getOffset();

  /**
   * <code>.google.protobuf.Any payload = 4;</code>
   * @return Whether the payload field is set.
   */
  boolean hasPayload();
  /**
   * <code>.google.protobuf.Any payload = 4;</code>
   * @return The payload.
   */
  com.google.protobuf.Any getPayload();
  /**
   * <code>.google.protobuf.Any payload = 4;</code>
   */
  com.google.protobuf.AnyOrBuilder getPayloadOrBuilder();
}
