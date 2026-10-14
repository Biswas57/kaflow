package kaflow.consumer;

import java.util.List;
import kaflow.broker.StoredRecord;

public record FetchResult(List<StoredRecord> records, long nextOffset, long publishedEnd) {
    public FetchResult { records = List.copyOf(records); }
}
