# Changelog

## 2.2.39

### Changed

- Today's cumulative driving totals are now upserted to MongoDB every 30 minutes instead
  of waiting until the following day. Re-sending totals cannot double-count a retry.
- DC charging sessions remain entirely local while charging and are sent immediately after
  completion. Failed completed-session uploads remain queued and retry every five minutes.

## 2.2.38

### Changed

- Charging cost now uses estimated grid energy: battery-side net kWh plus a fixed 10%
  charging loss. Both net and estimated grid energy are stored separately.
- Driving consumption, duration, distance and SOC drop now accumulate only while the car
  is moving; parked HVAC and accessory use no longer inflate driving statistics.

## 2.2.37

### Added

- Cloud sync is configured. The build carries the API origin and a write-only ingest
  token, so daily consumption and completed DC charging sessions reach MongoDB, and
  whatever queued up while offline is uploaded once connectivity returns.

### Changed

- The consumption API moved from a standalone Express service to a Next.js app on Vercel,
  which also serves a dashboard for reading the history back. The token in this APK opens
  the write endpoints only — reading needs a password the APK does not contain, and the
  MongoDB URI remains server-side and has never been in a build.

## 2.2.36

### Changed

- Charging sessions are now metered on DC only (10 kW and above). An AC charge is an
  overnight affair the head unit sleeps through, so its curve, duration and energy were
  guesses; the charging screen labels one as `AC şarj (ölçülmüyor)` rather than showing
  the previous DC session's figures next to it.

### Fixed

- Charging energy and duration are integrated on the monotonic clock. They used the head
  unit's wall clock, which jumps when it picks up GPS or NTP time — one forward jump
  mid-charge invented both kWh and hours. This is the same fix the consumption counters
  already carried.
- Intervals longer than five minutes are no longer integrated into a charging session. A
  head unit that slept mid-charge came back and billed the whole gap at the last power
  reading; a session interrupted overnight reported the night as charging time. Reported
  duration is now metered charging time, not the wall-clock span from start to end.
- A daily consumption record uploaded after an offline spell carries the lifetime total as
  it stood at the close of *that* day. Every backlogged day used to be stamped with the
  current lifetime, which plots as a flat line and one cliff.
- The offline charging-session queue is capped at 50 sessions. It is a single
  SharedPreferences string read into memory in full, and with the cloud endpoint
  unconfigured nothing ever drained it.
- A long charge no longer loses the start of its curve. At 720 points the sample interval
  doubles and the curve is thinned, instead of dropping the oldest point.
- The cloud sync throttle runs on the monotonic clock, so a backwards clock correction
  cannot stall syncing. Catch-up now sends up to 10 days and 10 sessions per cycle rather
  than one and three.

## 2.2.35

- Added persistent charging sessions with SOC/power curves, duration, energy, unit price,
  and total cost.
- Added an offline queue that retries completed charging sessions when connectivity returns.
- Added once-daily MongoDB consumption history with ordered catch-up after offline periods.
- Added authenticated history endpoints for a future personal mobile client.

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.2.34] - 2026-10-05

### Added

- Added a separate **Upload telemetry to ABRP** switch. Turning it off keeps background
  vehicle sampling, consumption history and OTA support running, but stops ABRP network
  uploads so DiPlay can be the sole live telemetry sender.

## [2.2.33] - 2026-10-02

### Fixed

- The SOC total no longer loses its meaning after a charge. The consumption counters
  decided whether the car was charging from `EV_CHARGE_PORT_CONNECTED` alone, and this
  vehicle's VHAL answers `false` to it with the cable in — the documented reason
  `ChargingSignal` exists. A charge therefore read as a drive, and every percent gained
  was booked as negative consumption against the total.
- The charging screen and the consumption counters now share one answer, in the pure
  `ChargingState`, so they cannot drift apart again. A charge is the vendor status saying
  so, or power flowing into the pack with the port agreeing or the car standing still —
  a stationary car cannot regenerate.

### Changed

