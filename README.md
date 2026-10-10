# PHEV Link

A from-scratch replacement for Mitsubishi's **Outlander PHEV Remote Ctrl** app, built for the 2014–2017 Outlander PHEV
(2018+ cars use the same protocol family and are supported by the protocol layer, but less tested).

The main goal: **a connection that just works.**

| | Official app | PHEV Link |
|---|---|---|
| Joining the car's Wi-Fi | Asks Android with an SSID *pattern* → "Connect to device?" picker every time | Exact SSID + BSSID after a one-time pairing → Android connects silently, even in the background |
| Network binding | Binds the **whole app** to the car network; never notices when it drops → stuck until restart | Binds only the car socket; follows Wi-Fi lost/available; reconnects with backoff |
| Packets split over Wi-Fi | Dropped | Reassembled; garbage skipped and logged |
| Key out of sync | Silently counts errors | Re-synchronises from the car's hint, resends, asks for a new key if needed |
| Phone already on the car Wi-Fi | — | Detected and used directly |
| When things go wrong | — | Every step logged; share the log from Diagnostics |

## Features

- **Dashboard** — battery % with estimated electric range, charging state and "full at" time, plug, doors/boot/bonnet,
  lock state, lights, power switch, car clock.
- **Climate** — heat, cool or defrost for 10/20/30 minutes; **Smart** mode picks mode and duration from the outside
  temperature (MET Norway forecast). Handles "stopped by the car" (door opened / battery low) with a one-tap reset.
- **Departure timers** — "ready to leave at 07:30 on weekdays": 35 min before, the app checks the forecast and starts
  defrost/heat/cool just long enough, so the car is ready when you walk out. Mild morning? It skips and tells you.
- **One-tap everywhere** — home-screen widget (battery, charging, climate, preheat & refresh buttons), quick-settings tile
  ("Preheat"), and long-press app shortcuts (preheat, stop, flash lights, refresh).
- **Find my car** — flash the headlights.
- **Alerts** — charging complete, charging stopped early (power cut, loose cable), cabin ready, climate stopped by the car,
  car left unlocked, door left open, lights left on.
- **Background watch** — while the car charges or preconditions, the app checks in every few minutes and alerts you.
- **Charge now** — override the charge timer (experimental).
- **Car clock** kept in sync (the car's own timers depend on it), like the official app does.
- **Diagnostics** — live log of every connection step and packet (with raw hex), raw register table, raw register writes
  for experiments, previous-session log, one-tap share.
- **Vehicle settings** — the car's own customisable functions over Wi-Fi, like in the official app: lights (auto-cutout,
  headlights on exit, lights on remote unlock), locks (auto relock, unlock mode, auto unlock), lock/unlock beeps and horn
  answerback, wipers, turn signals, power windows and A/C behaviour; each change is confirmed by the car and saved.
  Also lists setting slots the car reports that Mitsubishi's app never names.
- **OBD tab (ELM327 adapter)** — Bluetooth Classic, Bluetooth LE or Wi-Fi adapters, read-only:
  - **drive battery health** from the battery computer (BMU): charge level, capacity in Ah and % of new (38 Ah reference
    before model year 2017, 40 Ah from 2017, taken from the VIN), stored energy, pack voltage, highest/lowest cell and the
    cell difference, charge counters;
  - **live values** from all control units that answer (standard OBD-II plus documented Mitsubishi values, each marked
    standard / owner-documented / experimental);
  - **fault codes** from all control units with a plain-language explanation of 3,500 codes (what it means, how serious,
    can I drive, likely causes, what to check first; 122 written by hand for this car), code lookup, and clearing of
    engine/emission codes behind a confirmation;
  - **guided live tests** you can do on the spot: full fault scan, 12 V battery, 12 V charging in READY (DC/DC), drive
    battery capacity, cell balance, EU-kontroll readiness, charging check, engine warm-up/thermostat, fuel trims and
    front/rear tyre match — each with step-by-step instructions and a pass/warning/fail verdict with explanation
    ([design, limits and sources](docs/live-tests.md));
  - CSV recording, adapter details, raw battery data and a terminal (non-read commands need a confirmation).
- **Tips** — 26 well-known problems of the 2014–2017 Outlander PHEV (12 V battery, EV system warnings, capacity loss,
  brake disc rust, charging, …) with why they happen and what to do, and a guide to all 24 beeps and sounds: which ones
  this app, the car's menu, a switch or only a dealer can change. With sources.
