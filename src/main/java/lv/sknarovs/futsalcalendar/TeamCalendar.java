package lv.sknarovs.futsalcalendar;

import java.util.List;

/** One team's calendar: its display name and its matches in page order. */
record TeamCalendar(String name, List<Match> matches) {
}
