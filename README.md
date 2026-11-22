# phone-link-tools

Tooling for the Android side of phone-link: the autostart toggler, and the two
scripts needed to test anything before the first unlock.

- `autotoggle/` — accessibility service that drives DokeOS's per-app
  *Auto Start Management* list.
- `reboot.sh` — restart the phone and wait until it is really back.
- `unlock.sh` — clear the keyguard with the PIN.

## Why the toggler exists

DokeOS gates boot starts per package, and the list behind that gate has no
public API. A row only takes effect for the copy of the app that was installed
when the row was written, so after every install the row has to go **off and
back on** and the app has to be opened once. Doing that by hand is slow and
misses things; this service does it over accessibility instead.

## Build and install

The build runs on the phone under Termux (`aapt`/`d8`/`javac`/`apksigner`); it
wants `android.jar` (in `./android.jar` or `$HOME/android-sdk/platforms/*/`) and
the debug keystore:

```bash
cd ~/phone-link-tools/autotoggle && bash build.sh
adb install -r build/phone-link-autotoggle.apk
```

**Installing resets the accessibility grant.** Re-enable it afterwards, or the
service binds nothing and every command silently does nothing:

```bash
adb shell settings put secure enabled_accessibility_services \
    org.phonelink.autotoggle/org.phonelink.autotoggle.ToggleService
adb shell settings put secure accessibility_enabled 1
adb shell dumpsys accessibility | grep -m1 'Bound services'
```

The same grant is cleared on every reboot; `wireless-adb-keeper` puts it back
from its boot-time `ensure` step.

## Command interface

Both commands go to the receiver `org.phonelink.autotoggle/.Command`.

**Toggle rows** (labels separated by `;`, matched against the list's own
labels):

```bash
adb shell "am broadcast -n org.phonelink.autotoggle/.Command \
    -a org.phonelink.autotoggle.TOGGLE --es labels 'Phone link proxy;Termux:Boot'"
```

For each label the service drives the switch off and back on, then launches the
app once and returns to the list. A run ends with a summary in logcat.

**List rows** — useful to discover the exact labels and their state:

```bash
adb shell "am broadcast -n org.phonelink.autotoggle/.Command \
    -a org.phonelink.autotoggle.LIST"
```

Results are logged under the tag `ATOGGLE`:

```bash
adb logcat -s ATOGGLE
# ROW on  Phone link proxy
# ROW off Termux:GUI
# rows=103
# Phone link proxy end=on
# summary=9/9 re-registered, 9 opened
# done in 11813 ms
```

Failures are reported per label: `=not found`, `=no switch`,
`=tap ignored (stale entry?)`.

## How it finds a row

The vendor draws each row as a label and a `Switch` with nothing tying them
together, so a text search misses rows that are plainly on screen. The service
pairs them by vertical overlap instead.

Every label found by a full scan is remembered with the page it was on
(`SharedPreferences` named `pages`), so a repeat run jumps straight there and
only falls back to scanning when the hint is stale. Waits poll the switch state
instead of sleeping, and a switch sitting at the screen edge is brought fully
into view with a half-page nudge.

## Boot workflow

`reboot.sh` sends the reboot and blocks until a **new** kernel boot id is there,
so callers never race the old adbd:

```bash
./reboot.sh                 # wait up to 300 s
./reboot.sh --timeout 120
PHONE_SERIAL=<serial> ./reboot.sh
```

`unlock.sh` clears the keyguard; it never stores the PIN:

```bash
PHONE_PIN=1234 ./unlock.sh          # unlock now
./unlock.sh --pin 1234 --wait       # wait for boot first, then unlock
./unlock.sh --check                 # print the state, change nothing
```

A typical pre-unlock check, remembering that the phone is the laptop's only
uplink and has to be unlocked again at the end of it:

```bash
./reboot.sh
sleep 30
adb shell dumpsys user | grep -m1 'State:'   # RUNNING_LOCKED
# ... inspect whatever is supposed to run while locked ...
./unlock.sh --pin 1234
```

## Caveats

- Vendor specific: the list activity
  (`com.blackview.systemmanager/com.freeme.freemanager.autoboot.AutoStartMainActivity`)
  and the switch layout are Blackview/DokeOS details.
- Labels must match what the list prints — use `LIST` rather than guessing.
- Rows are per package: reinstalling an app means toggling its row again.
