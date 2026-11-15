# Doorbell Lab for Android

An experimental Android camera that emulates a native Reolink doorbell over Baichuan TCP. Version **0.2.19**. Unofficial; not a complete Reolink camera replacement.

Tested with **RLN8-410 / N3MB01 / v3.5.1.368_25010352**: native live video, Visitor alarm logging, and Visitor push notifications through the NVR are user-confirmed. Compatibility with other hardware or firmware is unverified.

> Current hardware regression: the tester reports no NVR video with 0.2.19, despite successful direct native decoding. Recovery is under investigation; passing unit tests do not establish NVR playback.

## Features

- Native Baichuan server on TCP 9000 with authenticated login, encrypted control messages, main/substream transport and Visitor events.
- A Ring doorbell button and a local camera preview that stays visible while streaming.
- 1280×720 (16:9), 15 fps H.264 output with sensor/gravity-based upright rotation. The image is center-cropped to fill the frame without stretching; when flat, the last stable orientation is retained.
- RTSP and ONVIF from the upstream Android IP Camera foundation.
- Fixed native channel name **Doorbell**, screen-awake behavior, and front-camera support.

## Setup

1. Build and install the debug APK on Android 9 or later. Grant camera and microphone permissions as needed.
2. Choose the camera and set your own username and password in Settings. Credentials are stored on the device; none are included in this repository.
3. Enable the native listener and streaming services you need. Use the IP address and ports shown by the app.
4. Add the camera inside the NVR using its native protocol and TCP port 9000. The app uses `smart_bell` device class so supported NVR firmware exposes Visitor events.
5. Enable NVR push, channel push, and the **Visitor** push schedule. Notifications are delivered by the NVR, not by a standalone cloud connection from this app.

Discovery is experimental. Direct-IP addition may be required. Refresh the Reolink app after changing channel capabilities.

## Build

Requires JDK 17 and Android SDK platform 36. Configure `ANDROID_HOME` or your own ignored `local.properties`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APKs are generated under `app/build/outputs/apk/debug/`. Release signing requires your own keystore; keys and signing credentials must never be committed.

## Limits

Two-way talk, chime pairing, quick replies, AI person/vehicle/package detection, standalone Reolink cloud push, full camera configuration and native playback/search are not implemented. Some menus may appear from compatibility metadata without a working implementation behind them. Unsupported commands return an error. Main/substream transport is supported; separate substream encoding depends on the capture backend, with a shared-main-stream fallback.

Orientation uses discrete 90-degree turns with stabilization. All physical mounting positions still require visual hardware validation. Stream transport is intended for a trusted LAN and is not TLS-encrypted.

See [validation notes](docs/validation.md) and [protocol research](docs/github-research.md). Diagnostic scripts prompt for passwords or accept explicitly supplied environment variables; do not publish captured media or runtime logs.

## Attribution

Based on [DigitallyRefined/android-ip-camera](https://github.com/DigitallyRefined/android-ip-camera), under MIT. The upstream revision is in `UPSTREAM_COMMIT`; original documentation is in [README.upstream.md](README.upstream.md). See [LICENSE](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md). Reolink is a trademark of its owner; this project is not affiliated with Reolink.
