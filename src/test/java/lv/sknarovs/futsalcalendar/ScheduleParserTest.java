package lv.sknarovs.futsalcalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

class ScheduleParserTest {
    static final int EXPECTED_MATCHES = 72;

    @Test
    void parsesEveryDatedMatchOnTheFixture() {
        assertEquals(EXPECTED_MATCHES, ScheduleParser.parse(Fixtures.html()).size());
    }

    @Test
    void parsesAPlayedMatch() {
        Match m = find(ScheduleParser.parse(Fixtures.html()), "25157907");
        assertEquals(LocalDate.of(2026, 9, 19), m.date());
        assertEquals(LocalTime.of(17, 0), m.time());
        assertEquals("FK Nīca/OtankiMill", m.homeTeam());
        assertEquals("TFK Salaspils", m.awayTeam());
        assertEquals("9:3", m.score());
        assertEquals("Nīcas sporta halle", m.stadium());
        assertFalse(m.round().isEmpty());
    }

    @Test
    void unplayedMatchHasNoScore() {
        assertNull(find(ScheduleParser.parse(Fixtures.html()), "25157919").score());
    }

    @Test
    void emptyTimeOnFixtureMakesAllDayMatch() {
        assertNull(find(ScheduleParser.parse(Fixtures.html()), "25157950").time());
    }

    @Test
    void skipsMatchWithoutDateBlock() {
        String html = "<div class=\"tr match\" data-id=\"1\"><div class=\"matchday\"><h4>14:00</h4>"
                + "<h5>4</h5><h6>kārta</h6></div>" + clubs("A", "B", "-", "-") + "</div>";
        assertTrue(ScheduleParser.parse(html).isEmpty());
    }

    @Test
    void textMimicsBeautifulSoupStrip() {
        assertEquals("FKNīca", ScheduleParser.text(el("<a> FK <b> Nīca </b></a>")));
        assertEquals("", ScheduleParser.text(el("<span>   </span>")));
    }

    @Test
    void invalidTimeMakesAllDayMatch() {
        assertNull(only(matchHtml("1", "10", "okt", "2026", "TBA", "A", "B", "-", "-", "S")).time());
        assertNull(only(matchHtml("1", "10", "okt", "2026", "25:00", "A", "B", "-", "-", "S")).time());
        assertNull(only(matchHtml("1", "10", "okt", "2026", null, "A", "B", "-", "-", "S")).time());
        assertEquals(LocalTime.of(9, 5), only(matchHtml("1", "10", "okt", "2026", "9:05", "A", "B", "-", "-", "S")).time());
    }

    @Test
    void invalidDateIsSkipped() {
        assertTrue(ScheduleParser.parse(matchHtml("1", "31", "feb", "2027", "17:00", "A", "B", "-", "-", "S")).isEmpty());
    }

    @Test
    void unknownMonthOrNonNumericDayIsSkipped() {
        assertTrue(ScheduleParser.parse(matchHtml("1", "10", "xyz", "2026", "17:00", "A", "B", "-", "-", "S")).isEmpty());
        assertTrue(ScheduleParser.parse(matchHtml("1", "x", "okt", "2026", "17:00", "A", "B", "-", "-", "S")).isEmpty());
    }

    @Test
    void monthIsCaseInsensitiveAndLatvian() {
        assertEquals(LocalDate.of(2027, 6, 3), only(matchHtml("1", "3", "Jūn", "2027", "17:00", "A", "B", "-", "-", "S")).date());
    }

    @Test
    void fewerThanTwoClubsIsSkipped() {
        String html = "<div class=\"tr match\" data-id=\"1\">" + date("10", "okt", "2026", "17:00")
                + "<div class=\"club\"><div class=\"title\"><a>A</a></div></div></div>";
        assertTrue(ScheduleParser.parse(html).isEmpty());
    }

    @Test
    void missingTeamLinkIsUnknown() {
        String html = "<div class=\"tr match\" data-id=\"1\">" + date("10", "okt", "2026", "17:00")
                + "<div class=\"club\"><div class=\"title\">A</div></div>"
                + "<div class=\"club\"><div class=\"title\"><a>B</a></div></div></div>";
        Match m = only(html);
        assertEquals("Unknown", m.homeTeam());
        assertEquals("B", m.awayTeam());
    }

    @Test
    void roundComesFromNearestPrecedingTh1() {
        String html = matchHtml("0", "1", "okt", "2026", "17:00", "A", "B", "-", "-", "S")
                + "<div class=\"tr th1\"><div><span class=\"h3\">1. kārta</span></div></div>"
                + matchHtml("1", "2", "okt", "2026", "17:00", "A", "B", "-", "-", "S")
                + "<div class=\"tr th1\"><div><span class=\"h3\">2. kārta</span></div></div>"
                + matchHtml("2", "3", "okt", "2026", "17:00", "A", "B", "-", "-", "S");
        List<Match> matches = ScheduleParser.parse(html);
        assertEquals(List.of("", "1. kārta", "2. kārta"), matches.stream().map(Match::round).toList());
    }

    @Test
    void scoreNeedsDigitsOnBothSides() {
        assertEquals("10:2", only(matchHtml("1", "1", "okt", "2026", "17:00", "A", "B", "10", "2", "S")).score());
        assertNull(only(matchHtml("1", "1", "okt", "2026", "17:00", "A", "B", "1", "", "S")).score());
    }

    @Test
    void missingIdAndStadiumAreEmpty() {
        String html = "<div class=\"tr match\">" + date("10", "okt", "2026", "17:00") + clubs("A", "B", "-", "-") + "</div>";
        Match m = only(html);
        assertEquals("", m.id());
        assertEquals("", m.stadium());
    }

    private static Match find(List<Match> matches, String id) {
        return matches.stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    private static Match only(String html) {
        List<Match> matches = ScheduleParser.parse(html);
        assertEquals(1, matches.size());
        return matches.getFirst();
    }

    private static Element el(String html) {
        return Jsoup.parseBodyFragment(html).body().child(0);
    }

    static String matchHtml(String id, String day, String month, String year, String time,
            String home, String away, String res1, String res2, String stadium) {
        return "<div class=\"tr match\" data-id=\"" + id + "\">" + date(day, month, year, time)
                + clubs(home, away, res1, res2) + "<div class=\"stadium\">" + stadium + "</div></div>";
    }

    private static String date(String day, String month, String year, String time) {
        return "<div class=\"date\"><h4>sestdiena</h4><h5>" + day + "</h5><h6>" + month + "</h6>"
                + (time == null ? "" : "<div class=\"h7\">" + time + "</div>")
                + "<div class=\"h8\">" + year + "</div></div>";
    }

    private static String clubs(String home, String away, String res1, String res2) {
        return "<div class=\"clubs\"><div class=\"club\"><div class=\"title\"><span><a>" + home
                + "</a></span></div><div class=\"result\"><span class=\"res1\">" + res1 + "</span></div></div>"
                + "<div class=\"club\"><div class=\"title\"><span><a>" + away
                + "</a></span></div><div class=\"result\"><span class=\"res2\">" + res2 + "</span></div></div></div>";
    }
}
