# Third-party notices

The Android application is based on DigitallyRefined/android-ip-camera at the revision in `UPSTREAM_COMMIT`. The original MIT license is retained in `LICENSE`; its README is retained as `README.upstream.md`. The fork adds experimental Baichuan support and modifies build settings, app identity, settings UI, media callbacks and two compatibility guards.

The native protocol implementation was informed by the MIT-licensed `mblakley/camera-simulator` and `starkillerOG/reolink_aio` implementations. Their license texts are included in `third-party/`. Java wire framing and Android adaptation are implemented in this fork; the Python simulator and client libraries are not bundled in the APK.

Neolink and Neolink.NET were inspected as protocol references. Their AGPL source is not vendored or linked into this app. See `docs/github-research.md` for project links and revisions.

Reolink is a trademark of its owner. This is an unofficial experimental emulator, not a Reolink product or firmware image. The local LAB-prefixed device identifier is not a registered Reolink cloud UID.
