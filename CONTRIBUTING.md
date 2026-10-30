# Contributing to SaigoCam

Thanks for helping. SaigoCam's goal is simple to state and hard to do: the lowest-latency,
most trustworthy phone-as-camera app, fully open source. Two rules follow from that:

1. **Measured, not claimed.** Any change that affects latency comes with numbers.
2. **No surprises for users.** No tracking, no accounts, no network traffic other than the stream.

## Before you start

- Check the [roadmap](ROADMAP.md) and open issues so work isn't duplicated.
- For anything bigger than a bug fix, open an issue first and outline the approach.

## Building

The Android app builds with Gradle; Android Studio (a version that supports AGP 9.1) is the
easiest way. From the command line:

```bash
./gradlew :app:assembleDebug
```

Unit tests (they run on the JVM with Robolectric, no device needed):

```bash
./gradlew :app:testDebugUnitTest
```

Lint:

```bash
./gradlew :app:lintDebug
```

Screenshot tests compare against images in `app/src/test/screenshots/`. To record new ones:

```bash
./gradlew :app:recordRoborazziDebug
```

The desktop receiver prototype has its own instructions in [`receiver/README.md`](receiver/README.md).

## Latency changes

If your change touches capture, encoding, transport, buffering or the receiver:

- Measure before and after with the procedure in [`docs/LATENCY.md`](docs/LATENCY.md).
- Change one thing at a time, take at least 20 samples per run, and report the **median and p90**.
- Put the numbers and the test conditions (phone, resolution/fps, link, viewer) in the pull request
  and add a row to the results table in `docs/LATENCY.md`.
- Never estimate a number. If you couldn't measure, say so and explain why.

## Code

- Match the style of the surrounding code (naming, comment density, formatting).
- Comment the *why* for anything non-obvious, especially latency trade-offs.
- Keep logic that can be unit-tested out of Android classes (see `StreamSettings.kt`) and add tests for it.
- Don't add dependencies without a note on their licence (see "Licensing" below).
- Wrap library calls that may fail on some phones in `try/catch` and keep the app usable when they do.

## Pull requests

- One change per pull request, with a short description of what and why.
- Say what you tested and on which phone/Android version. Say clearly what you did **not** test.
- CI builds the APK, runs the unit tests and lint. Keep it green.

## Licensing

SaigoCam is licensed under the GNU GPLv3 (see [LICENSE](LICENSE)). By contributing you agree
that your contribution is licensed under the same terms. Any new dependency must have a
GPLv3-compatible licence; list it and its licence in the pull request.

## Security issues

Please don't open public issues for security problems. See [SECURITY.md](SECURITY.md).
