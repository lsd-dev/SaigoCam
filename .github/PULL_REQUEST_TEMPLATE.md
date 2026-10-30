## What and why

<!-- One or two sentences. Link the issue if there is one. -->

## How it was tested

<!-- Phone model and Android version, viewer on the PC, what you tried. Say clearly what you did NOT test. -->

## Latency (only if this touches capture, encoding, transport, buffering or the receiver)

<!-- Measured with docs/LATENCY.md. Delete this section if not applicable. -->

| | Before | After |
|---|---|---|
| Median (ms) | | |
| p90 (ms) | | |
| Samples | | |

Conditions (phone, resolution/fps, keyframe mode, link, viewer and buffer setting):

Row added to the results table in `docs/LATENCY.md`: yes / no

## Checklist

- [ ] CI is green (build, unit tests)
- [ ] New logic has unit tests where it can be tested without a device
- [ ] New dependencies are listed with their licence (must be GPLv3-compatible)
- [ ] No tracking, analytics or outbound network calls were added (see PRIVACY.md)
- [ ] Docs updated if behaviour changed (README, docs/, PRIVACY.md, SECURITY.md)
