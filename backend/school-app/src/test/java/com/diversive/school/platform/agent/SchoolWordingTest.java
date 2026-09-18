package com.diversive.school.platform.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.diversive.agent.spi.EntityMatch;
import com.diversive.school.fee.payment.FeePaymentRequest.Route;
import com.diversive.school.fee.reminder.FeeReminderRequest.Channel;
import com.diversive.school.platform.format.SchoolFormats;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How names are matched and how values read in the school's confirmations. No Docker needed. */
class SchoolWordingTest {

    private final SchoolTemplateFormatter formatter = new SchoolTemplateFormatter(JsonMapper.builder().build());

    @Test
    void namesAreSplitIntoLowerCaseWholeWords() {
        assertThat(NameSearch.words("  Class-5  BLUE! ")).containsExactly("class", "5", "blue");
        assertThat(NameSearch.words("Ahmed's")).containsExactly("ahmed", "s");
        assertThat(NameSearch.words("!!!")).isEmpty();
        assertThat(NameSearch.sqlWords(List.of("class", "5"))).isEqualTo("class 5");
    }

    @Test
    void anExactNameWinsOverLongerOnesThatAlsoMatch() {
        List<EntityMatch> matches = List.of(new EntityMatch("1", "Class 5", null), new EntityMatch("2", "Class 5 Annex", null));

        assertThat(NameSearch.preferExact("class 5", matches)).extracting(EntityMatch::id).containsExactly("1");
        assertThat(NameSearch.preferExact("5", matches)).hasSize(2);
    }

    @Test
    void moneyDatesAndChannelsReadTheWayTheSchoolWritesThem() {
        assertThat(formatter.format("total_outstanding", new BigDecimal("71500.00"))).isEqualTo("PKR 71,500");
        assertThat(formatter.format("amount_received", new BigDecimal("3250.5"))).isEqualTo("PKR 3,250.50");
        assertThat(formatter.format("payment_date", LocalDate.of(2026, 9, 14))).isEqualTo("14 September 2026");
        assertThat(formatter.format("channel", Channel.WHATSAPP)).isEqualTo("WhatsApp");
        assertThat(formatter.format("channel", Channel.SMS)).isEqualTo("SMS");
        assertThat(formatter.format("route", Route.BANK_CHALLAN)).isEqualTo("by bank challan");
        assertThat(formatter.format("count", 5L)).isEqualTo("5");
        assertThat(formatter.format("calculated_at", OffsetDateTime.parse("2026-09-14T15:05:00+05:00")))
                .isEqualTo("14 September 2026 at 3:05 pm");
        assertThat(formatter.format("section_name", "Class 5 Blue")).isEqualTo("Class 5 Blue");
    }

    @Test
    void countsInWordsChooseTheirPlural() {
        assertThat(SchoolFormats.count(1, "guardian", "guardians")).isEqualTo("1 guardian");
        assertThat(SchoolFormats.count(5, "guardian", "guardians")).isEqualTo("5 guardians");
        assertThat(SchoolFormats.count(1200, "guardian", "guardians")).isEqualTo("1,200 guardians");
        assertThat(SchoolFormats.month(LocalDate.of(2026, 8, 1))).isEqualTo("August 2026");
    }
}
