package tributary.api.dto;

public record OffsetRequest(String partitionId, int offset) {
}
