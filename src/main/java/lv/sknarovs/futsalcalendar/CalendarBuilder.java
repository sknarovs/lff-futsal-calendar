package lv.sknarovs.futsalcalendar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/** Groups matches into one calendar per team; a port of the Python {@code build_calendars}. */
final class CalendarBuilder {
    private CalendarBuilder() {
    }

    /** Returns calendars keyed by team slug. */
    static SortedMap<String, TeamCalendar> build(List<Match> matches) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, List<Match>> byTeam = new LinkedHashMap<>();
        for (Match m : matches) {
            for (String team : List.of(m.homeTeam(), m.awayTeam())) {
                String slug = slug(team);
                names.putIfAbsent(slug, team);
                byTeam.computeIfAbsent(slug, k -> new ArrayList<>()).add(m);
            }
        }
        SortedMap<String, TeamCalendar> calendars = new TreeMap<>();
        byTeam.forEach((slug, list) ->
                calendars.put(slug, new TeamCalendar(names.get(slug), Collections.unmodifiableList(list))));
        return calendars;
    }

    /** File-system-safe name for a team. */
    static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).strip();
        s = s.replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("^-+|-+$", "");
    }
}
