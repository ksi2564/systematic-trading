package my.side.trading.kis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KisApprovalKeyResponse(
        @JsonProperty("approval_key") String approvalKey
) {
}
