# LFF Futsal Virslīga 2026/27 - Calendar

Automated calendar generator for the Latvian Football Federation (LFF)
Telpu futbola virslīga (Futsal Higher League) 2026/27 season.

## Subscribe to a Team Calendar

Copy the raw `.ics` URL below and add it to your calendar app:

| Team | Calendar URL |
|------|-------------|
| AVClub | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/master/cal/avclub.ics` |
| FC Nikers | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/master/cal/fc-nikers.ics` |
| FC Talsi | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/master/cal/fc-talsi.ics` |
| FK Nīca/OtankiMill | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/master/cal/fk-nica-otankimill.ics` |
| Futbola Parks Academy | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/master/cal/futbola-parks-academy.ics` |
| Salaspils FA | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/master/cal/salaspils-fa.ics` |
| Squad/Samgus Aizkraukle | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/master/cal/squad-samgus-aizkraukle.ics` |
| TFK Beitar Riga | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/master/cal/tfk-beitar-riga.ics` |
| TFK Salaspils | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/master/cal/tfk-salaspils.ics` |

### How to subscribe

- **Google Calendar**: Settings > Add calendar > From URL > paste the URL
- **Apple Calendar**: File > New Calendar Subscription > paste the URL
- **Outlook**: Calendar > Add calendar > From internet > paste the URL
- **Thunderbird**: File > Subscribe to Calendar > Network > paste the URL
- **Any app supporting iCal**: Import the `.ics` file or subscribe via URL

## Schedule Updates

A cron job on a Raspberry Pi scrapes the latest schedule from
[lff.lv](https://lff.lv/sacensibas/telpu-futbols/virsliga/) and commits updated `.ics` files to
this repository. How often it runs is set in the crontab.

- Event times are in Latvian time (Europe/Riga), so calendar apps show them in your own time zone.
- Games appear as 2-hour slots. Games without a kick-off time yet appear as all-day events.
- When scores become available, they appear in the event summary (e.g., `FK Nīca/OtankiMill 9:3 TFK Salaspils`).

## How It Works

- `lff-futsal-calendar` is a Java program compiled to a native binary with GraalVM. It fetches
  the schedule and writes `cal/<team>.ics` in the current directory. Nothing else: no git, no scheduling.
- `run.sh` is the Pi's cron entry point. It runs `git pull`, installs a newer release binary into
  `bin/` when one exists, runs the binary, then commits and pushes the changed calendars.

## Development

Requirements: a JDK 25 for tests, and, for native builds on Fedora Atomic, the
`fedora-toolbox-44` toolbox.

```bash
./gradlew test
```

One-time setup for native builds:

```bash
toolbox run -c fedora-toolbox-44 sudo dnf install -y gcc glibc-devel zlib-devel libstdc++-static
sdk install java 25.4.4.1+1-graalce   # answer "n" to keep your default JDK
```

Build and try the native binary (`--no-daemon` keeps Gradle from reusing a daemon started
outside the toolbox, where there is no `gcc`):

```bash
toolbox run -c fedora-toolbox-44 env GRAALVM_HOME="$HOME/.sdkman/candidates/java/25.4.4.1+1-graalce" \
    ./gradlew --no-daemon nativeCompile
(cd "$(mktemp -d)" && ~-/build/native/nativeCompile/lff-futsal-calendar && ls cal)
```

The GraalVM CE version above must match the container image tag in
`.github/workflows/release.yml`.

## Releasing

```bash
git tag v1.2.0 && git push origin v1.2.0
```

GitHub Actions builds `lff-futsal-calendar-linux-aarch64` on an ARM64 runner (with
`-march=compatibility`, so it runs on a Raspberry Pi 3) and attaches it to a GitHub Release. The
Pi picks it up on its next run. Tags containing `-` (e.g. `v1.2.0-rc.1`) become prereleases,
which the Pi ignores, which makes them useful for trying a build first.

## Raspberry Pi Setup

Requires a 64-bit Raspberry Pi OS, `git` and `curl`.

```bash
git clone git@github.com:sknarovs/lff-futsal-calendar.git   # SSH, so the Pi can push
cd lff-futsal-calendar
./run.sh                                                      # installs the latest release into bin/
```

Then add a crontab entry (`crontab -e`) on whatever schedule you like, for example:

```
0 6 * * * /home/pi/lff-futsal-calendar/run.sh >> /home/pi/lff-futsal-calendar.log 2>&1
```
