# Gregario

*A gregario is the rider who works for the team leader.*

Structured workouts on Peloton bikes, in a floating panel over whatever you're watching. Gregario is its own app on
the bike's tablet. It connects as a standard FTMS trainer app (the way Zwift does) over DirCon, either to
[Grupetto](https://github.com/doudar/Grupetto) on the tablet, which holds the watts with the Bike+ motor, or to a
[SmartSpin2K](https://github.com/doudar/SmartSpin2k) on your Wi-Fi, which holds them by turning the knob.

- Today's workout from intervals.icu, or .zwo/.mrc/.erg files (file picker, Open with, or a QR code from your phone)
- ERG targets with ranges, ramps, a one-second lead into harder steps, and Easier / Harder buttons
- The knob is yours: turn it and it's your resistance until the step ends, then ERG comes back
- A .fit file of every ride, uploaded to intervals.icu with the workout's steps as intervals
- Several riders on one bike

<p align="center">
<img width="200" alt="An effort at 110% FTP" src="docs/images/panel-effort.png">
<img width="200" alt="A range step with the knob" src="docs/images/panel-knob-range.png">
<img width="200" alt="Details page" src="docs/images/panel-details.png">
</p>

**Needs:** Grupetto running on the bike's tablet, with DirCon switched on.

**Works best with [intervals.icu](https://intervals.icu):** today's planned workout and your FTP come from there, and
every ride goes back with its steps. Everything else is extra: without intervals.icu, ride .zwo/.mrc/.erg files at
an FTP you type in. Strava upload is optional and needs your own Strava API application (which Strava now only
allows on a paid subscription); otherwise, upload the saved .fit file to Strava yourself.

| Bike | ERG |
|---|---|
| Peloton Bike+ | Through Grupetto (switch on Bike+ ERG), or with a SmartSpin2K |
| Peloton Bike | With a SmartSpin2K. Without one, Gregario shows each target for you to follow with the knob |

- First ride: [docs/FirstRide.md](docs/FirstRide.md)
- What it does, how it works, and a map of the code: [docs/Gregario.md](docs/Gregario.md)

App ID `com.madooroy.gregario`. Build in Android Studio's Terminal with `.\gradlew installDebug`.

MIT License: see [LICENSE](LICENSE).

***Unofficial and use at your own risk: Gregario changes the resistance of the bike you are riding. It is not
affiliated with Peloton.***
