package com.diversive.school.fee.reminder;

import com.diversive.school.platform.agent.DisplayName;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import java.util.Locale;

/**
 * Remind the families with overdue fees in one section (UC-04-07). The planning contract selects
 * families one by one; the POC reminds a whole section, which is the counted write the plan needs.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FeeReminderRequest(@NotNull Long sectionId, @NotNull Channel channel) {

    public enum Channel implements DisplayName {
        WHATSAPP("WhatsApp"), SMS("SMS"), EMAIL("email");

        private final String displayName;

        Channel(String displayName) {
            this.displayName = displayName;
        }

        @JsonValue
        public String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        @Override
        public String displayName() {
            return displayName;
        }
    }
}
