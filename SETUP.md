# PS2 Manager — Setup Guide (no coding needed, just following steps)

This is a real Android app project. You won't write any code — you'll install one
free program, open this project, plug in your phone, and press a button.

## What this app does
1. You pick the folder on your USB/HDD (plugged into your phone via an OTG adapter).
2. It scans for `.iso` / `.bin` files and reads the PS2 Game ID from each filename.
3. It looks up the real game title and downloads box art from an archived
   community database of OPL Manager's own art/title catalog.
4. You tap **Apply** (or **Apply for All**) to rename each file to OPL's format
   (`GameID.Title.iso`) and save the cover art into an `ART` folder on the drive —
   exactly what OPL itself expects.

## Requirements
- A Windows, Mac, or Linux computer (to build the app once)
- An Android phone (Android 8.0 or newer) — **not iPhone**, this won't work on iOS
- A USB-C or micro-USB **OTG adapter** to plug your PS2 drive into your phone
- Free disk space (~10 GB) and about 30–45 minutes for the one-time setup

## Step 1 — Install Android Studio
1. Go to https://developer.android.com/studio and download Android Studio (it's free).
2. Install it with all default options. This includes everything needed to build
   Android apps — you don't need to install anything else separately.
3. Open Android Studio once so it finishes its first-time setup (it may download
   a few more components — just let it finish).

## Step 2 — Open this project
1. Unzip the `PS2Manager.zip` file I gave you, anywhere on your computer.
2. In Android Studio, choose **Open**, and select the unzipped `PS2Manager` folder.
3. Android Studio will "sync" the project (a progress bar at the bottom) —
   this downloads the pieces it needs. Just wait for it to finish. This can take
   a few minutes the first time.
4. If it shows a banner about the "Gradle wrapper" being missing, click the
   option it offers to fix/create it automatically. If there's no such banner,
   you're already good to go.

## Step 3 — Connect your phone
1. On your phone: Settings → About Phone → tap "Build Number" 7 times to unlock
   Developer Options.
2. Settings → Developer Options → turn on **USB Debugging**.
3. Plug your phone into your computer with a USB cable. Tap "Allow" on the
   phone if it asks about USB debugging.
4. In Android Studio, your phone's name should appear in the device dropdown
   near the top (it might take a few seconds).

## Step 4 — Run it
1. Click the green ▶ **Run** button in Android Studio (or Run → Run 'app').
2. Android Studio will build the app and install it directly onto your phone.
3. The app icon "PS2 Manager" will appear on your phone — it's now a real
   installed app, just like anything from the Play Store.

## Step 5 — Use it
1. Unplug your phone from the computer.
2. Plug your USB/HDD drive into your phone using the OTG adapter.
3. Open PS2 Manager, tap **Pick USB / HDD Folder**, and select your drive's
   folder (or the DVD/CD folder if your drive is already OPL-formatted).
4. It'll scan, look up titles online (needs internet), and show you what it found.
5. Tap **Apply** on any game, or **Apply for All** to do everything at once.

## Notes and honest limitations
- The title/art database is a community-preserved archive, not an official live
  service — it covers a large number of PS2 titles but won't have literally
  every game. Games with no match just won't get renamed automatically.
- Your phone needs internet access the first time it looks things up (results
  get cached afterward).
- If your phone doesn't support USB OTG file access for your specific drive
  enclosure, Android will simply not offer it in the folder picker — this is a
  hardware/driver limitation outside the app's control, common with some
  USB-to-SATA enclosures.

## If something goes wrong building it
Take a screenshot of the red error text in Android Studio and send it to me —
I'll tell you exactly what to change. This is completely normal for a first
build and almost always a one-line fix.
