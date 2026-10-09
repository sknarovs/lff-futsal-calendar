package lv.sknarovs.futsalcalendar;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeTraversor;

/** Parses the LFF schedule page into matches; a port of the Python {@code parse_matches}. */
final class ScheduleParser {
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4),
            Map.entry("mai", 5), Map.entry("jūn", 6), Map.entry("jūl", 7), Map.entry("aug", 8),
            Map.entry("sep", 9), Map.entry("okt", 10), Map.entry("nov", 11), Map.entry("dec", 12));
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{1,2})");

    private ScheduleParser() {
    }

    static List<Match> parse(String html) {
        List<Match> matches = new ArrayList<>();
        String round = "";
        // Round headers and matches come back in document order.
        for (Element el : Jsoup.parse(html).select("div.th1, div.tr.match")) {
            if (el.hasClass("th1")) {
                Element h3 = el.selectFirst("span.h3");
                round = h3 == null ? "" : text(h3);
            } else {
                Match m = parseMatch(el, round);
                if (m != null) {
                    matches.add(m);
                }
            }
        }
        return matches;
    }

    private static Match parseMatch(Element match, String round) {
        Element date = match.selectFirst(".date");
        if (date == null) {
            return null;
        }
        Element dayEl = date.selectFirst("h5");
        Element monthEl = date.selectFirst("h6");
        Element yearEl = date.selectFirst(".h8");
        if (dayEl == null || monthEl == null || yearEl == null) {
            return null;
        }
        Integer month = MONTHS.get(text(monthEl).toLowerCase(Locale.ROOT));
        LocalDate day;
        try {
            day = LocalDate.of(Integer.parseInt(text(yearEl)), month == null ? 0 : month,
                    Integer.parseInt(text(dayEl)));
        } catch (NumberFormatException | DateTimeException e) {
            return null;
        }
        Element timeEl = date.selectFirst(".h7");
        LocalTime time = timeEl == null ? null : parseTime(text(timeEl));

        Elements clubs = match.select(".club");
        if (clubs.size() < 2) {
            return null;
        }
        String res1 = textOf(clubs.get(0).selectFirst(".result .res1"));
        String res2 = textOf(clubs.get(1).selectFirst(".result .res2"));
        String score = isDigits(res1) && isDigits(res2) ? res1 + ":" + res2 : null;

        return new Match(match.attr("data-id"), day, time, team(clubs.get(0)), team(clubs.get(1)),
                score, textOf(match.selectFirst(".stadium")), round);
    }

    private static LocalTime parseTime(String s) {
        Matcher m = TIME.matcher(s);
        if (!m.matches()) {
            return null;
        }
        int hour = Integer.parseInt(m.group(1));
        int minute = Integer.parseInt(m.group(2));
        return hour <= 23 && minute <= 59 ? LocalTime.of(hour, minute) : null;
    }

    private static String team(Element club) {
        Element link = club.selectFirst(".title a");
        return link == null ? "Unknown" : text(link);
    }

    private static boolean isDigits(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    private static String textOf(Element e) {
        return e == null ? "" : text(e);
    }

    /** BeautifulSoup's {@code get_text(strip=True)}: every text node stripped, joined without separator. */
    static String text(Element e) {
        StringBuilder sb = new StringBuilder();
        NodeTraversor.traverse((node, depth) -> {
            if (node instanceof TextNode t) {
                sb.append(pyStrip(t.getWholeText()));
            }
        }, e);
        return sb.toString();
    }

    private static String pyStrip(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && pySpace(s.codePointAt(start))) {
            start += Character.charCount(s.codePointAt(start));
        }
        while (end > start && pySpace(s.codePointBefore(end))) {
            end -= Character.charCount(s.codePointBefore(end));
        }
        return s.substring(start, end);
    }

    /** Whitespace as Python's {@code str.strip()} sees it (includes U+00A0). */
    private static boolean pySpace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85;
    }
}
