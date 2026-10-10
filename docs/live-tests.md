# Live ELM327 tests for the 2014-2017 Outlander PHEV (design)

Researched 2026-10-10. Uses only what the app reads today: mode 01 PIDs (`Mode01.kt`, value ids `obd.XX`,
other ECUs `obd.XX@7E9`), adapter voltage (`adapter.voltage`, ATRV), `PhevSignals.kt` ids (`bmu.*`, `obc.*`,
`motor.*`, `ecm.*`, `cluster.*`, `ac.*`, `abs.*`, `awc.*`), derived ids (`bmu.soh_pct`, `bmu.cell_spread_mv`,
`bmu.energy_kwh`) and `ObdClient.scanDtcs(includeExtended)`.

Confidence of the inputs (show it in the UI):
- `adapter.voltage`, `obd.*`: standard (VERIFIED/J1979).
- `bmu.soc`, `bmu.pack_v`, `bmu.capacity_ah`, `bmu.remaining_ah`, `obc.*`, `motor.*`: COMMUNITY (forum, one source).
- `bmu.cell_max_v/min_v/_no`, `bmu.soc2`: EXPERIMENTAL (inferred from one example reply).
- `ecm.*`, `cluster.*`, `ac.*`, `abs.*`, `awc.*`: EXPERIMENTAL (OBDb, non-PHEV Outlander). Tests must work without them.

Threshold sources are given per test. Where no published Outlander PHEV value exists, the threshold is an
engineering estimate and is marked **[eng]**; keep those in one constants file so they can be tuned from user logs.

General rules for every test
- Abort and show "Car not answering" if `ObdState.carReachable` is false for > 10 s.
- Store every run (timestamp, odometer `obd.A6` or `cluster.odometer` if available, raw samples) so trends can be shown.
- Use the median of the samples for verdicts; report min/max too. Ignore samples older than 2x their poll period.
- "Power ON (not READY)" = press the power button twice without the brake pedal (OFF -> ACC -> ON). "READY" = brake + power button.

---------------------------------------------------------------------------------------------------------------

## How to make the engine run (needed by `engine-warmup`, `fuel-trims`; helps `inspection-readiness`)

1. Outdoors only (exhaust). Bonnet closed, parking brake on, selector in P, car in READY.
2. Press and hold the **CHARGE (CHRG)** button for about 10 s. Owners use this to force the engine on, e.g. for an
   emissions test (Richi FAQ section X). A short press also selects Battery Charge mode, which starts the engine
   unless the drive battery is nearly full.
3. Stop the engine by switching the car off with the power button.
4. Alternatives: Save/Charge while driving; a very low drive battery with the power switch ON (owner's manual p. 2-04
   says the engine then starts by itself); heater on high in cold weather (owners report the engine helps when the
   heat demand exceeds about 5 kW) - not reliable.
5. The app should confirm the engine runs: `obd.0C` > 500 rpm for 3 consecutive samples, else show
   "The engine did not start. Hold CHARGE for 10 seconds in P with the car in READY."

Sources: Richi Jennings Outlander PHEV FAQ, sections X and Q.3 - https://www.richi.uk/p/mitsubishi-outlander-phev-faq.html ;
Mitsubishi UK 14MY owner's manual p. 2-03/2-04/2-12 - https://mitsubishi-motors.co.uk/wp-content/uploads/2021/06/14MY-Outlander-PHEV-Owners-Manual.pdf

---------------------------------------------------------------------------------------------------------------

## 1. `twelve-volt-health` - 12 V battery at rest and when switched on

Detects: discharged or worn 12 V AGM battery; parasitic drain (repeat after a parked weekend).

Preconditions
- Car OFF for at least 2 h (ideally overnight), not charging, no READY in the last 2 h (ask the user; if the Wi-Fi
  link is up, check `CarState` for charging/climate). Doors closed, nothing plugged into the 12 V sockets.
