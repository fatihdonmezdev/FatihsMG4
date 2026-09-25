# Security policy

This app runs on a car. It reads vehicle data and holds credentials for a third-party
service, so security reports are welcome and taken seriously.

## Reporting a vulnerability

Please **do not** open a public issue for a vulnerability. Use GitHub's
[private vulnerability reporting](../../security/advisories/new) instead.

Include what you were able to do, on which firmware generation, and whether the vehicle
was moving. A proof of concept helps; a working exploit is not required.

## What is in scope

- Anything that lets another app on the head unit read the stored ABRP credentials.
- Anything that gets attacker-controlled code installed through the unstable updater.
- Any vehicle write outside the explicitly enabled SWI69 WinClose feature, or a window
  close that bypasses P/readable-zero-speed checks, exceeds its five-second pulse, or
  fails to attempt STOP on every window after interruption or failure.
- Telemetry that leaks credentials into logs, URLs or crash reports.

## What is not in scope

- Requiring physical access to an unlocked head unit with developer mode enabled.
- The stable channel not auto-updating. That is deliberate; see `LICENSE.md` and the
  README's channel table.
- Vulnerabilities in the OEM firmware itself. Report those to SAIC.

## Design decisions you should know about

- Credentials live in `EncryptedSharedPreferences` and are sent in a POST body, never a
  URL. `allowBackup` is off and both preference files are excluded from backup rules.
- The unstable updater accepts an APK only over https, only from an exact-match GitHub host
  allowlist, and only when its signing certificate matches the running app's. All three
  checks fail closed. The stable channel does not contain the updater at all.
- The VHAL probe helpers, which enumerate the whole vehicle abstraction layer, exist only
  in debug builds.
- Window closing uses WinClose's SWI69 CarState callback and VSM commands. Its service is
  not exported, has its own opt-in switch, and holds no wake lock or alarm. It does not
  assert that a sensorless window has physically closed or that anti-pinch is available.
