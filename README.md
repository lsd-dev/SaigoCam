# SaigoCam

SaigoCam turns your Android phone into a low-latency camera source for OBS Studio (or any RTSP client). It runs an RTSP server directly on the phone and streams the camera (video only, for the lowest latency) to your PC over Wi-Fi or USB.

It is free and open source (GPLv3), with no accounts, no ads and no tracking. See [PRIVACY.md](PRIVACY.md).

> **Status: pre-release.** The current code has been written against the streaming libraries' source but not yet verified on a device, and no latency has been measured yet. This project only makes latency claims from measurements; see [docs/LATENCY.md](docs/LATENCY.md) and the [roadmap](ROADMAP.md).

## 📝 Features
* **Direct OBS integration** using the VLC Video Source or Media Source, with step-by-step guides inside the app.
* **Password-protected by default** (RTSP Basic auth), with a one-tap generated password and URLs that already include it.
* **Wi-Fi or USB.** USB via `adb forward` for the lowest, most stable latency.
* **720p / 1080p, 30 or 60 fps** (60 fps where the camera supports it), front or back camera.
* **Low-latency encoding:** H.264 Constrained Baseline (no B-frames), keyframe on connect, tiny send buffer, Wi-Fi low-latency lock and sustained performance mode while streaming, overheating warning.
* **Measured, not claimed:** a glass-to-glass latency test kit ships with the project.
* Built with Kotlin, Jetpack Compose and the [RootEncoder](https://github.com/pedroSG94/RootEncoder) / [RTSP-Server](https://github.com/pedroSG94/RTSP-Server) libraries.

## 📥 Download and Install
You can download the latest compiled `.apk` without building it yourself:
1. Go to the **Actions** tab in this repository.
2. Click the latest successful **Build Android APK** workflow run.
3. Scroll down to **Artifacts** and download `SaigoCam-debug-apk`.
4. Extract the zip and install the `.apk` on your Android device.

## 🚀 How to Use (OBS Setup)

> **Stream password:** The stream is password-protected by default (RTSP Basic auth). The **Setup Guide** tab shows the generated password, and the URLs it shows include the credentials, e.g. `rtsp://saigocam:<PASSWORD>@192.168.x.x:1935`. You can turn the password off in the same tab, but then anyone on your network can view the camera. RTSP credentials are not encrypted, so only stream on networks you trust.

### Wi-Fi (VLC Video Source, recommended)
1. Ensure your PC and phone are on the same Wi-Fi network.
2. Start streaming in the SaigoCam app.
3. Open OBS Studio → Sources → `+` → **VLC Video Source**.
4. Check **Loop Playlist** (required for auto-connect).
5. Set **Network Caching** to 100 ms.
6. In the Playlist box, click `+` → **Add Path/URL**.
7. Enter the URL shown in the app's Setup Guide tab (e.g. `rtsp://saigocam:<PASSWORD>@192.168.x.x:1935`).

### USB (lowest latency)
1. Enable **USB Debugging** on your Android device and connect it to your PC.
2. Open a terminal on your PC and run:
   ```bash
   adb forward tcp:1935 tcp:1935
   ```
3. Start streaming in the SaigoCam app.
4. In OBS, add a VLC Video Source or Media Source with the URL `rtsp://saigocam:<PASSWORD>@127.0.0.1:1935` (or `rtsp://127.0.0.1:1935` with the password turned off).

### Google Meet, Zoom, Teams
Use OBS's **Virtual Camera** and pick it as the camera in the meeting app. The in-app Setup Guide has the steps. A virtual camera that doesn't need OBS is on the [roadmap](ROADMAP.md).

## ⏱️ Measuring Latency
See [docs/LATENCY.md](docs/LATENCY.md) for the glass-to-glass procedure and the offline clock page in [`tools/latency-clock`](tools/latency-clock/index.html). Please include measured results (median and p90) with any latency-related pull request, or share them with the **Latency measurement** issue template.

## 🖥️ Desktop receiver (prototype)
[`receiver/`](receiver/README.md) holds a minimal-buffering desktop viewer (C, FFmpeg + SDL2) meant to measure how much latency VLC and OBS add on the PC side, and to become the base for an OBS plugin. It has not been run against a phone yet. CI builds it on Linux and Windows and uploads a ready-to-run `SaigoCam-receiver-windows` artifact; its README has local build steps and a zero-code `ffplay` baseline command.

## 🔧 Build from source
Requires a JDK 17+ and either Android Studio (a version that supports AGP 9.1) or the Android SDK on the command line.

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:testDebugUnitTest
```

Release builds are signed only when `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD` (and optionally `KEY_ALIAS`) are set in the environment; otherwise `assembleRelease` produces an unsigned APK.

## 🗂️ Project layout
| Path | What |
|---|---|
| `app/` | The Android app (Kotlin, Jetpack Compose) |
| `receiver/` | Desktop receiver prototype (C) |
| `tools/latency-clock/` | Offline clock page for latency tests |
| `docs/` | [Architecture](docs/ARCHITECTURE.md), [latency method](docs/LATENCY.md) |
| `.github/` | CI workflow, issue and PR templates |

## 🤝 Contributing
See [CONTRIBUTING.md](CONTRIBUTING.md). The [roadmap](ROADMAP.md) lists what's planned. Security issues: [SECURITY.md](SECURITY.md).

## ⚖️ License
This project is licensed under the GNU GPLv3 License - see the [LICENSE](LICENSE) file for details. The RootEncoder and RTSP-Server libraries are Apache-2.0; see each dependency's own licence.
