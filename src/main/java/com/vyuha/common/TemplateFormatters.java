package com.vyuha.common;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * Reusable Thymeleaf formatting helper bean (@fmt) for UTC timestamps and byte sizes.
 */
@Component("fmt")
public class TemplateFormatters {

    private static final DateTimeFormatter UTC_MINUTE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    public String utc(Instant instant) {
        if (instant == null) {
            return "—";
        }
        return UTC_MINUTE_FMT.format(instant) + " UTC";
    }
}
