# Refresher

An Android TV app that kills chosen apps on a timer and brings them straight
back, including while the box is asleep. Built for the cases where an app needs
a periodic kick — VPN clients that drift off a tunnel, streaming apps that
accumulate cache, anything that behaves better after a cold start.

**New in 0.6.8/0.6.9: System.** From the home screen: memory, swap, processor and
storage at a glance, and the apps using the most memory. Pick one to stop it
now or add a stop-only rule, plus a chart of the last hour. A scheduled
refresh never stops the app on screen; it waits and tries again.

## What it is, and what it can see

Refresher does one job: on a schedule you choose, it stops an app on your own
TV and opens it again. It is open source, so none of this has to be taken on
trust.

**Privacy**

- No account, no sign-in, no ads, no analytics, no crash reporting, no
  tracking libraries.
- Nothing is uploaded. The only download is Shizuku itself, and only when you
  press **Get** in Setup: Refresher fetches the APK from Shizuku's own GitHub
  releases (`github.com/RikkaApps/Shizuku`), checks that it is Shizuku and is
  signed by Shizuku's developer, deletes it if not, and hands it to Android's
  installer for you to confirm. Apart from that, its only network connection
  goes to the TV itself (`127.0.0.1`), to start Shizuku and run the one-time
  setup.
- It does not read your viewing, your files, your contacts or your accounts. It
  stores only your rules (which app, how often, a few switches) and a short
  activity log, on the TV.
- The **System** page shows how much memory and processor each app is using,
  read on the TV while the page is open. Its **Last hour** chart keeps one
  reading a minute (memory, processor, and the biggest app when memory runs
  low) in a small file on the TV, only for an hour, only while the TV is on.
- Backup of those rules is a single small file on the TV
  (`/data/local/tmp/refresher/backup.json`). It is only app names and
  intervals, and **Setup > Remove Refresher's setup** deletes it.
- The one-time setup key (for the TV's own network debugging) is generated on
  the TV, stays inside the app's private storage and never leaves it.

**Why each permission**

| Permission | Why |
|---|---|
| Kill background processes, Shizuku | Stopping the apps you picked |
| Exact alarms, wake lock, foreground service | Running on time, even while the TV sleeps |
| Receive boot completed | Re-arming your schedule after a restart |
| Display over other apps | Letting Android allow reopening an app from the background |
| Notifications | The notice shown while a restart is running |
| Network state | Telling when a VPN has reconnected |
| Internet | Local connection to the TV itself, and the one Shizuku download, see above |
| Request install packages | Installing Shizuku when you press Get in Setup (nothing else) |
| Request delete packages | The optional "uninstall Refresher" step in Remove setup |

**Exposed components.** Only the launcher entries, the boot receiver (it just
re-arms schedules), Shizuku's own provider and the device-admin receiver (both
guarded by system permissions) are reachable by other apps. The "Refresh now"
tile can be started by any app, so it is rate-limited to one run a minute.

**Verify it yourself.** Releases are signed with a key kept off the repository.
`adb shell dumpsys package com.thefactor1.taskskiller` lists exactly the
permissions above, and **Setup > Remove Refresher's setup** undoes everything
automatic setup changed.

## What Android actually allows

Worth being blunt about this up front, because it shapes the whole design: **an
ordinary Android app cannot force-stop another app.** That privilege belongs to
the shell, root, and device owners. So Refresher ships four kill backends and
uses the strongest one available on your box:

| Backend | Setup | Result |
|---|---|---|
| **Shizuku** | Install Shizuku, start it via ADB once per boot | Real `am force-stop` |
| **Device owner** | One `dpm` command on a box with no accounts | Real force-stop via hide/unhide |
| **Root** | Grant Refresher root | Real `am force-stop` |
| **Background processes** | None | Best-effort; **foreground services survive** |

The last one works out of the box but will not stop most VPN clients, because
they run a foreground service specifically so the system won't kill them. If a
VPN is your target, plan on Shizuku or device owner.

**Shizuku is the recommended route** for a stock, unrooted Android TV box.
Device owner is the better choice if you want something that survives reboots
with no per-boot ADB step.

## The timer

- Each rule uses `AlarmManager.setExactAndAllowWhileIdle` with an `RTC_WAKEUP`
  alarm, which fires through Doze.
- A mains-powered TV box usually never enters full Doze anyway (Doze requires
  the device to be unplugged), so in practice the timing is tight. App standby
  buckets can still interfere — the setup screen offers the battery-optimisation
  exemption that avoids it.
- The alarm hands off to a short-lived foreground service holding a partial wake
  lock, so the CPU stays up across the kill → wait → relaunch sequence with the
  screen off.
- Alarms don't survive a reboot, so everything is re-armed on `BOOT_COMPLETED`.
  Rules live in device-protected storage, so this also works before first
  unlock.
- Intervals are measured from the end of the previous run, or from when the rule
  was created if it has not run yet. Both anchors are persisted, so re-arming a
  schedule — which happens on every app launch — lands on the same instant
  rather than pushing a pending run further out.
- Runs missed while the box was powered off collapse into a single catch-up run
  shortly after the schedule is rebuilt; they are never replayed one per missed
  interval.

The shortest selectable interval is 5 minutes. Below roughly 15 minutes the
platform makes no guarantees, though on a plugged-in TV it generally holds.

## Relaunching

From Android 10, an app in the background is normally not allowed to start an
activity. Refresher handles this two ways:

1. With a privileged backend (Shizuku or root) it runs `am start` as the shell
   user, which is exempt from the restriction entirely. This is the reliable path.
2. Otherwise it calls `startActivity` and relies on the "display over other
   apps" exemption. Without that permission the system accepts the call and
   silently drops it — the app warns you when it detects this combination.

`skipWhileScreenOn` defers a run while someone is watching; `wakeScreen` turns
the display on for the relaunch (off by default, so the TV stays asleep).

## Per-rule options

- Target app and restart interval (5 min → 24 h)
- Relaunch after killing, or kill only
- Settling delay between kill and relaunch (0–15 s)
- Wake the screen for the relaunch
- Skip the run while the screen is on
- Enable/disable individually, plus a global pause

The **Recent activity** panel records what each run actually did — the only
practical way to tell a working setup from a silently blocked one on a headless
box.

## Building

No Android Studio needed. Every push builds a debug APK in GitHub Actions;
download it from the workflow run's **Artifacts** section. Locally:

```
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## Installing on the TV

```
adb connect <tv-ip>:5555
adb install -r app-debug.apk
```

## One-time ADB setup

Also shown in the app under **Setup**.

**Shizuku (recommended, no root).** Install Shizuku on the TV, then after each
boot:

```
adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
```

Then open Refresher → Setup → Grant Shizuku permission.

**Device owner (survives reboots, no root).** On a box with no accounts added:

```
adb shell dpm set-device-owner com.thefactor1.taskskiller/.kill.AdminReceiver
```

**Permissions Android TV has no settings screen for:**

```
adb shell appops set com.thefactor1.taskskiller SYSTEM_ALERT_WINDOW allow
adb shell dumpsys deviceidle whitelist +com.thefactor1.taskskiller
```

## Caveats

- Killing a VPN client can drop connectivity for the relaunch window. If the VPN
  is configured as always-on with block-without-VPN, the box may have no network
  at all until it reconnects.
- Device-owner mode is exclusive — a box can only have one device owner, and
  removing it usually means a factory reset.
- Shizuku's privileges end at reboot and must be restarted over ADB.
- Some TV firmware ships its own aggressive task killer that can drop pending
  alarms. Opening the app re-arms every schedule.
