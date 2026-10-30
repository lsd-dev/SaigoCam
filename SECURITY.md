# Security Policy

> Maintainer: the items marked `TODO` below are decisions only you can make. Fill them in
> before the first public release.

## Reporting a vulnerability

Please report security problems privately, not in public issues or pull requests.

- Preferred: GitHub's private vulnerability reporting ("Security" tab → "Report a vulnerability").
  `TODO: enable it in the repository settings (Settings → Code security → Private vulnerability reporting).`
- `TODO: optionally add a contact address here.`

Include what you found, how to reproduce it, and the app version or commit. We will confirm
receipt and keep you informed as we work on a fix. `TODO: state a response-time target if you
want to commit to one.`

## Supported versions

`TODO: once there are releases, say which ones receive security fixes (usually only the latest).`

## What SaigoCam's security model is

SaigoCam runs an RTSP server **on your phone**, reachable by any device on the same network
(or over USB with `adb forward`). Things to know:

- **Password protection is on by default** (RTSP Basic authentication, username `saigocam`,
  a random password generated on first run). It can be turned off in the Setup Guide tab;
  then anyone on the network can watch the camera.
- **The stream and the password are not encrypted.** RTSP Basic auth sends the credentials
  Base64-encoded, which anyone capturing traffic on the network can read. The video is sent in
  the clear too. Use SaigoCam on networks you trust, or over USB. Encrypted transport is on the
  [roadmap](ROADMAP.md).
- The password is stored in the app's private preferences and excluded from Android cloud backups.
- Changing or disabling the password applies to new connections; clients already watching are
  not disconnected.
- The app makes no network connections of its own. It only listens for incoming RTSP clients.

## Scope

In scope: anything that lets someone view the camera without the password, crash or control
the app remotely, or read the password from the network or device beyond what is described above.

Out of scope: the fact that RTSP Basic auth and the video are unencrypted (known, documented,
and planned work), and attacks that require a rooted phone or physical access to an unlocked device.
