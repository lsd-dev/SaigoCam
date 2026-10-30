# Measuring SaigoCam Latency

Every latency change should be measured, not assumed. This is the standard
**glass-to-glass** method: the time from light hitting the phone's camera until
that image appears on the PC screen. It includes every stage: camera, encoder,
network, decoder, OBS and display.

## What you need

- The latency clock: open [`tools/latency-clock/index.html`](../tools/latency-clock/index.html)
  in a browser on the PC. It works offline and sends nothing anywhere.
- OBS showing the SaigoCam feed (VLC Video Source or Media Source; see the README).
- A screenshot tool that freezes the screen instantly, e.g. Windows `Win+Shift+S`.
  A photo of the monitor taken with a second phone also works.

## Procedure

1. Put the browser window with the clock and the OBS preview **side by side on the same monitor**.
   Keep the clock tab visible: browsers pause the clock in background or minimised tabs.
2. Point the phone at the clock so it fills a good part of the frame and the digits are sharp.
3. Wait about 10 seconds after connecting, so start-up buffering has settled.
4. Take a screenshot. It now shows two times: the real clock, and the older time inside the OBS preview.
5. Type both readings into the form on the clock page. It computes the latency for that sample.
6. Repeat until you have **at least 20 samples**, spaced a few seconds apart.
7. Report the **median** and **p90**, not the best sample. Use "Copy as CSV" to save the raw data.

### Accuracy

- Each sample is accurate to about one display refresh (about ±17 ms at 60 Hz), plus the
  camera's exposure time. That's why we use the median of many samples.
- The monitor's own refresh adds the same delay to both readings, so it cancels out.
- Turn off OBS preview scaling filters and anything else that delays the preview.

## Test conditions to record

Latency depends on all of these, so record them with every result:

| Setting | Example |
|---|---|
| App version / commit | `1.0` or a commit hash |
| Phone model, Android version | Pixel 7, Android 15 |
| Resolution, fps | 1280x720, 30 fps |
| Keyframe mode | "Keyframe on connect" (default) or "Every frame" |
| Link | Wi-Fi 5 GHz / Wi-Fi 2.4 GHz / USB (`adb forward`) |
| OBS source and its buffer setting | VLC Video Source, Network Caching 100 ms |
| PC OS, OBS version | Windows 11, OBS 31 |

Change **one** setting at a time between runs.

## Results

No results have been measured yet. Add rows as you measure; never estimate a value.

| Date | Commit | Phone | Res / fps | Keyframes | Link | OBS source (buffer) | Samples | Median (ms) | p90 (ms) |
|---|---|---|---|---|---|---|---|---|---|
| | | | | | | | | | |
