# AGENTS.md — EVABRPUploader

Context for AI agents and new contributors working in this repository.

EVABRPUploader is part of **EVSuite**. The workspace `AGENTS.md` and normative
workspace `DESIGN.md` apply; this file contains only telemetry-specific additions.

## What this is

An Android Automotive app for the **MG4 (SAIC eh32)** that reads vehicle telemetry and
pushes it to A Better Route Planner. It runs on the car's head unit (AAOS 9, MT2712).

Fork of Leon Kernan's `ABRP_Uploader`. See `LICENSE.md` — the licence status is
**unresolved** and that is a real constraint, not paperwork.

## Non-negotiables

1. **Telemetry is read-only.** The explicitly requested SWI69 WinClose integration is
   the only vehicle-write exception: an opt-in service closes the four windows on the
   WinClose door callback, requiring P and readable 0 km/h before and during the pulse.
   It uses WinClose's CarState/VSM interfaces and the shared VehicleWriteGate; no other
   vehicle settings may be written. Always send STOP when an active pulse exits.
2. **A failed read is not a zero.** Getters return `Integer`/`Float`/`Boolean` and `null`
   means "could not read". Null fields are omitted from the payload. Sending 0 for SOC
   tells ABRP the battery is empty and wrecks a live route plan.
3. **Fail closed.** Update origin, APK signature, unknown vehicle state — refuse rather
   than assume.
4. **Never let a credential reach a URL, a log line, or a crash report.**
5. **AAOS 9 must stay stable.** A third-party app crashing the head unit is unacceptable.
   Every car call goes through reflection with try/catch; keep it that way.
6. **Power matters.** The head unit is awake whenever the car is on. No wake locks, no
   alarms. The GPS subscription and the upload tick both cost power — keep them in step.

## Architecture

```
AbrpUploadService   foreground service; scheduler thread does all IPC and HTTP
CarPropertyAdapter  reflection bridge to android.car (not on the compile classpath)
TelemetryPayload    pure JSON building — no Android types, unit-tested
UploadCadence       when a tick actually uploads — pure, unit-tested
UploadSettings      user cadence configuration
UploadLog           last 20 attempts + derived service state — pure, unit-tested
SecurePrefs         credentials in EncryptedSharedPreferences, migrates plaintext
AbrpApi             HTTP; credentials in the POST body, never the query string
MainActivity        configuration, service state, upload log
WindowCloseService independent, opt-in foreground service; no ABRP/GPS dependency
WinCloseHardware   SWI69 adapter derived from the sibling winclose/WindowHardware.kt
WindowClosePulse   bounded UP/STOP sequence; pure, unit-tested
```

**Put logic in the pure classes.** Anything Android-free can be unit-tested on the JVM;
anything inside the service effectively cannot be tested without a car.

## Source sets

| Set | Contents | Ships in |
|---|---|---|
| `main` | everything shared | all builds |
| `stable` | `UpdateHook` no-op | stable channel |
| `unstable` | updater policy code; trigger suspended during the suite audit | unstable channel |
| `debug` | `VhalProbe` — enumerates the whole VHAL | debug builds only |
| `testUnstable` | OTA policy tests | test only |

The stable channel does not merely disable self-update — the updater is **not in the
APK**.

## Firmware generations

MG4 infotainment ships as SWI68 / SWI69 / SWI131 / SWI132 / SWI133 / SWI165, and they
differ. Property IDs confirmed on one generation are not universal. Anything vehicle-facing
must tolerate a property simply not existing.

**Two read paths, split by whether the signal is firmware-agnostic:**

| Signal | Read path | Firmwares |
|---|---|---|
| Speed, outside temp, park | `EVHardware` getters (`getVehicleSpeedKmh`, `getOutsideTempCelsius`, `isVehicleInPark`) | all six — EVHardware branches per generation internally |
| SOC, range | `CarPropertyAdapter` vendor IDs (`0x2160F404`, `0x2140F41C`) | **SWI68 only** |
| Charge rate/port, cabin temp | `CarPropertyAdapter` standard-AAOS IDs | wherever the VHAL implements them |

`EVHardware.init()` is called in `AbrpUploadService.onCreate()` (idempotent); it detects
the generation and picks the right underlying API. Fields it covers therefore work on every
supported firmware.

**Reference firmware: `SWI68-29958-1300R69`.** The vendor EV IDs still in
`CarPropertyAdapter` are supported only on SWI68 firmware. EVHardware
has **no EV-battery abstraction for any generation**, so SOC and range cannot go through it;
until those vendor IDs are confirmed on the other generations they stay SWI68-only, and on
another firmware they may silently return nothing (handled safely — the field is omitted, but
SOC/range would be missing). Re-confirm with `VhalProbe` before trusting the vendor IDs on any
other generation.

## Working here

```bash
mise run check      # what CI runs: permission gate + lint + unit tests
mise run build
mise run logs       # adb logcat, filtered to this app
```

Gradle needs JDK 17. If a build fails in `JdkImageTransform` complaining about `jlink`,
a stale Gradle daemon started by VS Code's Java extension is being reused —
`pkill -f GradleDaemon` and retry.

## Things that will bite you

- **`android.car` is not on the compile classpath.** Everything car-related is reflection.
  R8 cannot see those uses, so `proguard-rules.pro` keeps them by name.
- **Unit tests get a stub `org.json`** from `android.jar` that returns null. The real
  implementation is added as a test dependency; do not remove it.
- **`service_running` in preferences goes stale-true** after a force-kill. Use
  `AbrpUploadService.isRunning()`.
- **Permission changes fail CI** until allowlisted with a justification.

## Honesty requirements

Most of this cannot be verified off a vehicle. When reporting work, state plainly what was
tested and what was not. "Unit tests pass, not tried on the car" is the expected and
acceptable answer. Do not imply road-testing that did not happen.
