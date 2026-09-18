package com.diversive.school.platform.agent;

/**
 * How a value reads inside a confirmation, when its JSON value would read badly: {@code whatsapp} as
 * "WhatsApp". Implemented by request enums; {@link SchoolTemplateFormatter} uses it.
 */
public interface DisplayName {

    String displayName();
}
