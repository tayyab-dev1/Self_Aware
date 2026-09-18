package com.diversive.school.fee.overdue;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** Who owes what, for which scope (UC-04-06). Session scope (branch, open session) is never a field. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FeeOverdueQuery(
        @NotNull Scope scope,
        Long classId,
        Long sectionId,
        Long studentId,
        AgeBand ageBand,
        @Positive BigDecimal minimumAmount) {

    /** The scope names exactly its own target: a class list needs a class and nothing else, and so on. */
    @JsonIgnore
    @AssertTrue(message = "the scope must come with exactly its own target: class_id for a class, section_id for a section, "
            + "student_id for a student, and none for the whole school")
    public boolean isTargetMatchingScope() {
        if (scope == null) {
            return true;
        }
        return switch (scope) {
            case SCHOOL_WIDE -> classId == null && sectionId == null && studentId == null;
            case CLASS -> classId != null && sectionId == null && studentId == null;
            case SECTION -> classId == null && sectionId != null && studentId == null;
            case STUDENT -> classId == null && sectionId == null && studentId != null;
        };
    }

    public enum Scope {
        SCHOOL_WIDE("school_wide"), CLASS("class"), SECTION("section"), STUDENT("student");

        private final String wireValue;

        Scope(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }
    }

    public enum AgeBand {
        UP_TO_30_DAYS("1_to_30_days"), UP_TO_60_DAYS("31_to_60_days"), UP_TO_90_DAYS("61_to_90_days"),
        OVER_90_DAYS("over_90_days");

        private final String wireValue;

        AgeBand(String wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        public String wireValue() {
            return wireValue;
        }
    }
}
