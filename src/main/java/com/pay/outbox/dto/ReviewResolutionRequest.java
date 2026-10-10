package com.pay.outbox.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Operator decision for a payout in MANUAL_REVIEW, based on evidence from the provider.
 * COMPLETED settles the held funds and requires the provider's payment reference; FAILED refunds them.
 */
@Data
public class ReviewResolutionRequest {

    public enum Resolution { COMPLETED, FAILED }

    @NotNull(message = "Resolution is required")
    private Resolution resolution;

    @Size(max = 100, message = "must be at most 100 characters")
    @Pattern(regexp = "^[A-Za-z0-9._:/-]*$", message = "may contain letters, digits and . _ : / - only")
    private String providerReference;

    @NotBlank(message = "A note with the evidence for this resolution is required")
    @Size(max = 500, message = "must be at most 500 characters")
    private String note;
}
