package lv.sknarovs.futsalcalendar;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Renders a team calendar as iCalendar (RFC 5545) text. */
final class IcsWriter {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final int MAX_LINE_OCTETS = 74;

    private IcsWriter() {
    }

    static String write(TeamCalendar cal, Instant stamp) {
        String dtstamp = DATE_TIME.format(stamp.atOffset(ZoneOffset.UTC)) + "Z";
        List<String> lines = new ArrayList<>();
        lines.add("BEGIN:VCALENDAR");
        lines.add("VERSION:2.0");
        lines.add("PRODID:" + escape("-//LFF Futsal Virslīga//lv"));
        lines.add("CALSCALE:GREGORIAN");
        lines.add("X-WR-CALNAME:" + escape(cal.name()));
        for (Match m : cal.matches()) {
            addEvent(lines, m, dtstamp);
        }
        lines.add("END:VCALENDAR");

        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(fold(line)).append("\r\n");
        }
        return out.toString();
    }

    private static void addEvent(List<String> lines, Match m, String dtstamp) {
        lines.add("BEGIN:VEVENT");
        lines.add("SUMMARY:" + escape(summary(m)));
        if (m.time() != null) {
            LocalDateTime start = m.date().atTime(m.time());
            lines.add("DTSTART:" + DATE_TIME.format(start));
            lines.add("DTEND:" + DATE_TIME.format(start.plusHours(1)));
        } else {
            lines.add("DTSTART;VALUE=DATE:" + DATE.format(m.date()));
            lines.add("DTEND;VALUE=DATE:" + DATE.format(m.date().plusDays(1)));
        }
        lines.add("DTSTAMP:" + dtstamp);
        lines.add("UID:" + escape(m.id() + "@lff-futsal"));
        lines.add("DESCRIPTION:" + escape(description(m)));
        if (!m.stadium().isEmpty()) {
            lines.add("LOCATION:" + escape(m.stadium()));
        }
        lines.add("END:VEVENT");
    }

    private static String summary(Match m) {
        return m.score() == null
                ? m.homeTeam() + " vs " + m.awayTeam()
                : m.homeTeam() + " " + m.score() + " " + m.awayTeam();
    }

    private static String description(Match m) {
        List<String> parts = new ArrayList<>();
        if (!m.round().isEmpty()) {
            parts.add(m.round());
        }
        if (m.score() != null) {
            parts.add("Score: " + m.score());
        }
        parts.add("Match ID: " + m.id());
        return String.join("\n", parts);
    }

    /** Escapes an iCalendar TEXT value. */
    static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n");
    }

    /** Folds a content line so each physical line holds at most 74 octets, never splitting a character. */
    static String fold(String line) {
        StringBuilder out = new StringBuilder();
        int octets = 0;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            int n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (octets + n > MAX_LINE_OCTETS) {
                out.append("\r\n ");
                octets = 0;
            }
            out.appendCodePoint(cp);
            octets += n;
            i += Character.charCount(cp);
        }
        return out.toString();
    }
}
