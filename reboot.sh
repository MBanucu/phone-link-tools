#!/usr/bin/env bash
# reboot.sh -- restart the phone and do not return until it is really back:
# adbd reachable, sys.boot_completed=1, and a *new* kernel boot id.
#
#   ./reboot.sh                  # wait up to 300 s
#   ./reboot.sh --timeout 120
#
# It does not unlock; unlock.sh does that. Exit status: 0 rebooted, 1 failed.
set -uo pipefail

SERIAL=${PHONE_SERIAL:-}
TIMEOUT=300

while [ $# -gt 0 ]; do
    case "$1" in
        --timeout) TIMEOUT=${2:-300}; shift ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
    shift
done

pick_serial() {
    [ -n "$SERIAL" ] && return 0
    SERIAL=$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }' \
        | grep -v ':' | head -n 1)
    [ -n "$SERIAL" ]
}
pick_serial || { echo "no adb device; plug the phone in" >&2; exit 1; }
d() { adb -s "$SERIAL" "$@"; }

boot_id() { d shell cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r'; }

old=$(boot_id)
[ -n "$old" ] || { echo "cannot read boot id" >&2; exit 1; }

d reboot || { echo "adb reboot failed" >&2; exit 1; }
echo "reboot sent ($SERIAL, boot id $old)"

deadline=$(( SECONDS + TIMEOUT ))
while :; do
    [ "$SECONDS" -lt "$deadline" ] || { echo "timeout: not back after ${TIMEOUT}s" >&2; exit 1; }
    sleep 0.5
    d get-state >/dev/null 2>&1 || continue
    [ "$(d shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] || continue
    new=$(boot_id)
    [ -n "$new" ] || continue
    [ "$new" != "$old" ] || continue
    echo "back up: boot id $new after $SECONDS s"
    exit 0
done