- **Languages** — English, Norsk (bokmål), Svenska, Dansk and Deutsch (Settings → Language).
- **Bridge (optional)** — a small always-on computer near the car (e.g. Raspberry Pi) keeps the link and lets the phone
  reach the car **from anywhere** through a VPN such as Tailscale. Includes a web control page and push alerts via ntfy.

## Install

1. On your phone, open the latest release of this repository (GitHub → Releases) and download `PHEV-Link-*.apk`.
2. Open it and allow installing from your browser/files app when Android asks.
3. Updates: install a newer APK over the old one — all builds share the same signing key, settings are kept.

Every push to `main` builds a new release automatically (GitHub Actions).

## First setup

The app walks you through it:

1. **Car Wi-Fi** — SSID (`REMOTExxxxxx`) and password from the card that came with the keys.
2. **Pair with car** (recommended, car nearby) — pick the car from Android's list once. From then on Android joins the
   car's Wi-Fi without asking, also for the widget, tile and departure timers.
3. **Connect.** If it stops at "Saying hello to the car" and then fails, this phone isn't registered with the car.
   Phones that used the official app usually are.

### Registering the phone (official procedure)

1. Sit in the car with the key and close the driver's door, power switch **OFF**.
2. Press the power switch once to **ACC** (don't press the brake).
3. Within 10 seconds press **LOCK** and **UNLOCK** on the key alternately — 10 presses in total, starting with LOCK.
4. The car beeps: 1 beep = no phones registered, 2 = one, 3 = two (memory full). It waits up to 10 minutes.
   In the app: Settings → **Register**.
5. Switch the power **OFF** to finish. The car may restart its Wi-Fi; the app reconnects by itself.

The car remembers at most 2 phones. **Memory full / start over:** close the driver's door, hazard lights **ON**, enter
registration mode (steps 2–3), then within 5 minutes press LOCK and UNLOCK alternately 20 times (starting with LOCK).
One beep = every phone deleted. This also resets the car's Wi-Fi name and password to the card and clears its timers.

Note: Android uses a per-network random MAC address, and the car registers that MAC. If you ever "forget" the car's
network in Android's Wi-Fi settings, you may need to register again.

## The bridge (control from anywhere)

The car only talks Wi-Fi within ~10–20 m. The bridge sits within that range at home, keeps in touch with the car, and
offers an HTTP API + web page. The app uses it automatically when it has fresh data about the car.

What you need: a Raspberry Pi (Zero 2 W, 3, 4, 5) or any small Linux box with Wi-Fi, within range of where the car parks,
plus a second connection to your home network (Ethernet, or a USB Wi-Fi adapter).

```bash
# 1. Join the car's Wi-Fi on the Pi's built-in Wi-Fi, without making it the default route
sudo nmcli dev wifi connect REMOTExxxxxx password 'xxxxxxxx' ifname wlan0
sudo nmcli con modify REMOTExxxxxx ipv4.never-default yes ipv6.method disabled connection.autoconnect yes
#    (home network stays on eth0 / the USB adapter)

# 2. Install the bridge (download phev-bridge.jar from the same release as the APK)
git clone https://github.com/Anwo91/phev-remote-plus && cd phev-remote-plus/bridge/deploy
sudo ./install.sh ~/phev-bridge.jar          # prints your API token

# 3. Register the bridge with the car (it's a new "phone" for the car):
#    put the car in registration mode (above), then
curl -X POST -H "Authorization: Bearer <token>" -d '{"action":"register"}' http://localhost:8099/api/command

# 4. Remote access: install Tailscale on the Pi and the phone (https://tailscale.com/download)
#    then in the app: Settings → How to reach the car → Bridge address  http://<pi-tailscale-ip>:8099  + token
```

Try it before going near the car: set `emulator=true` in `/etc/phev-bridge.properties` to run against a simulated car.

Push alerts without the phone being near the car: create a private topic on [ntfy](https://ntfy.sh), install the ntfy app,
and set `ntfy.url=https://ntfy.sh/<your-topic>` in the bridge config.

**Modes:** `interval` (default) connects every 10 minutes for a status check and on demand for commands;
`persistent` stays connected all the time. Interval is gentler on the car's 12 V battery — a parked car whose Wi-Fi
module never rests may drain it over long periods.

**Security:** never expose port 8099 to the internet directly — use Tailscale/WireGuard. Anyone with the token can
control the car's climate and lights (not locks: the protocol has no remote unlock).

### Bridge API

| Method | Path | |
|---|---|---|
| GET | `/` | Control page (asks for the token once) |
| GET | `/api/status` | `{link, car, bridge}` JSON |
| GET | `/api/events` | Server-sent events, a `status` event per change |
| POST | `/api/command` | `{"action":"climate","mode":"HEAT","minutes":20}` · `climate_off` · `headlights`/`parking_lights` + `on` · `locate` · `refresh` · `charge_now` · `ack_climate` · `register` · `unregister` · `sync_clock` · `raw` (+ `register`, `data` hex) |
| POST | `/api/connect`, `/api/disconnect` | |
| GET | `/api/log?n=300` | Diagnostic log |

Auth: `Authorization: Bearer <token>` or `?token=<token>`.

## OBD adapter

Any ELM327-compatible adapter works for standard data and fault codes. The drive-battery data needs an adapter that
supports custom CAN flow control (`ATFCSH`); many cheap "v1.5/v2.1" clones don't — the app tells you if yours doesn't.
Known good: OBDLink LX / MX+ / CX, Vgate iCar 2 / iCar Pro, Konnwei KW902, Veepeak. Pair classic Bluetooth adapters in
the phone's Bluetooth settings first; Bluetooth LE adapters are found with a scan in the app. The OBD socket is under the
steering wheel. Unplug the adapter when the car stands for days (it draws from the 12 V battery).

The OBD side never writes to the car except "Clear codes" (OBD-II mode 04), which asks first. The terminal asks before
sending anything that isn't a read request.

## Troubleshooting

- **"Looking for the car…" forever** — out of range, or the car's Wi-Fi is asleep. After several days parked it sleeps;
  using the key (lock/unlock) usually wakes it.
- **"Can't join car Wi-Fi"** — wrong password, or you declined Android's prompt. Fix the password and tap the status
  chip → *Try again now*.
- **Stops at "Saying hello"** — phone not registered (see above).
- **Drops when the car is switched on** — the car turns its Wi-Fi off a little while after the power switch goes ON
  (2014–2017 cars always; 2018+ unless *Keep the car's Wi-Fi on* is set in Settings → Staying connected). ACC is fine.
- **Heat/cool does nothing** — remote climate only runs with the power switch OFF and not while quick charging; the car
  also refuses with a door open, a low or very cold drive battery. The app now refuses up front when the car reports
  ACC/ON, and reports a failure (with the car's state in the log) if the car acks but never starts.
- **OBD tool connects but never answers** — scan tools with their own app (Autel "Maxi…", Launch, ThinkCar…) don't
  speak ELM327 and can't be used; use an ELM327-compatible adapter (see above).
- **Anything else** — Diagnostics → Share. The log shows each step with timestamps and raw packets.

## How it's built

```
core/     Kotlin protocol engine (no Android), car emulator, 30+ tests
app/      Android app (Jetpack Compose, Glance widget, foreground service)
bridge/   Bridge server (JVM, no dependencies beyond core) + web page
```

The protocol engine is "sans-IO": a pure state machine fed with bytes and clock ticks, so it is tested end-to-end against
a simulated car that injects the faults real Wi-Fi produces (fragmented packets, dropped writes, key desync, silent car,
mid-session re-keys, reconnects).

Protocol summary: TCP to `192.168.8.46:8080` on the car's access point; packets `[type][len][ack][register][data…][sum]`
XOR-encoded with a rolling key derived from the car's start packet (`0x4e` on 2014–17, `0x5e` on 2018+). Registers carry
the state (`0x1d` battery, `0x1f` charging, `0x24` doors/lock, `0x10`/`0x1c` climate…) and accept commands
(`0x02`+`0x04` climate on 2014–17, `0x1b` on 2018+, `0x0a`/`0x0b` lights, `0x06` refresh, `0x05` clock…).
Details were cross-checked against the official app (v3.1.6): e.g. climate writes echo the car's own timer registers
`0x22`/`0x19` so scheduled timers are never overwritten.

## Credits

- [phev2mqtt](https://github.com/buxtronix/phev2mqtt) by Ben Buxton and contributors — protocol notes and the XOR key
  algorithm (reverse engineered by @zivillian).
- [phev-remote](https://github.com/phev-remote) by Jamie Nuttall — the original open implementation.
- Weather: [MET Norway](https://api.met.no/) Locationforecast (CC BY 4.0).
- Fault-code titles: [AndrOBD](https://github.com/fr3ts0n/AndrOBD) (GPL-3.0); Outlander signal definitions:
  [OBDb](https://github.com/OBDb) (CC BY-SA 4.0); drive-battery values: owners on myoutlanderphev.com. Tips research
  sources are listed with each tip. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Not affiliated with or endorsed by Mitsubishi Motors. Use at your own risk.

## License

GPL-3.0-or-later (it ports parts of phev2mqtt, which is GPL-3.0). See `LICENSE`.
