# Gregario changes

Release when "Next release" has 5–10 items: bump versionName/versionCode in app/build.gradle, copy the list into
docs/release-notes.md, then push the tag (vX.Y.Z-test, then vX.Y.Z once ridden).

## Next release

1. Update notice reads "New version 0.1.2 available (test build)", not "0.1.2-test … (test build)".
2. The intervals.icu sign-in page clears its web files when it closes (about 40 MB on the Bike+).
3. Normalized power, TSS and IF stay right when the bike data comes in late (a late reading counted as one second).
4. A long free ride is no longer labelled an FTP test (only a 20-minute free-ride effort after a warm-up is).
5. Sharing the ride file twice in a row no longer removes the first copy before the receiving app has read it.

## 0.1.1-test (Nov 27, 2026)

- About & updates opens in Gregario: version, update status, Check now. It did nothing on a tablet with no browser.

## 0.1.0-test (Nov 27, 2026)

- First signed release: Peloton (Grupetto) and SmartSpin2K, intervals.icu and Strava, update check.