- The stored SOC column is zeroed once on upgrade. Distance, energy and time never
  consulted the charge state, are unaffected by the bug, and are kept.

## [2.2.32] - 2026-10-02

### Fixed

- Consumption now disagrees with neither itself nor the car. Lifetime was integrated from
  net DC power while the day and trip counters took either the vehicle's own cumulative
  kWh register or a fallback that discarded regeneration — three sources, three answers,
  and the fallback read high because recovered energy never subtracted. Every period is
  now integrated from one step of DriveHub_Dort's algorithm: trapezoidal net DC power,
  trapezoidal calibrated speed, and compensated (Kahan) accumulation.
- Integration intervals are measured on the monotonic clock. The head unit's wall clock
  jumps when it picks up GPS or NTP time, which previously either dropped intervals or
  invented hours of driving from a single forward jump.

### Changed

- Consumption counters start from zero on this release. The stored totals were produced by
  the mixed algorithms above and no factor converts them into what is now measured, so the
  old store is discarded rather than carried forward as a plausible-looking total.
- `ConsumptionMath` holds the integration as a pure, unit-tested class, per the repository's
  rule that logic lives outside the Android classes.

## [2.2.18] - 2026-09-30

### Changed

- Kept outside temperature on DriveHub_Dort's proven `getOutCarTemp` service path.
- Removed unavailable battery-temperature, battery-capacity and cabin-temperature rows
  from the vehicle screen instead of permanently showing dashes.

## [2.2.17] - 2026-09-30

### Fixed

- Replaced the custom OTA downloader with DriveHub_Dort's Android DownloadManager flow,
  then stages and verifies the downloaded bytes before installation.

## [2.2.16] - 2026-09-30

### Added

- Added a persistent installed-version badge immediately left of the OTA button.

## [2.2.15] - 2026-09-30

### Changed

- Published a follow-up build to exercise the public-Downloads installer fallback over OTA.

## [2.2.14] - 2026-09-30

### Fixed

- Stage fallback OTA packages in public Downloads and grant the resolved system installer
  explicit read access, avoiding the MG head unit's private-cache package parse failure.

## [2.2.13] - 2026-09-30

### Changed

- Published a follow-up build to verify the interactive installer fallback over OTA.

## [2.2.12] - 2026-09-30

### Fixed

- When PackageInstaller sessions cannot start on the head unit, OTA now opens Android's
  interactive APK installer through a cache-scoped FileProvider, matching DriveHub_Dort.

## [2.2.11] - 2026-09-30

### Changed

- Page tabs now switch immediately without ViewPager's multi-page sliding animation.

## [2.2.10] - 2026-09-30

### Fixed

- Matched DriveHub_Dort's proven install path: verify SHA-256 and package identity, then
  let Android PackageInstaller perform the authoritative signing-certificate check. This
  avoids the head unit's false signature mismatch while remaining fail-closed.

## [2.2.9] - 2026-09-30

### Changed

- Updated the check-in-progress copy for a fresh OTA upgrade test from 2.2.8.

## [2.2.8] - 2026-09-29

### Changed

- Updated the check-in-progress copy for an end-to-end OTA upgrade test from 2.2.7.

## [2.2.7] - 2026-09-29

### Changed

- OTA updates now require an APK-specific `.sha256` asset or matching `SHA256SUMS` entry,
  adapted from DriveHub_Dort commit `3b043875`. Downloaded bytes are hashed while streaming
  and rejected before PackageInstaller if integrity does not match.
- Startup performs a quiet availability check and never downloads by itself. Manual updates
  show download progress and a final result dialog.
- Existing exact-host, same-certificate and same-package checks remain mandatory.

## [2.2.6] - 2026-09-29

### Changed

- Redesigned all five pages with an OLED-black background, charcoal translucent panels,
  muted gold navigation and cyan controls. Static gradients avoid continuous rendering.
- Vehicle overview is now the first page. Energy, temperatures and tyre pressures appear
  at the top, with compact battery, range, power, speed and energy cards below.
  Unknown data stays a dash.
