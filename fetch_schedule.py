#!/usr/bin/env python3
"""Fetch LFF Futsal Virslīga schedule and generate per-team iCal files."""

import os
import re
import subprocess
import sys
from datetime import date, datetime, timedelta
from pathlib import Path

import requests
from bs4 import BeautifulSoup
from icalendar import Calendar, Event

URL = "https://lff.lv/sacensibas/telpu-futbols/virsliga/"
TIMEOUT = 30
DOMAIN = "https://lff.lv"

MONTH_MAP = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4,
    "mai": 5, "jūn": 6, "jūl": 7, "aug": 8,
    "sep": 9, "okt": 10, "nov": 11, "dec": 12,
}


def slugify(name: str) -> str:
    """Convert team name to a filesystem-safe slug."""
    s = name.lower().strip()
    s = re.sub(r"[^a-z0-9]+", "-", s)
    s = s.strip("-")
    return s


def fetch_page(url: str) -> str:
    """Fetch the page HTML."""
    headers = {"User-Agent": "LFF-Futsal-Calendar/1.0"}
    resp = requests.get(url, headers=headers, timeout=TIMEOUT)
    resp.raise_for_status()
    return resp.text


def parse_matches(html: str) -> list[dict]:
    """Parse all matches from the HTML."""
    soup = BeautifulSoup(html, "html.parser")
    matches = []

    for match_div in soup.select("div.tr.match"):
        data_id = match_div.get("data-id", "")

        # Parse date
        date_div = match_div.select_one(".date")
        if not date_div:
            continue

        day_el = date_div.select_one("h5")
        month_el = date_div.select_one("h6")
        year_el = date_div.select_one(".h8")
        time_el = date_div.select_one(".h7")

        if not (day_el and month_el and year_el):
            continue

        try:
            day = int(day_el.get_text(strip=True))
        except ValueError:
            continue

        month_text = month_el.get_text(strip=True).lower()
        month = MONTH_MAP.get(month_text)
        if month is None:
            continue

        try:
            year = int(year_el.get_text(strip=True))
        except ValueError:
            continue

        time_text = time_el.get_text(strip=True) if time_el else ""
        has_time = bool(time_text)
        hour, minute = 0, 0
        if has_time:
            parts = time_text.split(":")
            if len(parts) == 2:
                hour = int(parts[0])
                minute = int(parts[1])

        # Parse teams
        clubs = match_div.select(".club")
        if len(clubs) < 2:
            continue

        def extract_team(club):
            link = club.select_one(".title a")
            name = link.get_text(strip=True) if link else "Unknown"
            return name

        home_team = extract_team(clubs[0])
        away_team = extract_team(clubs[1])

        # Parse scores
        res1_el = clubs[0].select_one(".result .res1")
        res2_el = clubs[1].select_one(".result .res2")
        res1 = res1_el.get_text(strip=True) if res1_el else ""
        res2 = res2_el.get_text(strip=True) if res2_el else ""
        has_score = res1.isdigit() and res2.isdigit()

        # Parse stadium
        stadium_el = match_div.select_one(".stadium")
        stadium = stadium_el.get_text(strip=True) if stadium_el else ""

        # Find round header
        round_text = ""
        prev = match_div.find_previous("div", class_="th1")
        if prev:
            h3 = prev.select_one("span.h3")
            if h3:
                round_text = h3.get_text(strip=True)

        matches.append({
            "id": data_id,
            "year": year,
            "month": month,
            "day": day,
            "hour": hour,
            "minute": minute,
            "has_time": has_time,
            "home_team": home_team,
            "away_team": away_team,
            "score": f"{res1}:{res2}" if has_score else None,
            "stadium": stadium,
            "round": round_text,
        })

    return matches


def build_calendars(matches: list[dict]) -> dict[str, Calendar]:
    """Build one iCal calendar per team."""
    calendars: dict[str, Calendar] = {}

    def get_cal(team_name: str) -> Calendar:
        slug = slugify(team_name)
        if slug not in calendars:
            cal = Calendar()
            cal.add("prodid", "-//LFF Futsal Virslīga//lv")
            cal.add("version", "2.0")
            cal.add("calscale", "GREGORIAN")
            cal.add("x-wr-calname", team_name)
            calendars[slug] = cal
        return calendars[slug]

    for m in matches:
        uid = f"{m['id']}@lff-futsal"
        summary = f"{m['home_team']} vs {m['away_team']}"
        if m["score"]:
            summary = f"{m['home_team']} {m['score']} {m['away_team']}"

        description_parts = []
        if m["round"]:
            description_parts.append(m["round"])
        if m["score"]:
            description_parts.append(f"Score: {m['score']}")
        description_parts.append(f"Match ID: {m['id']}")
        description = "\n".join(description_parts)

        # Create events for both home and away teams
        for team in (m["home_team"], m["away_team"]):
            cal = get_cal(team)

            event = Event()
            event.add("uid", uid)
            event.add("summary", summary)
            if m["stadium"]:
                event.add("location", m["stadium"])
            if description:
                event.add("description", description)

            if m["has_time"]:
                dt = datetime(m["year"], m["month"], m["day"], m["hour"], m["minute"])
                event.add("dtstart", dt)
                event.add("dtend", dt + timedelta(hours=1))
            else:
                dt = date(m["year"], m["month"], m["day"])
                event.add("dtstart", dt)
                event.add("dtend", dt + timedelta(days=1))

            event.add("dtstamp", datetime.now())
            cal.add_component(event)

    return calendars


def write_calendars(calendars: dict[str, Calendar], output_dir: Path):
    """Write each calendar to a .ics file."""
    output_dir.mkdir(parents=True, exist_ok=True)
    for slug, cal in sorted(calendars.items()):
        filepath = output_dir / f"{slug}.ics"
        with open(filepath, "wb") as f:
            f.write(cal.to_ical())
        print(f"  Written: {filepath.name}")


def git_commit_and_push(repo_dir: Path):
    """Stage, commit, and push the calendar files."""
    try:
        subprocess.run(
            ["git", "add", "cal/"],
            cwd=repo_dir, check=True, capture_output=True,
        )

        status = subprocess.run(
            ["git", "status", "--porcelain", "cal/"],
            cwd=repo_dir, capture_output=True, text=True,
        )
        if not status.stdout.strip():
            print("No changes to commit.")
            return

        now = datetime.now().strftime("%Y-%m-%d %H:%M")
        subprocess.run(
            ["git", "commit", "-m", f"Update calendars ({now})"],
            cwd=repo_dir, check=True, capture_output=True,
        )
        print("Committed.")

        subprocess.run(
            ["git", "push"],
            cwd=repo_dir, check=True, capture_output=True,
        )
        print("Pushed.")
    except subprocess.CalledProcessError as e:
        print(f"Git error: {e.stderr.decode().strip()}", file=sys.stderr)


def main():
    script_dir = Path(__file__).resolve().parent
    cal_dir = script_dir / "cal"

    print("Fetching schedule from lff.lv...")
    html = fetch_page(URL)

    print("Parsing matches...")
    matches = parse_matches(html)
    print(f"  Found {len(matches)} matches.")

    if not matches:
        print("No matches found. Exiting.", file=sys.stderr)
        sys.exit(1)

    print("Generating calendars...")
    calendars = build_calendars(matches)
    print(f"  {len(calendars)} team calendars.")

    print("Writing .ics files...")
    write_calendars(calendars, cal_dir)

    print("Git commit & push...")
    git_commit_and_push(script_dir)

    print("Done!")


if __name__ == "__main__":
    main()
