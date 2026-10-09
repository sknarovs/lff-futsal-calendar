package lv.sknarovs.futsalcalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import java.util.SortedMap;
import org.junit.jupiter.api.Test;

class CalendarBuilderTest {
    @Test
    void slugMatchesPythonForNow() {
        assertEquals("fc-talsi", CalendarBuilder.slug("FC Talsi"));
        assertEquals("squad-samgus-aizkraukle", CalendarBuilder.slug("Squad/Samgus Aizkraukle"));
        assertEquals("fk-n-ca-otankimill", CalendarBuilder.slug("FK Nīca/OtankiMill"));
        assertEquals("a-b", CalendarBuilder.slug("--A  b--"));
    }

    @Test
    void eachMatchGoesToHomeAndAwayCalendarsInPageOrder() {
        Match m1 = match("1", "A", "B");
        Match m2 = match("2", "C", "A");
        SortedMap<String, TeamCalendar> cals = CalendarBuilder.build(List.of(m1, m2));
        assertEquals(List.of("a", "b", "c"), List.copyOf(cals.keySet()));
        assertEquals(List.of(m1, m2), cals.get("a").matches());
        assertEquals(List.of(m1), cals.get("b").matches());
        assertEquals(List.of(m2), cals.get("c").matches());
        assertEquals("A", cals.get("a").name());
    }

    @Test
    void calendarNameIsFirstSpellingSeenForSlug() {
        SortedMap<String, TeamCalendar> cals = CalendarBuilder.build(
                List.of(match("1", "FC X", "B"), match("2", "fc x", "C")));
        assertEquals("FC X", cals.get("fc-x").name());
        assertEquals(2, cals.get("fc-x").matches().size());
    }

    private static Match match(String id, String home, String away) {
        return new Match(id, LocalDate.of(2026, 10, 10), null, home, away, null, "", "");
    }
}
