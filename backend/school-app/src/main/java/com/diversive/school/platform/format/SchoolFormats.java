package com.diversive.school.platform.format;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** How the school writes money and dates for people: "PKR 71,500", "14 September 2026". */
public final class SchoolFormats {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMMM yyyy 'at' h:mm a", Locale.ENGLISH);

    private SchoolFormats() {
    }

    /** Rupees, grouped in thousands, with paisas only when there are some: "PKR 71,500", "PKR 3,250.50". */
    public static String money(BigDecimal amount) {
        BigDecimal rupees = amount.setScale(2, RoundingMode.HALF_UP);
        String pattern = rupees.stripTrailingZeros().scale() <= 0 ? "#,##0" : "#,##0.00";
        return "PKR " + new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ENGLISH)).format(rupees);
    }

    /** "1 guardian", "5 guardians", "1,200 guardians". */
    public static String count(long count, String singular, String plural) {
        return new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ENGLISH)).format(count) + " "
                + (count == 1 ? singular : plural);
    }

    public static String date(LocalDate date) {
        return DATE.format(date);
    }

    /** "14 September 2026 at 3:05 pm". */
    public static String dateTime(OffsetDateTime dateTime) {
        return DATE_TIME.format(dateTime).replace("AM", "am").replace("PM", "pm");
    }

    /** "September 2026", for a billing period. */
    public static String month(LocalDate date) {
        return MONTH.format(date);
    }
}
