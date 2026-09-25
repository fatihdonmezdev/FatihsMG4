# Contributing

This app runs on a car. That single fact drives everything below.

## Ground rules

1. **Telemetry never writes to the vehicle.** The opt-in SWI69 WinClose integration is
   the only write exception: four-window closing, gated on P and readable 0 km/h,
   with a bounded pulse and STOP on completion, interruption or failure.
2. **A property that cannot be read is omitted, never defaulted.** Sending 0 for an
   unreadable battery level tells ABRP the car is empty and corrupts the user's route
   plan. Nullable in, absent from the payload.
3. **Fail closed on anything security-shaped.** Unknown update origin, mismatched
   signature, unreadable state: refuse, do not guess.
4. **Say what you did not verify.** Most of this code can only be fully tested on a
   vehicle. "Builds and unit tests pass, not tried on the car" is a perfectly good PR
   note. Silence implying it was road-tested is not.

## Before opening a PR

```bash
mise run check      # permission gate + lint + unit tests
```

or, without mise:

```bash
bash .github/security/check-permissions.sh
./gradlew testStableDebugUnitTest testUnstableDebugUnitTest lintStableDebug
```

New behaviour needs a unit test. The pure-logic classes — `TelemetryPayload`,
`UploadCadence`, `UploadLog`, `OtaUpdater` — are deliberately free of Android types so
they can be tested on the JVM; put testable logic there rather than in the service.

## Permissions

Any new `uses-permission` fails CI until it is added to
`.github/security/permission-allowlist.txt` **with a justification comment**. This is
deliberate: an app on someone's car should not gain capabilities quietly.

## Channels

- `stable` — tagged releases, no self-update, no updater code in the APK.
- `unstable` — automated pre-releases that update themselves.

If you touch the updater, remember it installs code on a vehicle: the origin allowlist and
the signature check are the whole defence.

## Ways to help

Not every useful contribution is code:

- **Bug reports** — firmware generation, app version, what you did, what happened.
- **Feature requests** — describe the problem before the solution.
- **Documentation** — README, this guide, translations.
- **Pull requests** — see below.
- **Testing pre-releases** — install an `unstable` build and report what broke.
- **Sponsorship** — see below.

## Contributing efficiently

Maintainer time and AI quota are the scarce resources here, ideas are not. If you have
access to Claude Opus or another capable coding model, a finished pull request is worth
much more than a feature request: someone still has to design, write, test and verify the
request, and that someone has a limited quota too.

A pull request that lands quickly usually carries:

- The problem, in one or two sentences.
- The proposed solution, and what you rejected.
- The implementation, scoped to one concern.
- Tests — `mise run check` passes.
- Documentation updated: README, CHANGELOG, this guide where relevant.

Generate the change locally with whatever model you have, then read every line yourself
before opening the PR. You are the author, not the model. Unreviewed generated code on a
path that reaches the vehicle will be sent back.

## Sponsorship

Maintaining EVSuite costs development time, test hardware and AI usage. If it is useful in
your daily driving, consider sponsoring through
[GitHub Sponsors](https://github.com/sponsors/malys). Sponsorship covers those costs and
gets fixes and features out faster.

## Commit messages

Explain **why**, not what — the diff already says what. If you fixed something subtle,
say what the failure looked like, so the next person recognises it.

## Licensing

Read [`LICENSE.md`](LICENSE.md) first. The project's licence status is unresolved because
the upstream fork source has none. Contributions are accepted on the understanding that
they are offered under MIT once that is settled.