- Adapter plugged in (OBD pin 16 is permanent +12 V, ATRV works with the car off).

Steps
1. "Leave the car switched off. Measuring the resting voltage (30 s)." Sample `adapter.voltage` every 2 s for 30 s -> `V_rest` (median).
2. "Now press the power button twice WITHOUT the brake pedal (power ON, not READY). Wait." Wait 20 s, then sample
   `adapter.voltage` every 2 s for 30 s -> `V_on` (median) and `V_on_min`. Also read, if available, `ecm.battery_v`,
   `cluster.voltage`, `awc.accessory_v`, `obd.42` (control module voltage) -> `V_ecu`.
3. Offer to continue with `dcdc-output`.

Thresholds (resting, AGM, from the Discover Battery OCV table: 100% > 12.80 V, 75% > 12.60 V, 50% > 12.30 V, 25% > 12.00 V)
| `V_rest` | Verdict |
|---|---|
| >= 12.60 V | PASS "The 12 V battery is well charged." |
| 12.30-12.59 V | WARN "The 12 V battery is only about half charged." |
| 12.00-12.29 V | FAIL "The 12 V battery is low." |
| < 12.00 V | FAIL "The 12 V battery is very low. Charge it now; the car may soon refuse to start." |
- If `V_rest` is within 0.10 V of a limit, add "(borderline; adapters can be off by 0.1-0.2 V)" **[eng]**.
- If `V_rest` > 13.0 V: "The battery still has surface charge or something is charging it. Test again after 2 hours with the car off." (inconclusive) **[eng]**.

Thresholds (power ON, ~5-15 A load from control units, pumps, fan) **[eng]**
| `V_on` median | Verdict |
|---|---|
| >= 12.20 V | PASS |
| 11.90-12.19 V | WARN "The battery voltage sags a lot under a small load." |
| < 11.90 V | FAIL "The battery cannot hold its voltage under load; it is probably worn or deeply discharged." |
- If `|V_ecu - V_on| > 0.5 V` (any experimental ECU voltage): note "Voltage at the control unit differs from the socket; check battery terminals and earth straps, or calibrate the adapter." **[eng]**

Next steps
- WARN/FAIL: put the car in READY for 15-30 min (DC/DC charges the battery), rest 2 h, retest. If still WARN/FAIL ->
  charge with an AGM-capable charger, retest next morning; if it drops again -> replace with a vented AGM
  (original size S46B24L, ~45 Ah) or look for a drain (unplug OBD dongle, dash cam).
- Link tips: `weak-12v-battery`, `obd-dongle-drain`.

Sources
- Discover Battery OCV vs SoC (AGM): https://discoverbattery.com/support/learning-center/battery-101/how-can-you-tell-if-a-battery-is-fully-charged
- Owner's manual p. 3-04 (12 V charged only in READY or while the drive battery charges): https://mitsubishi-motors.co.uk/wp-content/uploads/2021/06/14MY-Outlander-PHEV-Owners-Manual.pdf
- Battery type (45 Ah AGM S46B24L): https://www.tayna.co.uk/car-batteries/aux/hj-s46b24l-s/

---------------------------------------------------------------------------------------------------------------

## 2. `dcdc-output` - 12 V supply in READY (DC/DC converter)

Detects: DC/DC converter not charging the 12 V battery; over-voltage; bad connection.

Preconditions: car in READY, P, climate OFF, 12 V test done or battery not flat.

Steps
1. "Put the car in READY (brake + power button). Keep climate off." Wait 30 s.
2. Sample `adapter.voltage` every 2 s for 90 s -> `V_ready` (median), `V_ready_min`, `V_ready_max`.
3. "Now switch on dipped headlights, blower on full and the rear window heater." Wait 10 s, sample 60 s -> `V_load`.
4. Optional extra signals: `ecm.battery_v`, `cluster.voltage`, `obd.42`.

