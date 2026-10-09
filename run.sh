#!/usr/bin/env bash
# Cron entry point on the Raspberry Pi: install the newest release binary,
# regenerate the calendars and publish them. Cron alone decides when this runs.
#
# Everything lives in functions and runs from the last line, so a `git pull`
# that replaces this file cannot disturb the copy bash is executing.
set -euo pipefail

REPO=sknarovs/lff-futsal-calendar
BIN=bin/lff-futsal-calendar

main() {
    cd "$(dirname "$0")"
    git pull -q --rebase --autostash
    update_binary
    "$BIN"
    publish
}

update_binary() {
    local tag
    tag=$(curl -fsSI --max-time 30 "https://github.com/$REPO/releases/latest" \
        | tr -d '\r' | sed -n 's#^location: .*/releases/tag/##Ip') || tag=""
    if [[ -n "$tag" && "$tag" == "$(cat bin/VERSION 2>/dev/null)" && -x "$BIN" ]]; then
        return
    fi
    if [[ -n "$tag" ]] && mkdir -p bin && curl -fsSL --max-time 300 -o "$BIN.tmp" \
        "https://github.com/$REPO/releases/download/$tag/lff-futsal-calendar-linux-$(uname -m)"; then
        chmod +x "$BIN.tmp"
        mv "$BIN.tmp" "$BIN"
        echo "$tag" > bin/VERSION
        echo "Installed $tag."
        return
    fi
    rm -f "$BIN.tmp"
    if [[ ! -x "$BIN" ]]; then
        echo "No binary and no release could be downloaded." >&2
        exit 1
    fi
    echo "Warning: could not check for a new release; using the installed binary." >&2
}

publish() {
    git add cal/
    if [[ -z "$(git status --porcelain cal/)" ]]; then
        echo "No changes to commit."
        return
    fi
    git commit -q -m "Update calendars ($(date '+%Y-%m-%d %H:%M'))"
    git push -q
    echo "Committed and pushed."
}

main "$@"
