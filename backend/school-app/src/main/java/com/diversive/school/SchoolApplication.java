package com.diversive.school;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The school backend. Business modules live in sub-packages of
 * {@code com.diversive.school}; the agent gateway arrives through its own
 * auto-configuration and is not component-scanned from here.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SchoolApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchoolApplication.class, args);
    }
}
