package com.diversive.school.fee.invoice;

import com.diversive.school.platform.agent.NameSearch;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a user's words for an invoice say: "Ahmed Raza's September invoice" is the student's name words
 * [ahmed, raza] and month 9; "ayesha ki september 2026 ki fees" is [ayesha], month 9, year 2026. Words
 * that only say "invoice" in English or Roman Urdu are dropped.
 *
 * @param nameWords words of the student's name; empty means the words name no student
 * @param month     1 to 12, or 0 when no month was said
 * @param year      the billing year, or 0 when none was said
 */
record InvoicePhrase(List<String> nameWords, int month, int year) {

    private static final Set<String> FILLER = Set.of(
            "invoice", "invoices", "fee", "fees", "bill", "challan", "tuition", "for", "of", "the", "month", "s",
            "in", "section", "student", "ka", "ki", "ke");
    private static final Map<String, Month> MONTHS = months();

    InvoicePhrase {
        nameWords = List.copyOf(nameWords);
    }

    static InvoicePhrase parse(String raw) {
        List<String> nameWords = new ArrayList<>();
        int month = 0;
        int year = 0;
        for (String word : NameSearch.words(raw)) {
            if (month == 0 && MONTHS.containsKey(word)) {
                month = MONTHS.get(word).getValue();
            } else if (year == 0 && word.matches("20\\d\\d")) {
                year = Integer.parseInt(word);
            } else if (!FILLER.contains(word)) {
                nameWords.add(word);
            }
        }
        return new InvoicePhrase(nameWords, month, year);
    }

    /** "september" and "sep" (and "sept") for every month. */
    private static Map<String, Month> months() {
        Map<String, Month> months = new HashMap<>();
        for (Month month : Month.values()) {
            months.put(month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT), month);
            months.put(month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toLowerCase(Locale.ROOT), month);
        }
        months.put("sept", Month.SEPTEMBER);
        return Map.copyOf(months);
    }
}
