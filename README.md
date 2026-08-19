# LFF Futsal Virslīga 2026/27 - Calendar

Automated calendar generator for the Latvian Football Federation (LFF)
Telpu futbola virslīga (Futsal Higher League) 2026/27 season.

## Subscribe to a Team Calendar

Copy the raw `.ics` URL below and add it to your calendar app:

| Team | Calendar URL |
|------|-------------|
| AVClub | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/main/cal/avclub.ics` |
| FC Nikers | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/main/cal/fc-nikers.ics` |
| FC Talsi | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/main/cal/fc-talsi.ics` |
| FK Nīca/OtankiMill | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/main/cal/fk-nica-otankimill.ics` |
| Futbola Parks Academy | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/main/cal/futbola-parks-academy.ics` |
| Salaspils FA | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/main/cal/salaspils-fa.ics` |
| Squad/Samgus Aizkraukle | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/main/cal/squad-samgus-aizkraukle.ics` |
| TFK Beitar Riga | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/main/cal/tfk-beitar-riga.ics` |
| TFK Salaspils | `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/main/cal/tfk-salaspils.ics` |

### How to subscribe

- **Google Calendar**: Settings > Add calendar > From URL > paste the URL
- **Apple Calendar**: File > New Calendar Subscription > paste the URL
- **Outlook**: Calendar > Add calendar > From internet > paste the URL
- **Thunderbird**: File > Subscribe to Calendar > Network > paste the URL
- **Any app supporting iCal**: Import the `.ics` file or subscribe via URL

## Schedule Updates

Calendars are updated automatically every 6 hours via cron on a Raspberry Pi.
The script scrapes the latest schedule from [lff.lv](https://lff.lv/sacensibas/telpu-futbols/virsliga/)
and commits updated `.ics` files to this repository.

When scores become available, they will appear in the event summary (e.g., `FK Nīca 3:2 TFK Salaspils`).

## Manual Run

```bash
./run.sh
```

This creates a virtual environment on first run, installs dependencies, scrapes the schedule,
generates `.ics` files in `cal/`, and commits + pushes to git.

## Cron Setup (Raspberry Pi)

```
0 */6 * * * /path/to/lff-futsal-calendar/run.sh >> /var/log/lff-futsal.log 2>&1
```

## Project Structure

```
├── fetch_schedule.py    # Main script
├── requirements.txt     # Python dependencies
├── run.sh              # Wrapper with venv setup
├── cal/                # Generated .ics files
│   ├── avclub.ics
│   ├── fc-nikers.ics
│   └── ...
└── README.md
```
