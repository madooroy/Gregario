# Your first ride with Gregario

## Once: set it up

Pick the setup that matches your tablet, then do **Gregario** (all setups).

### Peloton tablet (Bike or Bike+)

1. **Grupetto:** install and run [Grupetto](https://github.com/doudar/Grupetto) as usual. In its settings, switch on
   **DirCon**, and on a Bike+ **Bike+ ERG** too. Keep Grupetto running: Gregario rides through it. (On a Peloton Bike,
   ERG needs a SmartSpin2K; without one, Gregario shows the targets for you to follow with the knob.)
2. Install Gregario on the Peloton tablet (sideload it like Grupetto, for example with OpenPelo).
3. **With a SmartSpin2K as well:** set it up with its own guide, on the same Wi-Fi as the tablet. Gregario finds it and
   asks before the workout whether to ride through it. Keep Grupetto running with its Bluetooth on: the SmartSpin2K
   reads its power from Grupetto.

### Any other Android tablet (any bike with a SmartSpin2K)

1. **SmartSpin2K:** set it up with [its own guide](https://github.com/doudar/SmartSpin2k): fitted to the bike, on your
   Wi-Fi, and with a power source paired in its web page (a power meter, power pedals, or the bike's own Bluetooth
   power). Check it holds watts in its own app or web page first.
2. Put the tablet on **the same Wi-Fi** as the SmartSpin2K (not a guest network: those keep devices apart).
3. Install the Gregario APK (allow installs from your browser or file manager when Android asks).
   That's all: Gregario sees it isn't on a Peloton, uses the SmartSpin2K, and finds it on the Wi-Fi by itself.
   There is no Grupetto to install, and Gregario never asks for it.

### Gregario (all setups)

1. Open Gregario. Tap **Grant permission** so it can show its panel over other apps.
2. On Gregario's screen:
   - **intervals.icu users:** paste your API key (intervals.icu › Settings › Developer Settings) and tap
     **Save & test**. Your planned workouts appear on the panel each day. Nothing else to load.
   - **No intervals.icu:** type your FTP and tap **Save FTP**. Add workouts as below.

## Adding a workout file (.zwo, .mrc, .erg)

Download it on your phone. On Gregario's screen, switch on **Send to the bike** once. Then the panel's workout list
shows a **QR code**. Point the phone's camera at it, tap the link, choose the file. It appears on the panel under
**Your workouts**.

(Or on Gregario's screen: **Choose files…** for a file already on the tablet or a USB stick.)

## Riding

1. Open Gregario and tap **Open panel**, then **Ride** (or just go back to Peloton). The panel floats on top.
   "Bike connection" should say **Connected to Grupetto** (or **Connected to SmartSpin2K**).
2. Tap the workout. Start pedaling: the first target comes in.
3. Tap **Start**. The panel shows the target, the time left in each step and what's next.
4. **The knob is always yours.** Turn it (or bump it) and it's your resistance until that step ends; ERG comes back
   for the next one, or tap **ERG on**. **Pause** on the panel pauses.
5. At the end, rate how hard it felt and tap **Save**. It uploads to intervals.icu (and to Strava, if you connected your own Strava API application in the settings).

If something goes wrong (Grupetto stops answering, another app takes the bike), the workout pauses and says why.
Close the panel from Gregario's screen or its notification; that hands the bike back (to Grupetto, or a SmartSpin2K
back to free riding).

## If it doesn't connect

The panel's card says what's wrong and what to do. The usual causes:

- **"Gregario needs Grupetto" / "Grupetto isn't answering"** (Peloton only): install Grupetto, open it and switch
  **DirCon** on. Gregario connects by itself once it's on.
- **"Looking for a SmartSpin2K"**: it's off, on another Wi-Fi, or the Wi-Fi keeps devices apart (guest network,
  "client isolation", or a mesh satellite on its own network). Put both on the same main Wi-Fi. Still not found?
  Type the SmartSpin2K's IP address (from your router, or the SmartSpin2K app) in Gregario's settings.
- **"SmartSpin2K: no power yet"**: the SmartSpin2K has no power reading. On a Peloton, Grupetto must be running with
  Bluetooth on. Elsewhere, pair your power meter in the SmartSpin2K's web page.
- **Another app has the bike** (Zwift, a second Gregario): close or disconnect it; only one app can hold ERG.

