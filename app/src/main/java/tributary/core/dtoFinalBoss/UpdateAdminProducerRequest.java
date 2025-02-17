package tributary.core.dtoFinalBoss;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class UpdateAdminProducerRequest {
    private final String newProdId;
    private final String oldProdId; // optional
    private final String password; // optional

    @JsonCreator
    public UpdateAdminProducerRequest(
            @JsonProperty("newProdId") String newProdId,
            @JsonProperty("oldProdId") String oldProdId,
            @JsonProperty("password") String password) {
        if (newProdId == null || newProdId.isEmpty()) {
            throw new IllegalArgumentException("newProdId cannot be null or empty");
        }
        this.newProdId = newProdId;
        this.oldProdId = oldProdId;
        this.password = password;
    }

    public String getNewProdId() {
        return newProdId;
    }

    public String getOldProdId() {
        return oldProdId;
    }

    public String getPassword() {
        return password;
    }
}