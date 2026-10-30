# SaigoCam receiver (prototype)

A minimal Windows desktop viewer for the SaigoCam RTSP stream. It does as little
receive-side buffering as possible, so we can measure it against VLC and OBS Media Source.

> **Status: written, NOT built, NOT run.** The machine this was written on has no C
> compiler, CMake, FFmpeg or SDL. No latency has been measured with it, and this README
> gives no latency numbers. Expect small compile fixes on the first build.

- Language: C11, about 1,100 lines in `src/`.
- Libraries: FFmpeg (libavformat, libavcodec, libavutil) for RTSP and H.264, SDL2 for the window.
- Licence: GPLv3, the same as SaigoCam (see [`../LICENSE`](../LICENSE)).

## How it keeps latency low

| Stage | What the receiver does | Why |
|---|---|---|
| Demuxer | `fflags nobuffer`, `probesize 32`, `analyzeduration 0`, no `avformat_find_stream_info` (unless you pass `--probe`) | No start-up probing or extra packet buffering. The SDP already gives us the codec. |
| RTP over UDP | `max_delay 0`, `reorder_queue_size 0` | libavformat otherwise waits up to 100 ms to reorder UDP packets. |
| Transport | `--transport tcp` (default) or `udp` | TCP never loses packets but can stall behind a retransmission. UDP never stalls but can lose packets. Measure both. |
| Decoder | `AV_CODEC_FLAG_LOW_DELAY`, slice threads only | Frame threading holds back one frame per thread. `LOW_DELAY` turns it off (checked in FFmpeg's `libavcodec/pthread.c`). |
| Hand-off | One-frame mailbox between the decode and display threads | No queue. If a new frame is decoded before the last one was shown, the old one is **dropped** and counted. |
| Display | Shown as soon as it's decoded, no pts pacing, vsync **off** by default (`--vsync` turns it on) | Waiting for vsync can add up to one display refresh. |
| Optional | `--hwdec d3d11va` (or `dxva2`, `cuda`, `qsv`, `auto`) | GPU decoding. The frame is copied back to system memory (NV12) for SDL; zero-copy rendering is future work. |

### A finding: libavformat's H.264 parser holds each frame back

For RTP H.264, libavformat turns on its H.264 parser (`need_parsing = AVSTREAM_PARSE_FULL` in
`libavformat/rtpdec_h264.c`). That parser (`h264_find_frame_end` in `libavcodec/h264_parser.c`)
only decides a frame has ended when it sees the **start of the next frame**. So
`av_read_frame()` returns frame N only once frame N+1's first packet has arrived. That costs up
to one frame interval: about 33 ms at 30 fps, about 17 ms at 60 fps.

This is read from FFmpeg's source, **not measured**. OBS Media Source and ffplay both read RTSP
through libavformat, so they would have the same hold. VLC uses its own RTSP client (live555),
so it shouldn't. The hold happens inside `av_read_frame`, so the receiver's own `RX>DEC`
figure can't show it. Only glass-to-glass comparisons can.

**`--early` (experimental)** tries to avoid the hold:

- It uses `fflags noparse+nofillin`, so the parser is skipped.
- It feeds the decoder complete NAL units with `AV_CODEC_FLAG2_CHUNKS`. In that mode FFmpeg's
  H.264 decoder outputs a picture once its last macroblock row is decoded.

libavformat doesn't expose the RTP marker bit or the FU-A end bit. So [`src/early_nal.c`](src/early_nal.c)
guesses that a fragmented NAL unit is complete when a fragment is shorter than the stream's
full fragment size. Packetizers send full-size fragments and a shorter last one.

- If a guess is ever wrong, the overlay counts an **EARLY MISFIRE**. The heuristic then turns
  itself off and falls back to parser-equivalent timing. One frame may show corruption until
  the next keyframe.
- If `--early` misbehaves with SaigoCam's packetizer, the proper fix is our own RTP
  depacketizer that reads the marker bit (see "Next steps").

## Dependencies and licences

| Dependency | Used for | Licence | GPLv3-compatible? |
|---|---|---|---|
| FFmpeg ≥ 5.0: libavformat, libavcodec, libavutil | RTSP/RTP, H.264 decoding | LGPL-2.1-or-later by default. GPL-2.0-or-later if built with `--enable-gpl`. (L)GPL-3.0 with `--enable-version3`. | Yes, **unless the build uses `--enable-nonfree`**. A nonfree build can't be redistributed. Check with `ffmpeg -hide_banner -buildconf`. |
| SDL2 ≥ 2.0.16 | Window, rendering, YUV textures, events | zlib licence | Yes |
| 5x7 bitmap font in `src/overlay.c` | Overlay text | Written for this project, GPLv3 | Yes |
| ffplay (optional, baseline only) | Zero-code comparison | Part of FFmpeg; same licence as the build | Not linked, only run |

Build tools (CMake, GCC/MinGW-w64 or MSVC, Ninja, pkg-config) are not part of the program.

If you ever **redistribute** a binary, also list the libraries bundled with it. For an MSYS2
build that means:

- The MinGW runtime DLLs.
- Everything that FFmpeg build links: `ldd saigocam-receiver.exe` lists them. The MSYS2 FFmpeg
  package links many codec libraries.

The licence of each of those still needs checking. For local testing, none of this matters.

## Get a build without installing anything

GitHub Actions builds the receiver on every change to `receiver/` (workflow **Build Receiver**,
also runnable by hand from the Actions tab). The Windows job uploads an artifact named
`SaigoCam-receiver-windows`: a folder with `saigocam-receiver.exe` and every DLL it needs.
Download it, unzip, and run it from PowerShell as described under "Run". The Linux job is a
compile check only.

## Build locally

First check whether the toolchain is installed:

```powershell
Get-Command cmake, gcc, cl, pkg-config -ErrorAction SilentlyContinue
```

### Option A: MSYS2 UCRT64 (recommended: one package manager supplies everything)

1. Install MSYS2 from <https://www.msys2.org>. Open the **MSYS2 UCRT64** shell.
2. Install the toolchain and libraries:

   ```bash
   pacman -S --needed mingw-w64-ucrt-x86_64-gcc mingw-w64-ucrt-x86_64-cmake mingw-w64-ucrt-x86_64-ninja mingw-w64-ucrt-x86_64-pkgconf mingw-w64-ucrt-x86_64-ffmpeg mingw-w64-ucrt-x86_64-SDL2
   ```

3. Build and run the unit test (adjust the path to your checkout):

   ```bash
   cd /c/Users/<you>/Downloads/Projects/Claude/SaigoCam/receiver
   ```

   ```bash
   cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
   ```

   ```bash
   cmake --build build
   ```

   ```bash
   ctest --test-dir build --output-on-failure
   ```

The program needs the DLLs in `C:\msys64\ucrt64\bin`. Run it from the UCRT64 shell, or add
that folder to `PATH`.

### Option B: Visual Studio (MSVC) and prebuilt packages

1. Install Visual Studio 2022 or its Build Tools with **Desktop development with C++**. This
   includes CMake.
2. Download an FFmpeg **shared** build with headers and import libraries, e.g. a
   `win64-lgpl-shared` or `win64-gpl-shared` zip from
   <https://github.com/BtbN/FFmpeg-Builds/releases>.
   - Unzip it to e.g. `C:\deps\ffmpeg`.
   - Check that `lib\avformat.lib` exists. If it doesn't, use Option A.
3. Download `SDL2-devel-2.x.y-VC.zip` (latest 2.x) from <https://github.com/libsdl-org/SDL/releases>
   and unzip it to e.g. `C:\deps\SDL2`.
4. In **Developer PowerShell for VS 2022**, from the project root:

   ```powershell
   cmake -S receiver -B receiver/build -DFFMPEG_ROOT=C:/deps/ffmpeg -DSDL2_DIR=C:/deps/SDL2/cmake
   ```

   ```powershell
   cmake --build receiver/build --config Release
   ```

   ```powershell
   ctest --test-dir receiver/build -C Release --output-on-failure
   ```

The build copies the FFmpeg and SDL2 DLLs next to `receiver\build\Release\saigocam-receiver.exe`.

### Just the unit test (any C compiler, no libraries)

[`tests/test_early_nal.c`](tests/test_early_nal.c) checks the `--early` heuristic on its own:

```bash
gcc -std=c11 -Wall -Wextra receiver/tests/test_early_nal.c receiver/src/early_nal.c -o test_early_nal
```

## Run

The URL is the one in the app's **Setup Guide** tab. To keep the password out of your
PowerShell history, read it into a variable instead of typing it on a command line:

```powershell
$url = Read-Host "SaigoCam URL"
```

```powershell
.\saigocam-receiver.exe $url
```

The program never prints the password. It shows the URL as `rtsp://saigocam:***@…`, and it
hides any FFmpeg log line that contains the password or an `Authorization` header. The
password is still visible in the process command line to anyone who can list processes on
this PC. RTSP Basic auth is not encrypted on the network either.

| Option | Default | Meaning |
|---|---|---|
| `--transport tcp\|udp` | `tcp` | RTSP lower transport |
| `--hwdec NAME` | none (software) | `d3d11va`, `dxva2`, `cuda`, `qsv` or `auto`. Falls back to software if it isn't available. |
| `--early` | off | Experimental: skip the H.264 parser hold (see above) |
| `--vsync` | off | Wait for vertical sync when presenting |
| `--renderer NAME` | SDL's default (Direct3D 9 on Windows) | SDL render driver, e.g. `direct3d11`, `direct3d`, `opengl` |
| `--fast` | off | `AV_CODEC_FLAG2_FAST` decoder shortcuts |
| `--probe` | off | Run `avformat_find_stream_info` first (slower start) |
| `--no-overlay`, `--fullscreen`, `--verbose` | | |

Keys:

| Key | Action |
|---|---|
| **O** | Toggle overlay |
| **F** | Toggle fullscreen |
| **Esc** or **Q** | Quit |

If the stream stalls for 5 s, the receiver reconnects with backoff (0.5 s, doubling up to 4 s).

### Overlay and console stats (updated every second)

The same line is printed to the console as `[stats] …`.

| Field | Meaning |
|---|---|
| `FPS IN` | Frames decoded per second |
| `SHOWN` | Frames presented per second |
| `DROPPED` | Total decoded frames replaced in the mailbox before they were shown, because the display fell behind |
| `DEC ERRORS` | Decoder errors (corrupt or lost data) |
| `RX>DEC` | Mean time from `av_read_frame` returning a packet to the decoded frame (including GPU→CPU copy with `--hwdec`). Does **not** include the parser hold. |
| `DEC>SHOW` | Mean time from decoded to `SDL_RenderPresent` returning. Does not include Windows' compositor (DWM) or the monitor. |
| `REORDER` | The decoder's `has_b_frames`. If it's above 0, the decoder is holding frames back for reordering. SaigoCam's Constrained Baseline stream should show 0. |
| `KBIT/S` | Received video bitrate |
| `EARLY MISFIRES` | With `--early` only; see above |

## Zero-code baseline: ffplay

ffplay ships with most Windows FFmpeg builds (e.g. the BtbN zips above). Use the same
low-latency flags as the receiver:

```powershell
ffplay -hide_banner -loglevel warning -window_title "ffplay baseline" -fflags nobuffer -flags low_delay -framedrop -probesize 32 -analyzeduration 0 -sync ext -an -rtsp_transport tcp $url
```

For UDP, use this command instead:

```powershell
ffplay -hide_banner -loglevel warning -window_title "ffplay baseline" -fflags nobuffer -flags low_delay -framedrop -probesize 32 -analyzeduration 0 -max_delay 0 -reorder_queue_size 0 -sync ext -an -rtsp_transport udp $url
```

Keep `-loglevel warning` and `-window_title`. Without them, ffplay prints the full URL,
**including the password**, to the console (stream info at `info` level) and shows it in the
window title.

ffplay still uses the H.264 parser, a packet queue, a small decoded-picture queue and
clock-based display timing. So it's a reference point, not the same pipeline as this receiver.

## Measuring with docs/LATENCY.md

Use the glass-to-glass method in [`../docs/LATENCY.md`](../docs/LATENCY.md) with the clock page
[`../tools/latency-clock/index.html`](../tools/latency-clock/index.html). The only change from
that procedure: the "OBS preview" is now the **receiver window** (or the ffplay window).

1. Open the clock page in a browser. Put the receiver window **next to it on the same
   monitor**, with both windowed: the clock must stay visible.
2. Point the phone at the clock. Make sure the receiver's overlay doesn't cover the clock
   digits in the picture; press **O** to hide it while you take screenshots.
3. Wait about 10 s after connecting. Then take at least 20 screenshots (`Win+Shift+S`),
   a few seconds apart, and enter both readings on the clock page.
4. Report the **median and p90** from "Copy as CSV". Never report the best sample.
5. Record the usual test conditions (phone, resolution/fps, keyframe mode, Wi-Fi or USB). In
   the "OBS source (buffer)" column of LATENCY.md, write the viewer and its flags, e.g.
   `saigocam-receiver tcp`, `saigocam-receiver udp --early`, `ffplay baseline tcp`.
6. Note the `RX>DEC`, `DEC>SHOW`, `DROPPED` and `REORDER` values for each run. They show
   how much of the total happens inside the receiver.

Suggested runs. Keep the phone settings the same, and change one thing at a time:

| # | Viewer | Shows the effect of |
|---|---|---|
| 1 | OBS VLC Video Source, Network Caching 100 ms | Current recommended setup |
| 2 | OBS Media Source | Current alternative |
| 3 | ffplay baseline (TCP) | libavformat with low-latency flags, no code |
| 4 | `saigocam-receiver` (TCP) | No queues or pacing |
| 5 | `saigocam-receiver --transport udp` | TCP vs UDP |
| 6 | `saigocam-receiver --early` | The parser hold. Also repeat at 60 fps: the gap should halve if the finding above is right. |
| 7 | `saigocam-receiver --hwdec d3d11va` | Software vs hardware decode |
| 8 | `saigocam-receiver --renderer direct3d11` and `--vsync` | Presentation path |

Run one viewer at a time, so the phone serves only one client.

## Limitations and next steps

- **Not built or run yet.** First step: build it, run `ctest`, then run against a phone.
- Only `yuv420p`/`yuvj420p` (software decode) and NV12 (hardware decode) are displayed. Other
  formats are reported and skipped. That covers SaigoCam's 8-bit H.264.
- With `--hwdec`, every frame is copied from the GPU to system memory. A D3D11 zero-copy path
  would save that copy.
- `--early` depends on a packet-size heuristic. The robust version is our own RTSP/RTP client,
  or a custom RTP depacketizer, that uses the RTP marker bit. That would also be the base for
  the planned OBS source plugin and virtual webcam.
- Windowed output passes through DWM composition. Exclusive fullscreen or a flip-model
  swapchain may help, but they can't be measured side by side with the clock. Test them
  separately.
- No audio (SaigoCam sends video only).
