# PHEV Link — kits

Three ways to package PHEV Link for another Outlander PHEV owner, from "hand it over and
it works" to "a maker's telematics box." They share the same app and the same reverse‑engineered
protocol; they differ in how much hardware lives with the car and how much risk you take on if you
sell them.

Prices below are **ballpark parts cost** (EUR, and NOK at ≈ ×11.7) to help you price a kit — check
current retail with import/VAT before you commit to a number. "Retail" is a suggestion that leaves
room for your time and a modest margin; set your own.

| | Tier 1 — Remote | Tier 2 — Home Bridge | Tier 3 — In‑car telematics |
|---|---|---|---|
| What it does | App + car, phone nearby | Control from anywhere, car parked at home | Live data anywhere the car goes |
| Stays in the car? | No | No (lives at home on mains) | Yes |
| Car‑battery risk | None | None (car Wi‑Fi only, `interval` mode) | Real — managed by the power stack |
| Needs new code? | No — ships today | No — ships today | **Yes** — bridge OBD‑over‑USB is not built yet |
| Parts cost | ~€60 / ~700 NOK | ~€55 / ~640 NOK | ~€185 / ~2,150 NOK + SIM |
| Suggested retail | €89–99 | €99–129 | €299–349 + SIM |
| Who it's for | Any owner | Owner who wants remote control | A maker who wants road telemetry |
| Liability to sell | Lowest | Low | Highest |

---

## Tier 1 — "Remote" kit

The one you can actually sell tomorrow. A reliable adapter, the app, and a setup card. Nothing stays
plugged in, nothing modifies the car, no battery risk. Everything else is a step up in capability and
in what you take on.

### Bill of materials
| Part | Suggested | Approx cost | Notes |
|---|---|---|---|
| OBD adapter | **OBDLink LX** (or MX+) | €60 / MX+ €100 | Genuine STN chip. See "Why not a clone" below. |
| Setup card | Laser‑engraved or printed | ~€2 (your laser) | Content in `kit-setup-card.md`; QR to the release. |
| Packaging | Small box / tin | ~€2 | Optional. |

**In the box:** adapter, setup card, a line of text pointing at the APK (QR → GitHub release).

**No assembly.** The buyer scans the card, installs the APK, sets the car Wi‑Fi, registers their
phone (card walks them through it), and pairs the adapter for the OBD extras.

**Suggested retail €89–99** with the LX; €139 if you build it around the MX+.

### Why not a clone
The per‑cell battery read the app does (OBD `21 02` / `21 03`) is ISO‑TP **multi‑frame with flow
control** — the exact thing cheap ELM327 clones get wrong. A genuine STN‑based OBDLink handles it; a
€8 clone will connect, then hand back garbage or nothing on the heavy reads. If you put your name on a
kit, the adapter is the one part not to cheap out on.

---

## Tier 2 — "Home Bridge" kit

A small computer that lives **at home** on mains power, within Wi‑Fi range of where the car parks. It
keeps in touch with the car and lets the phone reach it from anywhere over Tailscale. Because it runs
on house power it never touches the car's 12 V; the only car‑side draw is the car's own Wi‑Fi module,
and the bridge's default `interval` mode (a status check every ~10 min) keeps that gentle. Ships with
what's built today.

### Bill of materials
| Part | Suggested | Approx cost | Notes |
|---|---|---|---|
| Computer | Raspberry Pi Zero 2 W (or 3A+) | €18 / 3A+ €30 | Zero 2 W is plenty for the bridge. |
| Case | Official / generic | €8 | |
| SD card | 32 GB A1 | €8 | Pre‑flashed by you. |
| PSU | Official 5 V supply | €10 | Undervoltage = flaky Wi‑Fi; don't skimp. |
| Second radio | USB Wi‑Fi dongle | €10 | Linux‑friendly chipset (RTL8188EU / MT7601). One radio talks to the car, one to home. |

**Parts ~€54 / ~630 NOK. Suggested retail €99–129** (pre‑flashed + set up is most of the value).

### Assembly (you, before shipping)
1. Flash Raspberry Pi OS Lite, enable SSH, set the Wi-Fi country.
2. Install the bridge: `sudo phev-bridge/install.sh phev-bridge/phev-bridge.jar` (from `phev-bridge-pi.tar.gz`).
3. Install Tailscale, sign into **your own tailnet or the buyer's** (decide this — see below).
4. Test against the emulator (`emulator=true` in `/etc/phev-bridge.properties`) before it ever sees a car. Leaving it
   on is fine: the app's setup switches the bridge to the real car.