- Added a wide-screen navigation rail and paired cards, with stacked pages and bottom
  navigation for narrower windows. Headers follow the selected page.
- Restyled credentials, transfer controls, frequency picker, Wi-Fi, logs and About;
  the interface stays dark regardless of the system theme.

## [2.2.5] - 2026-09-29

### Removed

- Window closing: its page, service, door listener, pulse logic, resources and dedicated
  permissions are removed. Previous opt-in preferences cannot restart the removed service.

### Fixed

- OTA checks now report API, network and response errors instead of saying the app is current.
- Release selection considers every asset, choosing the newest eligible version even when
  it appears later in the same release. Drafts and stable releases are excluded.
- Concurrent launch/button update checks no longer start duplicate downloads. Installer
  session handles are closed after commit; failed sessions are abandoned.
- Wi-Fi permission refusals are handled explicitly, satisfying the Android lint checks.

## [2.2.4] - 2026-09-25

### Fixed

- OTA installs now go through the platform `PackageInstaller` instead of shelling out to
  `pm install -r`. The old path handed the package manager service a file inside the app's
  private cache, which it cannot read from its own process, and every such failure was
  reported as `Kurulum reddedildi (imza uyuşmuyor?)` — a signature verdict on a correctly
  signed build. A session streams the archive from this process, so no second process has
  to reach our cache. Approach taken from merthankaraman/DriveHub_Dort, which solved the
  same problem on the same head unit.
- The four ways an install can be refused no longer share one message. The certificate
  check, an unparseable or wrong-package archive, and a failed session each say so, and the
  package manager's own `INSTALL_FAILED_*` text is surfaced verbatim rather than guessed at.
- The archive's package name is checked against ours. The platform key signs every app on
  the unit, so a certificate match alone never meant the APK was this app.

## [2.2.3] - 2026-09-23

### Added

- Opt-in SWI69 Comfort window closing adapted from WinClose, independent of ABRP.
  Door opening while in P at readable 0 km/h closes all four windows with a bounded
  five-second pulse; STOP is attempted on completion, cancellation and failure.
  No driving timer, speed threshold, delay or per-window settings. A wake lock is held
  across the pulse, and WinClose's 60-second cooldown suppresses a second close when the
  door is reopened. The state a fresh listener registration replays seeds the door edge
  detector instead of triggering it. The service stops itself on a non-SWI69 generation
  or without system uid. Not tested on a car.

## [2.2.2] - 2026-09-17

### Changed

- Updated EVHardware to the shared `master` catalogue and telemetry implementation.

## [2.2.1] - 2026-09-04

### Fixed

- **Speed was uploaded 3.6× too high.** `PERF_VEHICLE_SPEED` reports km/h on SWI68, not the
  metres per second AAOS specifies, and EVHardware converted it a second time. Every telemetry
  upload from this generation therefore carried a speed 3.6 times the real one, and ABRP's
  consumption and arrival models were fed it. Proven on SWI68-29958-1300R67 by two recordings of
  a 2.4 km route that came out at 7.69 km and 8.46 km — 129 and 124 km/h average over a few
  minutes of town driving. The conversion is now evidence-gated per firmware generation in
  EVHardware. Telemetry already sent to ABRP is not corrected by this.

## [2.2.0] - 2026-08-27

### Changed

- Energy acquisition now consumes EVHardware's coherent `EnergySnapshot`; duplicate SOC,
  range, power, temperature, odometer and charge property ids were removed from the app.
- Charging-session integration and tyre-pressure reads moved to EVHardware. The local
  car-property adapter is now unused by production and supports debug diagnostics only.

## [2.1.8] - 2026-08-26

### Fixed

- **The weather answers arrived all along; they were being thrown away.** EVHardware read the
  reply parcel one int too early — a nullable parcelable argument is written as a presence flag
  and then the object, so every field landed in the next one's place, the payload's type tag
  never matched, and a reading that had arrived was reported as unreadable. That is precisely
  the `map=bound` with nothing behind it that 2.1.7 timed the query to explain. Tracking
  EVHardware 1.6.0 fixes it, and `ext_temp` has a working source that does not depend on
  catching a broadcast at the right moment.

