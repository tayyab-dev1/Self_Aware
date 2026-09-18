package com.diversive.school.fee.overdue;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Who owes what (UC-04-06), one row per student with overdue fees.
 *
 * @param count            how many students are listed
 * @param students         the same number in words, e.g. "8 students"
 * @param scopeName        what the list covers, e.g. "Class 5 Blue" or "the whole school"
 * @param totalOutstanding what the listed students owe on their overdue invoices
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FeeOverdueResponse(
        long count,
        String students,
        String scopeName,
        BigDecimal totalOutstanding,
        List<OverdueStudent> rows) {

    public FeeOverdueResponse {
        rows = List.copyOf(rows);
    }

    /** @param daysOverdue since the oldest overdue invoice fell due */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OverdueStudent(long studentId, String studentName, String admissionNo, String section, String guardianName,
                                 BigDecimal outstanding, LocalDate oldestDueOn, long daysOverdue) {
    }
}
