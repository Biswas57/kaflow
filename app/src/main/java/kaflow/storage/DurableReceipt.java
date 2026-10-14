package kaflow.storage;

import kaflow.broker.TopicPartition;

/** Issued only after durable checkpoint installation and coordinator publication. */
public record DurableReceipt(TopicPartition partition, long offset, long timestampMillis) { }