The **buyer** then does the rest from the app, with no computer: first-run setup asks for the car's Wi-Fi card, then
offers **I have a PHEV Link bridge** (also in Settings → Set up bridge), which finds the bridge, pairs by
re-plugging it, joins the bridge to their car's Wi-Fi and walks them through the car's registration beeps. Their home
link must be Ethernet or the USB Wi-Fi adapter — the built-in Wi-Fi goes to the car (the app says so if it can't).

**Decide who owns the tailnet.** Simplest for the buyer: they install Tailscale on their phone and Pi,
on their own account, and you hand over a configured SD image + instructions. Don't leave the box on
*your* tailnet unless you want to be their sysadmin forever.

**Registration note:** the bridge counts as one of the car's **two** remembered phones. Buyer + bridge
= both slots. Put that on the card so nobody's surprised.

---

## Tier 3 — "In‑car telematics" kit

The maker tier. A Pi rides **in the car** with a USB OBD adapter and a cellular stick, so you get live
battery/charger data anywhere — not just in the driveway. This is the version that runs off the 12 V,
so the whole point of the build is the power stack that keeps it from flattening the car.

> **Not shippable as‑is.** Two honest gaps: (1) the bridge does **not** read OBD over USB yet — that's
> real new code (an `ObdSerial` transport feeding the existing `ObdClient`, plus bridge telemetry
> fields for cells/charger/12 V). (2) The sell‑it checklist below bites hardest here. Treat this as a
> spec to build toward, not a box to assemble this week.

### Bill of materials
| Part | Suggested | Approx cost | Notes |
|---|---|---|---|
| Computer | Raspberry Pi 3A+ (or Zero 2 W) | €30 | Needs USB for OBD + modem. |
| OBD adapter | **OBDLink SX (USB)** | €35 | Wired — no Bluetooth to go flaky on a headless box. |
| Cellular | USB LTE modem (Huawei E3372) or SIM7600/EG25 HAT | €35 | Data‑only IoT SIM, ~€5–10/mo. |
| Supervisor | ESP32 dev board | €8 | Sleeps at µA; wakes/boots the Pi; reads 12 V. |
| 12 V sense | Resistor divider + ADS1115 (or ESP32 ADC) | €4 | Feeds the voltage number up to the app. |
| Power in | 12 V→5 V buck (≥3 A) | €6 | |
| Hard cutoff | Low‑voltage disconnect module / Victron Battery Protect | €12–40 | The part you actually trust — see below. |
| Power switch | Relay or MOSFET module | €5 | Supervisor cuts Pi **and** OBD together. |
| Buffer | UPS HAT + 2× 18650 (or small LiFePO4) | €25 | Clean shutdown + rides short car‑off gaps. |
| OBD tap | Fused OBD‑port Y‑splitter / extension | €15 | Keeps the port usable; adds a fuse. |
| Enclosure | Vented box | €12 | |

**Parts ~€185 / ~2,150 NOK + SIM. Suggested retail €299–349 + SIM.** Niche, but there's a real
community that would pay for plug‑and‑play road telemetry.

### The power stack (why it's safe), bottom‑up
1. **Hardware low‑voltage cutoff.** A module that *physically* disconnects at ~12.0–12.2 V (~50 % on a
   resting battery). Software can hang; this can't. It alone makes "the box flattened my car"
   impossible.
2. **ESP32 supervisor keeps the Pi off most of the time.** The Pi can't truly deep‑sleep (~0.2–0.5 A
   even halted → a couple of days to trouble on a ~40 Ah aux battery). The ESP32 stays awake at µA and
   boots the Pi only on a schedule (say 2 min/hour) or on an event — CAN traffic (car woke) or a phone
   ping. ~30× less average draw, so layer 1 almost never trips.
3. **Powerbank/UPS as a reservoir.** It stores energy, doesn't add it — so recharge it **only** when
   the car's DC‑DC is running (12 V reads >~13.2 V = car on or charging). Then parked time spends the
   buffer, not the car battery.

**Kill the OBD dongle with the Pi.** Many adapters hold the CAN bus awake, which stops the car's own
modules from sleeping — a bigger drain than the Pi. Switch the OBD port's 12 V through the same
supervisor relay so both die together.

**App side (small, buildable now):** the Pi reports measured 12 V, the app shows it, and a `LOW_12V`
alert rides the same AlertEngine the theft/charge‑fail alerts already use. (When you give the go‑ahead,
I'll add the alert type + bridge field with logging from the first commit, per your "log it with the
fix" rule.)

---

## Selling it — the honest checklist

Not legal advice — confirm before you trade, especially via your ENK.

- **CE / RED (radio).** Anything with Wi‑Fi/BLE/LTE sold as a *product* in the EEA touches the Radio
  Equipment Directive. The Pi and OBDLink are already CE‑marked, so assembling certified modules is
  lower‑risk than building your own radio — but a unit sold as one product can still need a conformity
  assessment. Tier 1 (a certified adapter + a card) mostly sidesteps this.
- **Liability.** It plugs into a car and controls climate/lights. Clear disclaimers; keep locks out of
  scope (the protocol has no remote unlock anyway). Tier 1, which leaves nothing in the car, carries
  the least exposure; Tier 3 the most.
- **IP.** The protocol is reverse‑engineered. Routine for the owner community (phev2mqtt and friends do
  it openly); commercial sale is greyer re: Mitsubishi. The app is yours and open‑source, which helps.
- **The 2‑phone limit.** The car remembers two phones. A bridge takes one slot. Say so on the card.
- **Support & updates.** Every push to `main` builds a new APK. Decide whether buyers self‑update from
  GitHub (simplest) or you push images. Tier 3 with cellular means you *can* update remotely — and that
  you're now responsible when it breaks.

---

## Recommended first move

Sell **Tier 1** to prove there's demand: lowest cost, lowest liability, ready today, and your laser
turns the setup card into something that looks like a product. Keep Tier 2 as a pre‑flashed upsell for
anyone who asks "can I do it from work?". Build Tier 3 for yourself first, live with it through a Norwegian
winter, then decide if it's a product.
