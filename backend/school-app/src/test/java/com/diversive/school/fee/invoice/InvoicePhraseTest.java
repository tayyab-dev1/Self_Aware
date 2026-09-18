package com.diversive.school.fee.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class InvoicePhraseTest {

    @Test
    void readsTheStudentAndTheMonthFromEnglish() {
        assertThat(InvoicePhrase.parse("Ahmed Raza's September invoice")).isEqualTo(new InvoicePhrase(List.of("ahmed", "raza"), 9, 0));
    }

    @Test
    void readsRomanUrduAndDropsItsParticles() {
        assertThat(InvoicePhrase.parse("ayesha ki sept 2026 ki fees")).isEqualTo(new InvoicePhrase(List.of("ayesha"), 9, 2026));
    }

    @Test
    void wordsThatOnlySayInvoiceNameNoStudent() {
        assertThat(InvoicePhrase.parse("the august fee challan")).isEqualTo(new InvoicePhrase(List.of(), 8, 0));
    }
}
