# Privacy

SaigoCam is designed so that there is nothing to disclose: no account, no analytics, no crash
reporting, no advertising, and no servers of ours. This page describes what the app actually
does with data, based on its source code, which anyone can check.

## What leaves your phone

- **The camera video**, as an RTSP stream, to clients that connect to your phone on your local
  network (or over USB). Nothing is sent unless a client connects, and the app never connects
  out to anything itself.
- Nothing else. The app contains no analytics, telemetry or crash-reporting code, and no
  third-party SDK that phones home. We are not aware of any network activity by the streaming
  libraries other than serving the stream.

The video stream is **not encrypted** (see [SECURITY.md](SECURITY.md)). Anyone on the same
network who can capture traffic could see it. Use trusted networks or USB.

## What is stored on your phone

In the app's private storage, which other apps can't read on an unrooted device:

| Data | Why | Notes |
|---|---|---|
| Stream password and the on/off switch for it | So the password survives app restarts | Excluded from Android cloud backup and device-to-device transfer |

Nothing else is stored. No recordings, no logs of who connected, no images.

## Permissions and why

| Permission | Used for |
|---|---|
| Camera | Capturing the video you stream |
| Internet, network/Wi-Fi state | Running the RTSP server and showing your phone's IP address in the Setup Guide |
| Foreground service (camera) | Keeping the stream running while the app is in the background |
| Notifications | The persistent "Streaming active" notification. Optional: the app works if you decline |
| Wake lock | Holding the Wi-Fi radio in low-latency mode while streaming |

The app does **not** request the microphone (it streams video only), location, contacts,
storage or any other permission.

## Builds

The APKs published through GitHub Actions are built from this repository's source by the
workflow in `.github/workflows/`. The build is configured not to include the dependency
metadata block that Google Play uses.

## Changes

If any of this ever changes, this file changes in the same commit, and the change is called
out in the release notes.
