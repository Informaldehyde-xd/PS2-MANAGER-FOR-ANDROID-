# PS2 Manager — Build It With Just Your Phone (No PC)

You'll use GitHub's free cloud build service to turn this code into an installable
app, entirely from your phone's browser. Takes about 15–20 minutes the first time.

## Step 1 — Create a free GitHub account
1. On your phone, open a browser and go to https://github.com/signup
2. Create a free account (just an email + password).

## Step 2 — Create a new repository
1. Go to https://github.com/new
2. Name it `PS2Manager` (Public or Private, either is fine).
3. Leave everything else default, tap **Create repository**.

## Step 3 — Upload the project files
1. On the new repo's page, tap **"Add file" → "Upload files"**.
   (If your browser shows a cramped mobile layout, tap the "..." menu or switch
   your browser to "Desktop site" mode — it makes this screen easier to use.)
2. First unzip `PS2Manager.zip` using your phone's file manager (most phones can
   unzip natively — tap the zip file → Extract/Unzip).
3. Go back to the GitHub upload screen and drag/select **all the unzipped files
   and folders** into it. This needs to include the hidden `.github` folder —
   if your file manager hides it, enable "show hidden files" in its settings
   first (every phone's Files app has this option somewhere in settings).
4. Scroll down, tap **Commit changes**.

## Step 4 — Let it build automatically
1. Uploading the files automatically triggers the build. Tap the **"Actions"**
   tab at the top of your repo.
2. You'll see a build running (a yellow dot). Tap it to watch progress.
3. After a few minutes it turns into a green checkmark — the build succeeded.
   (If it turns red, take a screenshot of the error and send it to me.)

## Step 5 — Download the APK
1. Still on that build's page, scroll down to **"Artifacts"**.
2. Tap **PS2Manager-debug-apk** to download it (it'll download as a `.zip`
   containing the `.apk` — unzip it the same way as before).

## Step 6 — Install it on your phone
1. Open the downloaded `app-debug.apk` file from your Files/Downloads app.
2. Android will ask to allow installing from this source — tap **Settings**,
   allow it for your browser/file manager, then go back and tap **Install**.
3. Done — "PS2 Manager" now appears as a real app on your phone.

## After that: making changes
Any time I update the code for you, I'll give you an updated zip. Just repeat
Step 3 (upload the changed files into the same repo, tap Commit) and Steps 4–6
again — GitHub rebuilds it automatically every time you upload.

## Using the app
Same as before: plug your USB/HDD drive into your phone with an OTG adapter,
open PS2 Manager, pick the drive's folder, let it scan and match titles, then
tap **Apply** (or **Apply for All**) to rename files and save cover art.
