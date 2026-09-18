package com.diversive.school.dashboard;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The main dashboard's fee figures for the user's branch (UC-01-07), each calculated at {@code calculatedAt}.
 *
 * @param collectedToday     money received today, by the day it was received, not entered
 * @param collectedThisMonth money received since the first of this month
 * @param totalOutstanding   what is still owed on issued invoices
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DashboardResponse(
        String branchName,
        OffsetDateTime calculatedAt,
        BigDecimal collectedToday,
        BigDecimal collectedThisMonth,
        BigDecimal totalOutstanding) {
}
