# Architecture

SaigoCam is deliberately small. This page is the map; the code comments hold the detail.

## Components

```
Phone                                                     PC
┌───────────────────────────────────────────────┐        ┌──────────────────────────┐
│ MainActivity (Jetpack Compose)                │        │ OBS (VLC / Media Source) │
│   Stream tab: preview, settings, start/stop   │  RTSP  │ ffplay                   │
│   Setup Guide tab: URLs, password, OBS steps  │◄──────►│ receiver/ (prototype)    │
│        │ bind                                 │ :1935  └──────────────────────────┘
│        ▼                                      │
│ StreamingService (foreground service)         │
│   owns RtspServerCamera2                      │
│   state as StateFlows for the UI              │
│   auth, Wi-Fi lock, thermal, lifecycle        │
│        │                                      │
│        ▼                                      │
│ RootEncoder + RTSP-Server (pedroSG94)         │
│   Camera2 → OpenGL → MediaCodec H.264 → RTSP  │
└───────────────────────────────────────────────┘
```

| File | Role |
|---|---|
| `app/src/main/java/io/github/lsd_dev/saigocam/MainActivity.kt` | UI only. Binds to the service and renders its state. The preview `OpenGlView` is handed to the library; when its surface goes away the service decides whether to keep the camera running. |
| `app/src/main/java/io/github/lsd_dev/saigocam/StreamingService.kt` | Everything stateful: camera/encoder configuration, start/stop, RTSP auth, connection callbacks, Wi-Fi lock, thermal status, notifications, service lifecycle. |
| `app/src/main/java/io/github/lsd_dev/saigocam/StreamSettings.kt` | Pure functions (resolution choice, bitrate, password, URL) with unit tests in `app/src/test/`. |
| `receiver/` | C prototype of a minimal-buffering desktop viewer. See its README. |
| `tools/latency-clock/`, `docs/LATENCY.md` | The measurement method. |

## Data flow while streaming

1. Camera2 delivers frames to an OpenGL surface (the library's `GlStreamInterface`), which
   also feeds the on-screen preview.
2. MediaCodec encodes H.264 (Constrained Baseline when the phone supports it: no B-frames, so
   decoders never reorder). CBR where supported.
3. The RTSP server packetizes to RTP and serves every connected client. A 5-frame cache is
   kept and cleared when a client connects or the bitrate collapses.
4. On the PC, the client depacketizes, decodes and displays. This is where most buffering
   happens today and why `receiver/` exists.

## Latency-relevant decisions

| Decision | Reason |
|---|---|
| Keyframe every 1 s + `requestKeyFrame()` when a client connects | Instant decode start without the bitrate cost of all-intra. The old all-intra mode remains as a switch for A/B tests. |
| Constrained Baseline profile | No B-frames, so no decoder reordering delay. |
| Video only | Audio would add a sync buffer; PC audio sources are better anyway. |
| Encoder prepared **before** the preview starts | The camera session takes its size/fps when it opens and the library doesn't reconfigure a running session. |
| Wi-Fi low-latency lock while streaming | Wi-Fi power save batches packets into latency spikes. |
| Sustained performance mode | Steadier clocks → steadier frame times (p90), at a small cost in peak speed. |

## Lifecycle

- The Activity only **binds** to the service, so with no stream running the service dies with the UI.
- `startStreaming()` also calls `startService`, making the service "started" so the stream
  survives the UI closing. `stopStreaming()` calls `stopSelf()`.
- `onStartCommand` returns `START_NOT_STICKY`: a restart after the process is killed would
  have no camera and no UI.
- When the preview surface is destroyed and nothing is streaming, the camera is closed
  explicitly (`stopCamera()`), because the library's `stopPreview()` is a no-op in background mode.

## Security model

RTSP Basic auth on by default (username `saigocam`, random password). Credentials and video are
not encrypted on the wire. See `SECURITY.md`. Encryption and pairing are roadmap items.

## What is **not** verified yet

Everything in Phase 0 of the roadmap was written against the libraries' source code but has
not been compiled or run on a device at the time of writing. Treat the behaviour described
here as intended until CI and a phone confirm it.
