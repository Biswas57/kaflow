package tributary.core.dtoFinalBoss;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class UpdateAdminGroupRequest {
    private final String newGroupId;
    private final String oldGroupId; // optional
    private final String password; // optional

    @JsonCreator
    public UpdateAdminGroupRequest(
            @JsonProperty("newGroupId") String newGroupId,
            @JsonProperty("oldGroupId") String oldGroupId,
            @JsonProperty("password") String password) {
        if (newGroupId == null || newGroupId.isEmpty()) {
            throw new IllegalArgumentException("newGroupId cannot be null or empty");
        }
        this.newGroupId = newGroupId;
        this.oldGroupId = oldGroupId;
        this.password = password;
    }

    public String getNewGroupId() {
        return newGroupId;
    }

    public String getOldGroupId() {
        return oldGroupId;
    }

    public String getPassword() {
        return password;
    }
}