Thresholds **[eng]** - no published Outlander PHEV DC/DC set point was found. Basis: a working charging source must
hold the 12 V bus clearly above the battery's own resting voltage (~12.6-12.9 V for a charged AGM); common
charging-system targets are 13.5-14.8 V; AGM batteries should not be held above ~15 V.
| `V_ready` median | Verdict |
|---|---|
| 13.20-15.00 V | PASS "The car is charging its 12 V battery." |
| 12.80-13.19 V | WARN "The 12 V supply is low. It may be in a low-power mode; drive for 15 min and test again." |
| < 12.80 V | FAIL "The 12 V battery is not being charged in READY. Have the DC/DC converter and its fuse checked." |
| 15.01-15.50 V | WARN "The 12 V supply is high." |
| > 15.50 V | FAIL "The 12 V supply is too high and can damage the battery." |
- Under load: `V_load` >= 13.0 V PASS; 12.6-12.99 V WARN; < 12.6 V FAIL (converter cannot carry the load). **[eng]**
- Save the user's PASS value as a personal baseline; later runs warn if `V_ready` is > 0.5 V below baseline. **[eng]**

Next steps: FAIL -> run `dtc-full-scan` (look for P0A94 DC/DC performance, P0562/P0563, P1035/P104B/P1000 low control
voltage); workshop.

Sources: owner's manual p. 3-04 (above); TaT P1035 description (low 12 V control voltage, "a fault in the converter
that maintains 12V"): https://www.tat.net.au/scandata/codes/code-view.php?id=8316

---------------------------------------------------------------------------------------------------------------

## 3. `hv-cell-balance` - drive battery cell balance

Detects: one weak cell/module, imbalance after many partial charges. Shows cell numbers.

Preconditions
- Car parked, no driving or charging for >= 30 min (cell voltages relax). Power ON (not READY), climate off.
- `bmu.soc` between 30 and 95 % (at very low SoC the voltage curve is steep and spreads grow naturally) **[eng]**.

Steps
1. Power ON (not READY). Wait 20 s.
2. Read BMU `21 01` every 5 s for 60 s (12 samples): `bmu.cell_max_v`, `bmu.cell_max_no`, `bmu.cell_min_v`,
   `bmu.cell_min_no`, `bmu.cell_spread_mv`, `bmu.soc`, `bmu.pack_v`.
3. Spread = median of `bmu.cell_spread_mv`. Module guess = ceil(cell_no / 8) (assumes 10 modules x 8 cells; numbering
   order is not verified).
4. Optional "under load" part (drive): log at the fastest poll during a firm acceleration 30 -> 80 km/h; report max
   spread separately. Do not use it for the verdict (internal resistance differences) **[eng]**.

Sanity checks (decode is EXPERIMENTAL)
- `bmu.pack_v / 80` should be between `cell_min_v` and `cell_max_v` (+/- 0.05 V). If not: "Cell data could not be read
  reliably on this car" -> inconclusive, show raw bytes.
- Cell numbers must be 1..80; voltages 3.0-4.3 V.

