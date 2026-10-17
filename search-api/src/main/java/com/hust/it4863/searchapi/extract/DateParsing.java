package com.hust.it4863.searchapi.extract;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chuan hoa chuoi ngay dang lay tu trang web ve Instant. Ho tro:
 *  - ISO-8601 (meta article:published_time, JSON-LD datePublished, the time datetime),
 *    ke ca offset dang +0700 khong co dau ':'
 *  - "yyyy-MM-dd HH:mm[:ss]"
 *  - RFC-1123 ("Thu, 16 Oct 2025 08:00:00 GMT")
 *  - kieu Viet Nam "dd/MM/yyyy[, HH:mm]" hoac "HH:mm dd/MM/yyyy" (vd. "Thu nam, 16/10/2025, 08:00 (GMT+7)")
 * Chuoi khong co mui gio duoc hieu theo gio Viet Nam (Asia/Ho_Chi_Minh).
 */
final class DateParsing {

    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final Pattern OFFSET_WITHOUT_COLON = Pattern.compile("([+-]\\d{2})(\\d{2})$");
    private static final Pattern SPACE_SEPARATED_ISO =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}(:\\d{2})?)$");
    private static final Pattern VN_DATE = Pattern.compile("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TIME = Pattern.compile("(?<!\\d)(\\d{1,2}):(\\d{2})(?::\\d{2})?(?!\\d)");

    private DateParsing() {
    }

    /** Chuoi co chua 1 ngay dang dd/MM/yyyy hoac yyyy-MM-dd hay khong (loc ung vien heuristic). */
    static boolean containsDate(String text) {
        return text != null && (VN_DATE.matcher(text).find() || ISO_DATE.matcher(text).find());
    }

    static Optional<Instant> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String s = raw.strip();
        Matcher noColon = OFFSET_WITHOUT_COLON.matcher(s);
        if (s.contains("T") && noColon.find()) {
            s = noColon.replaceFirst("$1:$2");
        }
        Matcher spaced = SPACE_SEPARATED_ISO.matcher(s);
        if (spaced.matches()) {
            s = spaced.group(1) + "T" + spaced.group(2);
        }

        Optional<Instant> iso = parseIso(s);
        if (iso.isPresent()) {
            return iso;
        }
        try {
            return Optional.of(ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (DateTimeParseException ignored) {
            // thu kieu Viet Nam ben duoi
        }
        return parseVietnamese(s);
    }

    private static Optional<Instant> parseIso(String s) {
        try {
            return Optional.of(OffsetDateTime.parse(s).toInstant());
        } catch (DateTimeParseException ignored) {
            // khong co offset
        }
        try {
            return Optional.of(ZonedDateTime.parse(s).toInstant());
        } catch (DateTimeParseException ignored) {
            // khong co zone
        }
        try {
            return Optional.of(LocalDateTime.parse(s).atZone(DEFAULT_ZONE).toInstant());
        } catch (DateTimeParseException ignored) {
            // khong co gio
        }
        try {
            return Optional.of(LocalDate.parse(s).atStartOfDay(DEFAULT_ZONE).toInstant());
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Instant> parseVietnamese(String s) {
        Matcher date = VN_DATE.matcher(s);
        if (!date.find()) {
            return Optional.empty();
        }
        int day = Integer.parseInt(date.group(1));
        int month = Integer.parseInt(date.group(2));
        int year = Integer.parseInt(date.group(3));
        int hour = 0;
        int minute = 0;
        Matcher time = TIME.matcher(s);
        if (time.find()) {
            hour = Integer.parseInt(time.group(1));
            minute = Integer.parseInt(time.group(2));
        }
        try {
            return Optional.of(LocalDateTime.of(year, month, day, hour, minute).atZone(DEFAULT_ZONE).toInstant());
        } catch (java.time.DateTimeException e) {
            // vd. 31/02/2025, hoac 1 chuoi so khong phai ngay
            return Optional.empty();
        }
    }
}
