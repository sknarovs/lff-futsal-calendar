# LFF Futsal Virslīga 2026/27 Calendar: deployment branch

This branch holds only what the Raspberry Pi needs:

- `run.sh`: the cron job. It installs the latest release binary, regenerates the calendars and pushes them here.
- `cal/<team>.ics`: the generated team calendars, committed by the Pi.

Subscribe with `https://raw.githubusercontent.com/sknarovs/lff-futsal-calendar/master/cal/<team>.ics`.
The full list of teams is in the [`dev`](https://github.com/sknarovs/lff-futsal-calendar/tree/dev) README.

On the Pi, clone only this branch:

```bash
git clone --single-branch --branch master git@github.com:sknarovs/lff-futsal-calendar.git
```

The source code, the build and the full setup guide are on the [`dev`](https://github.com/sknarovs/lff-futsal-calendar/tree/dev) branch.
