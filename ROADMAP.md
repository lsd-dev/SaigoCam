# Roadmap

The goal: the lowest-latency, most trustworthy phone-as-webcam app, fully open source.
Latency claims are only made from measurements (see [`docs/LATENCY.md`](docs/LATENCY.md)).

Status key: ✅ done · 🧪 written, not yet built or verified · 🔜 next · 💡 planned · ❓ needs a decision

## Phase 0: trustworthy baseline

| Status | Item |
|---|---|
| 🧪 | Password-protected RTSP by default; keep the server up when a client disconnects; no Activity leak; notifications optional |
| 🧪 | Camera opened at the chosen resolution/fps (not the library default); keyframe on connect instead of every frame; 60 fps option |
| 🧪 | Wi-Fi low-latency lock, sustained performance mode, thermal warning |
| 🧪 | Camera released when not in use; service stops when idle; resolutions limited to both cameras |
| 🧪 | Unit tests (`StreamSettingsTest`), screenshot test, CI runs tests and lint |
| ✅ | Glass-to-glass latency method and clock page |
| 🔜 | **Put the project in git, get CI green, test on a real phone** |
| 🔜 | **First baseline measurements**: VLC Source vs Media Source vs ffplay vs receiver; keyframe modes; 30 vs 60 fps; Wi-Fi vs USB |
| ✅ | Application ID / package name: `io.github.lsd_dev.saigocam` (never change after the first release) |

## Phase 1: own the receiving end

The PC side (VLC/OBS buffering) is where we expect most of the delay. Expectation, not yet measured.

| Status | Item |
|---|---|
| 🧪 | Desktop receiver prototype (`receiver/`): FFmpeg + SDL2, no queues, optional hardware decode. CI builds it (Linux check, Windows artifact) |
| 💡 | OBS source plugin built on the receiver's pipeline |
| 💡 | Own RTP depacketizer (reads the marker bit) to avoid the libavformat parser hold |
| 💡 | Virtual webcam without OBS: Windows, macOS camera extension, Linux `v4l2loopback` |
| 💡 | Live latency readout: capture timestamps in the stream + clock sync on a control channel |

## Phase 2: setup in seconds, and safe

| Status | Item |
|---|---|
| 💡 | Automatic discovery (mDNS/NSD) and a QR code of the stream URL |
| 💡 | Pairing that sets up real encryption (TLS/DTLS or SRTP keyed by the pairing) |
| 💡 | Remembered devices, automatic reconnect on both sides |

## Phase 3: phone-side pipeline

| Status | Item |
|---|---|
| 💡 | Encoder low-latency options the current library doesn't expose (`KEY_LATENCY`, `KEY_PRIORITY`, intra-refresh, CBR), via a fork or our own camera → encoder → network code |
| 💡 | Real-time priority for encoder and network threads |
| 💡 | SRT transport for Wi-Fi; UDP mode that drops late frames |
| 💡 | H.265 where supported; adaptive bitrate on poor links |
| 💡 | Direct camera → encoder path (skip the GPU step) when no effects are needed |

## Phase 4: features

| Status | Item |
|---|---|
| 💡 | Landscape and rotation support |
| 💡 | Manual camera controls: focus, exposure, ISO, white balance, zoom, torch, lens selection |
| 💡 | Auto-framing (face-following digital pan/zoom) |
| 💡 | PC hotkeys for camera controls over the control channel |
| 💡 | Optional low-latency audio (Opus) with A/V sync |
| 💡 | Local high-quality recording while streaming |
| 💡 | Several phones at once, switchable from the OBS plugin |
| 💡 | Heat handling: drop resolution/fps automatically on thermal throttling |

## Open-source readiness

| Status | Item |
|---|---|
| ✅ | GPLv3 licence; CONTRIBUTING, SECURITY (with TODOs), PRIVACY; issue and PR templates |
| 🧪 | Template leftovers removed from the build |
| 💡 | Signed releases on GitHub Releases; F-Droid listing (check its rules on JitPack dependencies early) |
| 💡 | Licence review of all dependencies by whoever owns legal review |
| 💡 | Public benchmark page with measured numbers per phone |
| ❓ | Code of conduct (maintainer's choice of text) |
