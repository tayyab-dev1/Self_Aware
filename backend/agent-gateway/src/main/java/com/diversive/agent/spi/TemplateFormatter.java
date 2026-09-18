package com.diversive.agent.spi;

/**
 * How one value reads inside a confirmation, e.g. {@code 84500.00} as "PKR 84,500" or a channel
 * as "WhatsApp". The host application knows its money, dates and wording; the gateway's plain
 * default is used when it supplies none.
 */
public interface TemplateFormatter {

    /**
     * @param key   the template placeholder, e.g. {@code total_outstanding}
     * @param value a parameter value of its record field's type, a resolved label, the count, or a fact
     */
    String format(String key, Object value);
}
