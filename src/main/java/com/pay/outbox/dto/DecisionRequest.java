package com.pay.outbox.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Approval or rejection of a funding adjustment; the note is kept in the audit log. */
@Data
public class DecisionRequest {

    @NotBlank(message = "A note explaining the decision is required")
    @Size(max = 500, message = "must be at most 500 characters")
    private String note;
}