## [2.1.7] - 2026-08-26

### Fixed

- **The weather broadcast is real but rare, and the app forgot it between restarts.** The action
  is confirmed: the head unit's weather app sends `com.saicmotor.weather` when *it* refreshes,
  not on any cadence this app can ask for. 2.1.6 listened and gave up between refreshes, and the
  app is restarted every time the driver opens it to look at the log — which is the worst moment
  to start waiting. The last reading is now kept across restarts for two hours, and the platform
  is asked at start-up and on every temperature-less upload for a held broadcast, which costs
  nothing and answers immediately if the weather app sends its update sticky.

### Changed

- **`map=bound` in 2.1.6 corrected the release note that shipped with it.** That release said the
  map service never bound; the log showed the binder alive and the *query* returning nothing,
  which is a different failure with a different fix. The map query is now timed, and the elapsed
  time appears in the log — the query is bounded by a two-second wait inside EVHardware, so a
  null returning at once was refused or answered undecodably while a null returning at the bound
  is a service that did not answer in time. Nothing outside the library distinguishes them, and
  guessing which one it was is what produced the wrong note.
- The broadcast reading now carries its age in the log (`bcast=Toulouse/12min`): where a value
  came from does not say whether it still describes the day.

## [2.1.6] - 2026-08-26

### Fixed

- **`ext_temp` was still absent: the weather service 2.1.5 added never bound.** The log added in
  that release is what showed it — every upload listed `ext_temp` among the omitted fields, with
  no place name beside the fix. Meanwhile the head unit's own status bar shows a temperature
  continuously, and it does not get it from that service: it gets it from a broadcast. The
  uploader now listens for the same one (`com.saicmotor.weather`) and prefers it, falling back
  to the map service query and, before either, to the vehicle sensor on a car that has one.

  The payload's shape is unverified, so it is read by searching for any key that names a
  temperature at any depth rather than by a fixed layout, in either unit, whether the value
  arrives as a number or as a string. A parser written against a guessed layout fails silently
  and looks exactly like the bug it was meant to fix.

### Added

- **The temperature's source, on every log entry** — `[temp car]`, `[temp bcast:Toulouse]`,
  `[temp map:...]`, or when nothing answered, why each one did not: `[temp none bcast=no
  broadcast yet map=unbound]`. A payload that arrives but carries no readable temperature is
  reported with a sample of what it did carry, which is what a fixed parser would have hidden.

### Notes

- Position is not the problem, and the log settled it: fixes arrive from the `fused` provider,
  accurate to about a metre and seconds old, with the coordinates the car is actually at. An
  address that looks wrong on ABRP is not coming from what this app sends.

## [2.1.5] - 2026-08-26

### Fixed

- **`ext_temp` reached ABRP as 0 degC, and 2.1.4 did not change that.** Guarding the value only
  stopped the app sending a zero; ABRP shows 0 for an absent `ext_temp` too, so the symptom was
  identical. The cause is the car: this VHAL does not implement `ENV_OUTSIDE_TEMPERATURE`, so
  there was never a vehicle reading to correct. The outside temperature now comes from the head
  unit's own weather service (`com.saicmotor.mapservice`, already bound by EVTasker through
  `SaicWeather`), queried for the car's position and cached for 10 minutes — the query can reach
  the network from inside the head unit and it blocks the upload thread while it does. The
  vehicle sensor is still preferred whenever a car does implement it.
- **A GPS fix could be dropped for being stale when its age was never knowable.** 2.1.4 aged
  fixes on the monotonic clock, but a provider that does not stamp `getElapsedRealtimeNanos()`
  leaves it at zero, which dated every fix to the last boot and would have discarded all of
  them — a staleness guard turning into a position blackout. An unstamped fix is now kept, and
  a fix from a clock that jumped forward is no longer aged negatively.

