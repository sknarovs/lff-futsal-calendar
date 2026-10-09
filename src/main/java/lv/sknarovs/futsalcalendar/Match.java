package lv.sknarovs.futsalcalendar;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One match from the schedule page.
 *
 * @param time  kick-off time, or {@code null} for an all-day match
 * @param score {@code "home:away"}, or {@code null} when not played yet
 */
record Match(String id, LocalDate date, LocalTime time, String homeTeam, String awayTeam,
        String score, String stadium, String round) {
}
