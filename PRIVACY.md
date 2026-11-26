# Gregario privacy policy

Gregario is a free, open-source app (MIT License) that runs on your bike's tablet. It has no server of its own, no
account, no analytics and no ads.

**What it keeps, on the tablet only:** your settings (riders, FTP, the bike to ride through), the sign-ins you give it
for intervals.icu and Strava, your rides waiting to upload, and a log of each recent ride for troubleshooting (what Gregario
sent to the bike and what the bike reported, second by second; kept in Gregario's own folder on the tablet,
Android/data/com.madooroy.gregario/files/ride-logs, until newer rides replace it). Uninstalling Gregario deletes all of it.

**"Send to the bike" page:** while the workout list is showing, Gregario runs a small web page on your Wi-Fi (the
address and QR code on the panel) so a phone or computer on the same network can send it workout files. It only
accepts workout files, never serves anything from the tablet, and stops when a workout is opened.

**What it sends, and only where you connect it:**
- **intervals.icu** (when connected): it reads your planned workouts for today and your FTP, and uploads each ride
  (a FIT file) with its name, description, RPE and the workout's steps as intervals.
- **Strava** (when connected): it uploads each ride (the same FIT file) with its name and description.
- **Your bike** (Grupetto on the tablet, or a SmartSpin2K on your Wi-Fi): target power, to control the resistance.
- **Apps you choose:** "Share ride file" on the finish screen hands that ride's FIT file to the app you pick.

Nothing is sent anywhere else, and nothing is shared with or sold to anyone. You can disconnect intervals.icu or
Strava in Gregario's settings, or from the intervals.icu and Strava websites, at any time.

Questions: open an issue at https://github.com/madooroy/Gregario/issues.

Last updated: November 26, 2026.
