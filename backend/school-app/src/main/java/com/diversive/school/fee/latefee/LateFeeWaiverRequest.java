package com.diversive.school.fee.latefee;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Waive one late fee, with its own reason (UC-04-15). A late fee is a LateFee invoice line. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LateFeeWaiverRequest(@NotNull Long lateFeeId, @NotBlank @Size(max = 500) String waiverReason) {
}
