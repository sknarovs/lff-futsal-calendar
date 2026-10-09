package lv.sknarovs.futsalcalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class IcsWriterTest {
    @Test
    void escapesTextValues() {
        assertEquals("a\\\\b\\;c\\,d\\ne\\nf", IcsWriter.escape("a\\b;c,d\r\ne\nf"));
    }

    @Test
    void foldsAt74ContentOctets() {
        assertEquals("x".repeat(74), IcsWriter.fold("x".repeat(74)));
        assertEquals("x".repeat(74) + "\r\n x", IcsWriter.fold("x".repeat(75)));
        assertEquals("x".repeat(73) + "\r\n ā", IcsWriter.fold("x".repeat(73) + "ā"));
        assertEquals("x".repeat(72) + "ā", IcsWriter.fold("x".repeat(72) + "ā"));
    }

    @Test
    void longLinesAreFoldedInOutput() {
        String out = write(timed("Rīga " + "ļ".repeat(60)));
        assertTrue(out.lines().allMatch(l -> l.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 75));
        assertTrue(out.contains("\r\n "));
    }

    @Test
    void specialCharactersInStadiumAreEscapedInEvent() {
        assertTrue(write(timed("Rīga, Arēna; \\Halle")).contains("LOCATION:Rīga\\, Arēna\\; \\\\Halle\r\n"));
    }

    @Test
    void allDayEventUsesValueDate() {
        Match m = new Match("7", LocalDate.of(2026, 12, 19), null, "A", "B", null, "S", "12. kārta");
        assertTrue(write(m).contains("DTSTART;VALUE=DATE:20261219\r\nDTEND;VALUE=DATE:20261220\r\n"));
    }

    @Test
    void stampIsUtc() {
        assertTrue(write(timed("S")).contains("DTSTAMP:20261009T030000Z\r\n"));
    }

    @Test
    void emptyStadiumOmitsLocation() {
        assertFalse(write(timed("")).contains("LOCATION"));
    }

    @Test
    void endsWithEndVcalendarCrlf() {
        assertTrue(write(timed("S")).endsWith("END:VEVENT\r\nEND:VCALENDAR\r\n"));
    }

    @Test
    void scoredEventSummaryAndDescription() {
        Match m = new Match("9", LocalDate.of(2026, 9, 20), LocalTime.of(16, 0), "FC Talsi", "Squad", "4:5",
                "Talsu sporta halle", "1. kārta");
        String out = write(m);
        assertTrue(out.contains("SUMMARY:FC Talsi 4:5 Squad\r\n"));
        assertTrue(out.contains("UID:9@lff-futsal\r\n"));
        assertTrue(out.contains("DESCRIPTION:1. kārta\\nScore: 4:5\\nMatch ID: 9\r\n"));
    }

    private static Match timed(String stadium) {
        return new Match("1", LocalDate.of(2026, 10, 10), LocalTime.of(16, 0), "A", "B", null, stadium, "");
    }

    private static String write(Match m) {
        return IcsWriter.write(new TeamCalendar("A", List.of(m)), Fixtures.STAMP);
    }
}