### Added

- **The payload summary is in the app's Log tab, not only in logcat.** 2.1.4 put it where a car
  with no adb attached cannot show it. Each attempt now carries the fields it sent and the ones
  it left out, which is the only thing that separates "ABRP shows 0" from "the app sent 0".
- **Position diagnostics on each entry:** the fix's age, its accuracy, the provider that
  supplied it, and the place name the weather service answered for. ABRP reverse-geocodes the
  address it displays from the same coordinates, so a place name that disagrees with it means
  the coordinates are wrong, and one that agrees means ABRP is showing something older.

## [2.1.4] - 2026-08-25

### Fixed

- **The car was reported where it used to be, not where it is.** A GPS fix had no expiry, so
  `lastLocation` kept whatever it last held. Two paths turned that into a wrong position:
  start-up seeded it from `getLastKnownLocation()` unconditionally — with a cold receiver that
  is the *previous* drive's fix, uploaded stamped with the current time for the first minutes
  of every trip — and once the receiver stopped delivering (garage, tunnel) the same fix was
  resent forever. A fix is now dropped past `max(5 min, 3 x upload interval)`, measured on the
  monotonic clock rather than the wall clock, which the head unit adjusts from GPS itself.
  Position is then omitted and ABRP keeps its own last point instead of being moved backwards.
- **`ext_temp` could be sent as a freezing day the car never measured.** It was the only
  temperature without a plausibility guard, while `cabin_temp`, `batt_temp` and `hvac_setpoint`
  all had one. A VHAL that does not implement `ENV_OUTSIDE_TEMPERATURE` returns `0.0` rather
  than throwing, so the read looked successful and `ext_temp: 0` went to ABRP's consumption
  model. Now guarded like the others (-50..80 degC, exactly 0.0 excluded). A genuine 0 degC is
  lost with it — the cheaper of the two errors, and the same trade-off already made three times.

### Added

- **A one-line summary of every upload in the log.** `TelemetryPayload.summarize()` renders
  what actually went on the wire, derived from the JSON itself so it cannot drift from the
  payload it describes, and names the fields that were left out. That is what separates a read
  that failed from a read that returned something wrong — the question this release started from.
  Dropped stale fixes are logged with their age.

## [2.1.3] - 2026-08-25

### Removed

- `android.car.permission.CAR_POWERTRAIN` — declared since the first release and never used.
  The app reads no powertrain property: park state comes from the vendor gear services through
  EVHardware, and nothing here touches the standard AAOS gear or ignition properties the
  permission guards. Dropped from the manifest and from the security allowlist.

## [2.1.2] - 2026-08-25

### Fixed

- **The uploader starts on a car that has not granted position yet.** The service declares the
  `location` foreground type, and since Android 14 every declared type is checked against the
  permissions held at the moment it enters the foreground — so a fresh install, or a boot
  before the app had ever been opened, killed the service in its own `onCreate` before the
  first tick. The type is now claimed only while the permission behind it is held, and the
  activity asks for the permission *before* starting the service rather than after.
- **Outside and cabin temperature reach ABRP again.** Both were declared and read since 2.1.0
  but never requested at runtime, so both properties answered as unreadable and were dropped
  from every payload.
- **The service notification is visible.** `POST_NOTIFICATIONS` was declared and never asked
  for; the only status the service has was being dropped silently on Android 13 and later.
- A permission granted while the service is already running now takes effect at once instead
  of at the next boot.

## [2.1.1] - 2026-08-23

### Fixed

- Restored stable release builds by approving the read-only odometer, tyre-pressure and
  battery-capacity permissions in the security allowlist.

## [2.1.0] - 2026-08-23

### Added

- The telemetry payload now carries every ABRP field this vehicle can actually answer:
  pack temperature, usable capacity, state of energy, odometer, climate setpoint, the four
  tyre pressures, and `kwh_charged` for the running charge session. `kwh_charged` has no
  counter in the VHAL, so it is integrated from the instantaneous charge rate on every
  sample tick — including the ticks the cadence policy throttles away — and resets when a
  new session starts.