Thresholds (at rest) **[eng]**, informed by owner data (a GoingElectric owner reported only ~2 mV difference during charging;
dealers' "cell smoothing" print-outs show 80 cells at the same voltage, per the Richi FAQ)
| Spread | Verdict |
|---|---|
| <= 20 mV | PASS "Cells are well balanced." |
| 21-50 mV | WARN "Slight imbalance. Let the car finish a few full AC charges, then test again." |
| 51-100 mV | WARN "Clear imbalance. One cell or module may be weaker." |
| > 100 mV | FAIL "Large imbalance. A cell or module is likely failing; ask a workshop for a battery check." |
- If `cell_min_v` < 3.40 V while `bmu.soc` > 30 %: FAIL "A cell is unusually low." **[eng]**
- Trend: same `cell_min_no` (or same module) lowest in >= 3 runs on different days -> add "Cell N (module M) is
  repeatedly the lowest."

Next steps: full AC charges without stopping early; repeat; workshop (cell smoothing / module test with MUT-III).
Link tips: `drive-battery-weak-cell`, `drive-battery-capacity-loss`.

Sources
- Richi FAQ A.2 (cell smoothing, DBCAM) and M (do not stop charges early): https://www.richi.uk/p/mitsubishi-outlander-phev-faq.html
- GoingElectric PHEV Watchdog thread (cell difference ~0.002 V while charging): https://www.goingelectric.de/forum/viewtopic.php?p=1052216
- 10 modules per pack: https://myimiev.com/threads/traction-battery-modules-replacement-outlander-phev.5462/

---------------------------------------------------------------------------------------------------------------

## 4. `hv-capacity` - drive battery capacity (state of health)

Detects: capacity loss; a confused BMU estimate.

Preconditions: power ON (not READY) or READY; any SoC. Ask the model year once (store it).

Steps
1. Read BMU `21 01` 3 times, 5 s apart: `bmu.capacity_ah`, `bmu.remaining_ah`, `bmu.soc`, `bmu.pack_v`, `bmu.energy_kwh`.
2. Reference capacity: model year 2014-2016 -> 38.0 Ah; model year 2017 -> 40.0 Ah (the UK warranty thresholds were
   70 % of 38 Ah = 26.6 Ah before MY2017, and 70 % of 40 Ah = 28 Ah for MY2017-2018). Unknown year -> show both.
   NOTE for the code: `BatteryHealth.sohPercent` currently always divides by 40 Ah (`PhevSignals.NOMINAL_CAPACITY_AH`);
   pre-MY2017 cars read ~5 % worse than the Mitsubishi basis.
3. SoH = capacity_ah / reference x 100.

Thresholds
| SoH vs reference | Verdict |
|---|---|
| >= 80 % | PASS "Battery health is good for its age." |
| 70-79 % | WARN "Normal ageing for many 2014-2017 cars; expect clearly less electric range than new." |
| < 70 % (below 26.6 Ah pre-MY2017, below 28 Ah MY2017) | FAIL "Below Mitsubishi's old 70 % warranty threshold. Range is much reduced." |
- Consistency **[eng]**: `remaining_ah / capacity_ah x 100` vs `bmu.soc`; if they differ by > 10 points, add "The battery
  computer's estimate may be out of date; an owner recalibration or a dealer capacity measurement (DBCAM) can help."
  (byte 0 SoC may be the control SoC, so allow a wide band.)
- Trend **[eng]**: drop > 2 Ah within 30 days -> "Capacity changed quickly; repeat after a full charge."
- Context text: "Owner reports: 2014 cars at 63-73 % after about 6 years in New Zealand; German owners around 32-34 Ah."

Next steps: track monthly; owner recalibration (Richi A.1); dealer DBCAM (up to 3 days; MSB-17EXL54-501); module
repair quotes. Warranty: UK 8 years/100,000 miles; most 2014-2017 cars are now past 8 years - check first registration.

Sources: Richi FAQ (A.1, A.2, A.4, AG); AA NZ https://aa.co.nz/cars/ask-an-expert/faults-reliability-service-and-repair/show/12447 ;
GoingElectric https://www.goingelectric.de/forum/viewtopic.php?p=1052216 ; owner's manual p. 2-04.

---------------------------------------------------------------------------------------------------------------

## 5. `charging-session` - does charging work and get logged?

Detects: no charge going in; charger not counting sessions; timer blocking charging.

Preconditions: `bmu.soc` <= 85 %; normal AC charging cable available; no charging timer set (the app can check over Wi-Fi).

Steps
1. Power ON (not READY). Read `bmu.soc`, `bmu.remaining_ah`, `bmu.pack_v`, `obc.charges_100v`, `obc.charges_200v` -> before.
2. "Switch the car OFF and plug in the charging cable." The manual says the power switch must be OFF to charge.
   If the Wi-Fi link is connected, watch `CarState` until charging = true (timeout 2 min -> FAIL "Charging did not start",
   link tip `charging-wont-start`).
3. Wait at least 20 min (user can leave; app notifies).
4. "Unplug (or stop charging), then switch the power ON (not READY)." Read again -> after.
   (Reading over OBD during charging is not attempted: switching ON with the cable connected may stop charging;
   18MY manual p. 3-05 describes a 10-minute buzzer in that case.)

Thresholds
- Expected SoC rise **[eng]**: 10 A (~2.3 kW) for 20 min ~ 0.75 kWh ~ 2.5 Ah into a ~300 V pack, i.e. about +6-8 % of a
  ~30-38 Ah pack; 16 A ~ +10-13 %. PASS if dSoC >= 50 % of expected (scale by actual minutes) and `remaining_ah` rose;
  WARN if > 0 but < 50 %; FAIL if no rise.
- Counter: `obc.charges_200v` (230 V in Europe is assumed to count as the 200 V class - unverified) or `obc.charges_100v`
  should rise by 1. If SoC rose but no counter changed: INFO only (counter meaning unverified).

Next steps: FAIL -> manual troubleshooting list (power switch, timer, connector, supply, 12 V battery), IT-grid earth
faults at home, other socket; workshop if it fails everywhere.

Sources: owner's manual charging troubleshooting p. 3-20 to 3-22 (14MY) and p. 3-05 (18MY):
https://mitsubishi-motors.co.uk/wp-content/uploads/2021/06/18MY-Outlander-PHEV-Owners-Manual.pdf ;
charging times (10 A ~2.3 kW, 16 A ~3.8 kW) Richi FAQ; Norwegian IT grid issue: https://www.tu.no/artikler/ny-veileder-slik-loser-du-ladeproblemene/473587

---------------------------------------------------------------------------------------------------------------

## 6. `engine-warmup` - engine cooling and thermostat (ENGINE MUST RUN)

Detects: thermostat stuck open (slow warm-up, P0128), overheating, engine not running steadily.

Preconditions: see "How to make the engine run". Outdoors, P, climate OFF (cabin heat draws heat from the coolant).
Best with a cold engine (`obd.05` within 10 C of ambient `obd.46`/`ac.ambient_temp`/`awc.ambient_temp`).
Fuel level > 10 % (`obd.2F` or `cluster.fuel_level`).

Steps
1. Read `obd.05` (start temp), ambient.
2. User forces the engine on (hold CHARGE ~10 s). Confirm `obd.0C` > 500 rpm.
3. Sample every 5 s for up to 15 min: `obd.05`, `obd.0C`, `obd.1F`, `obd.04`; optional `ecm.fan_duty`, `ac.coolant`, `obd.5C`.
4. Stop early when `obd.05` >= 80 C and stable (+/- 2 C) for 60 s, or on a FAIL condition. Tell the user to switch off.

Thresholds **[eng]** (basis: typical Mitsubishi petrol thermostats open around 82 C - the 4B11 value itself was not
verified; normal coolant after ~10 min of running is 82-105 C per Samarins; the ECU sets P0128 if warm-up is too slow)
| Result | Verdict |
|---|---|
| reaches >= 75 C within 10 min (ambient >= 0 C) or 15 min (ambient < 0 C), then stays 80-100 C | PASS "Engine warms up normally." |
| reaches only 60-74 C in that time, or drops > 5 C while running at steady load | WARN "Slow warm-up. The thermostat may be opening too early." |
| < 60 C after 15 min of running | FAIL "The engine does not warm up. Thermostat likely stuck open." |
| > 105 C | WARN "Running hot. Check the coolant level when cold." (show fan duty if available) |
| > 110 C | FAIL - tell user to switch off now "Engine overheating." |
- Engine stopped by itself during test (`obd.0C` = 0 for > 10 s while user did not switch off): WARN "The engine stopped
  by itself" + run `dtc-full-scan` (early cars: P10DC campaign).
- Also report `obd.0C` spread (max-min over the last 60 s); > 300 rpm at steady state -> "Uneven engine speed" **[eng]**.

Next steps: WARN/FAIL -> coolant level, thermostat replacement by a workshop; check P0128/P0125/P0116-P0118.

Sources: https://www.samarins.com/diagnose/p0128.html ; Richi FAQ X (forcing the engine on).

---------------------------------------------------------------------------------------------------------------

## 7. `fuel-trims` - lean/rich mixture, air leaks (ENGINE MUST RUN, WARM)

Detects: intake air leaks, weak fuel delivery, air flow sensor errors, leaking injectors.

Preconditions: engine running continuously, `obd.05` >= 75 C, STFT moving (closed loop), no active misfire codes.

Steps
1. Phase A (stationary, Charge mode in P): sample `obd.06`, `obd.07`, `obd.0C`, `obd.04`, `obd.0B`, `obd.10` every 2 s
   for 60 s. Optional `ecm.stft`, `ecm.ltft` for comparison.
2. Phase B (optional, on the road with Save/Charge active so the engine runs): steady 70-90 km/h for 60 s, same signals.
   (The app cannot raise rpm itself; Phase B replaces the classic "hold 2000 rpm" step.)
3. Per phase: LTFT = median `obd.07`, STFT = median `obd.06`, total = LTFT + STFT. (Bank 2 PIDs 08/09 do not apply to the
   inline-4 engine.)

Thresholds (aa1car: STFT normally within +/-10 %, LTFT near 0 and > 10 % indicates a problem; lean/rich codes at about +/-20-25 %)
| |total| | Verdict |
|---|---|
| <= 10 % | PASS "Fuel mixture is normal." |
| 10.1-20 % | WARN "The engine is correcting the mixture more than normal (lean if positive, rich if negative)." |
| > 20 % | FAIL "Large fuel correction; a P0171/P0172 code is likely soon." |
Interpretation
- Positive (lean) in A but normal in B: "Likely an air leak after the air flow sensor (hoses, intake gasket)."
- Positive in both: "Likely weak fuel delivery or an air flow sensor reading too low."
- Negative (rich) in both: "Likely a leaking injector, high fuel pressure, an air flow sensor reading too high or the
  fuel vapour purge system." Show `ecm.purge` if available.

Sources: https://aa1car.com/library/what_is_fuel_trim.htm ; related codes P0171/P0172/P0101.

---------------------------------------------------------------------------------------------------------------

## 8. `inspection-readiness` - before EU-kontroll (engine lamp, codes, self-tests)

Detects: engine lamp on, emission codes, incomplete self-tests (readiness monitors), recently cleared codes.

Preconditions: power ON (engine need not run). Best after a week of normal use with some engine running.

Signals / requests
- PID 01 (current status) - decode all 4 bytes, not just A (implementation note: `Mode01.pids[0x01]` only decodes A today):
  - A bit7 = MIL on; A bits0-6 = number of confirmed emission DTCs.
  - B bit3 = 0 spark ignition. B bits0-2 = supported continuous monitors (0 misfire, 1 fuel system, 2 comprehensive
    components); B bits4-6 = the same tests NOT complete (1 = incomplete).
  - C (spark ignition) = supported: bit0 catalyst, bit1 heated catalyst, bit2 EVAP, bit3 secondary air,
    bit4 A/C refrigerant, bit5 O2 sensor, bit6 O2 sensor heater, bit7 EGR/VVT.
  - D = same bit order, 1 = NOT complete.
- PID 41 (this drive cycle) same layout for B-D (A reserved) - optional "since engine start" view.
- `obd.21` distance with MIL on, `obd.30` warm-ups since codes cleared, `obd.31` distance since cleared, `obd.4E` time since cleared.
- Modes 03 / 07 / 0A via `scanDtcs(false)`.

Verdict
| Condition | Verdict |
|---|---|
| MIL on, or any stored or permanent emission code | FAIL "The engine warning lamp or an emission fault would likely fail the inspection. Fix it first." |
| MIL off, no codes, >= 1 supported monitor incomplete | WARN "Some engine self-tests have not finished. Drive with the engine running (Save or Charge) on a few 20-30 minute trips and test again." |
| `obd.31` < 200 km or `obd.30` < 10 **[eng]** | add WARN "Fault codes were cleared recently; the self-tests may not be finished yet." |
| pending codes only | WARN "A fault is being watched but not confirmed yet." |
| MIL off, no stored/pending/permanent codes, all supported monitors complete | PASS "Ready for the emissions part of the inspection." |
- Note to show: EVAP and catalyst monitors need the engine warm and specific conditions; on a PHEV that rarely runs its
  engine they can take many days. Do not clear codes in the weeks before the inspection.
- The exact Norwegian handling of incomplete monitors was NOT verified; EU rules (Directive 2001/9/EC, carried into
  2014/45/EU) allow the emission check via "reading of the OBD device and simultaneous checking of the proper
  functioning of the OBD system".

Sources: SAE J1979 PID 01/41 layout; Directive 2001/9/EC text https://app.lexploria.com/document/259629-d ;
EU-kontroll intervals and emissions check for petrol cars https://www.bos.no/bilverksted/eu-kontroll-regler-du-bor-kjenne-til ;
Richi FAQ X (engine on for emissions test).

---------------------------------------------------------------------------------------------------------------

## 9. `dtc-full-scan` - all control units

Detects: stored/pending/permanent codes in engine, BMU, charger, motors, ABS, cluster, A/C, AWC.

Preconditions: power ON (not READY). Run `twelve-volt-health` step 1 first if possible: below 12.2 V, warn "Low 12 V
battery can cause false communication codes" **[eng]**.

Steps: `scanDtcs(includeExtended = true)`; group results per ECU; keep the raw list; offer "Save report" before any clear.

Interpretation rules (show the first matching group first)
- Low-voltage pattern: P0562 and/or manufacturer codes P1035 (RMCU 12 V control voltage), P104B (GCU), P1000 (FMCU),
  P1AF2, plus U0xxx lost-communication codes in several units -> "These codes usually come from a weak 12 V battery.
  Test the 12 V battery, then clear the codes and scan again after a drive." (TaT case MITZK14447)
- P10DC -> "Known on early cars (engine stops after cold start). Ask the dealer about the software and spark plug campaign."
- P0Axx (hybrid/HV: e.g. P0AA6 isolation, P0A94 DC/DC, P0A7F battery deterioration, P0A80 replace battery) ->
  "High-voltage system fault. Have it checked by a Mitsubishi workshop; do not open HV parts."
- P01xx/P02xx/P03xx/P04xx emission codes -> link `fuel-trims`, `engine-warmup`, `inspection-readiness`.
- C-codes from ABS/ASC -> brakes, wheel speed sensors, tyres (`wheel-speed-match`).
- Only U-codes in many units -> 12 V or wiring/ground problem.
- Implementation note: P1035, P104B, P1000, P1AF2 and P10DC are manufacturer codes and are missing from codes.tsv; add
  titles (see TaT and Richi sources).

Verdict: PASS no codes; WARN only pending/history or low-voltage pattern; FAIL confirmed P0Axx or MIL-on emission code.

Sources: https://www.tat.net.au/tatsafact/5952 ; https://www.tat.net.au/scandata/codes/code-view.php?id=8316 ;
Richi FAQ O.2 (P10DC).

---------------------------------------------------------------------------------------------------------------

## 10. `wheel-speed-match` - tyre size / wear mismatch (EXPERIMENTAL signals)

Detects: different tyre sizes or wear front vs rear, which owners link to "EV System Service Required" and reduced regen.

Preconditions: straight, level road; steady 50-80 km/h (cruise if possible); no braking or hard acceleration; tyre
pressures set as on the door label.

Steps
1. Sample at the fastest rate for 30 s: `abs.wheel_fl`, `abs.wheel_fr`, `abs.wheel_rl`, `abs.wheel_rr`, `obd.0D`,
   `motor.front_rpm`, `motor.rear_rpm`.
2. Drop samples where `obd.0D` changes by > 2 km/h between samples.
3. If the ABS PIDs answer: front = mean(fl, fr), rear = mean(rl, rr); diff % = (front - rear) / mean x 100; also
   left-right per axle and each wheel vs the mean of all four.
4. If ABS PIDs do not answer: ratio R = `motor.rear_rpm` / `motor.front_rpm` at steady speed (both motors are geared to
   their axles). There is no published ratio, so store the first run as baseline (ask "Are all four tyres the same make
   and evenly worn?") and compare later runs.

Thresholds **[eng]** (1 % of rolling circumference on 225/55 R18 is about 7 mm of diameter, i.e. ~3.5 mm tread difference)
| |front-rear| (or R change vs baseline) | Verdict |
|---|---|
| <= 0.5 % | PASS "Front and rear tyres roll at the same speed." |
| 0.5-1.5 % | WARN "Front and rear tyres differ a little (wear or pressure)." |
| > 1.5 % | FAIL "Front and rear tyres differ clearly. Different tyres can trigger EV system warnings." |
- Single wheel > 1 % from the mean -> "Check that tyre's pressure." **[eng]**

Sources: Richi FAQ L.3 (mixed tyres and EV System Service Required); signals from OBDb (non-PHEV, experimental) and the
myoutlanderphev forum (motors).

---------------------------------------------------------------------------------------------------------------

## Which tests need the engine running
- Required: `engine-warmup`, `fuel-trims`.
- Helpful: `inspection-readiness` (monitors complete only after engine running in the preceding days).
- Not needed: `twelve-volt-health`, `dcdc-output`, `hv-cell-balance`, `hv-capacity`, `charging-session`, `dtc-full-scan`, `wheel-speed-match`.

## BMU 0x761/0x762 mode-21 findings
- No reliable public byte layout for `21 02`-`21 06` (per-module cell voltages, pack current, temperatures) was found.
  The primary source (myoutlanderphev.com thread "Extracting useful data from your OBDII port", and "List of OBDII
  parameters") blocks automated fetching; GitHub repos found (JJRoberts902/Outlander-PHEV-BMU-TOC, projectgus CAN logs,
  Tom-evnut/OutlanderPHEVBMS) cover firmware maps and CAN broadcast frames, not service 21.
- Related but different (i-MiEV CAN broadcast, not OBD): pack current from CAN id 0x373 = ((D3*256 + D4) - 32768) * 0.01 A;
  0x374 byte 0/1 = control/display SoC (https://myimiev.com/threads/battery-capacity-and-computed-soc-obdzero-bmu-pid-762.5222/latest).
  Do not apply to the Outlander without verification.
- Capacity reference: pre-MY2017 Mitsubishi warranty basis 38 Ah, MY2017-2018 40 Ah (Richi FAQ AG). Dealer procedures:
  DBCAM (MSB-17EXL54-501), "Batt. capa. estimated info. reset" + "Control information reset" (MSB-14EXML00_54-001),
  "Cell smoothing".
- Suggestion: log raw `21 02`..`21 06` replies (BatteryHealth.rawBmu already keeps `21 01`) from users who opt in, with
  `bmu.pack_v` and `bmu.cell_max/min` alongside, to decode them from data (sum of 80 cell values should equal pack voltage).