- Range falls back to the standard AAOS `RANGE_REMAINING` property when the SWI68 vendor
  cluster answers nothing, so range now reaches ABRP on other firmware generations too.

### Fixed

- Three vehicle property IDs were off by one and had silently disabled their fields: the
  charge-port read was the flap (`EV_CHARGE_PORT_OPEN`) rather than the cable
  (`EV_CHARGE_PORT_CONNECTED`), and the cabin-temperature ID was not a VehiclePropertyIds
  value at all, so `cabin_temp` had never been sent.

## [2.0.1] - 2026-08-22

### Fixed

- Telemetry no longer stops while the car is charging. Charge detection relied solely on
  the standard AAOS charge-port property, which this VHAL need not implement: a plugged-in
  car reading "unplugged" was treated as parked and idle and throttled to one sample every
  15 minutes, so ABRP never saw the SOC curve it needs to size a charging stop. The charge
  rate and, failing that, a rising SOC on a parked car are now used as fallbacks.
- `power` is now signed ABRP's way — positive leaving the battery, negative charging. The
  vehicle's charge rate uses the opposite convention and was being forwarded unchanged, so
  ABRP saw a fast-charging car as discharging.

## [2.0.0] - 2026-08-15

### ⚠️ Breaking — existing users must install once more

- Renamed from MG4ABRPUploader to **EVABRPUploader**, and the application id changed from
  `com.mg4.abrptelemetry` to **`com.evsuite.abrp`**. Android treats this as a different app,
  so it does not update an existing install: it is added next to it and starts with no ABRP
  credentials and no permissions. Re-enter your ABRP token here, confirm telemetry reaches
  your account, and only then uninstall the old app.

## [1.1.0] - 2026-08-10

### Changed

- Reworked the main screen as three swipeable ABRP, Service and Log pages with a fixed,
  high-contrast top navigation bar and consistent selected-state accessibility.
- Updated the shared EVHardware dependency to the current catalogue and diagnostics layer.

## [1.0.3] - 2026-08-09

### Fixed

- Corrected stable release packaging and metadata.

## [1.0.2] - 2026-08-04

### Added

- Add an in-app About surface with project information and a repository QR code.

### Security

- Keep unstable OTA downloads in private cache, validate every redirect, verify the APK
  signing certificate, and require an explicit successful `pm install` result.
- Remove the public-download update path and its storage permission exposure.

## [1.0.1] - 2026-08-03

### Changed

- Prefill the ABRP API-key input with the public open-source telemetry key while
  preserving any custom key already saved by the user.

## [1.0.0] - 2026-08-02

### Added

- API key and token inputs are masked by default, with a password visibility
  toggle sized for in-car touch targets.
- CONTRIBUTING.md, DESIGN.md and this CHANGELOG.
- Security issue template.

### Changed

- Adopted the EVSuite design system. Colour, spacing, type and component styles now
  come from shared tokens (`values/colors.xml`, `values/dimens.xml`,
  `values/styles_ev.xml`), generated by `tools/sync-tokens.mjs` and specified in
  [DESIGN.md](DESIGN.md).
- Theme is now `Theme.Material3.DayNight.NoActionBar`: the app follows the vehicle's
  day/night setting, and the light palette is held to the same 7:1 contrast floor as
  the dark one.
- `ev_outline` raised from `#4A525B` to `#7A8492`. The old value was 2.25:1 against
  `ev_surface`, below the 3:1 floor for non-text UI, so the card border it was meant
  to provide effectively was not there.
- Launcher icon replaced with the EVSuite adaptive icon: charcoal tile, white glyph
  with a single red accent, product caption. Vector only — the five legacy density
  bitmap buckets are gone.
- README restructured to the shared EVSuite skeleton; table of contents is now
  generated by `tools/sync-docs.mjs`.
- Repaired bug/feature issue templates (duplicate YAML key, field ordering).
