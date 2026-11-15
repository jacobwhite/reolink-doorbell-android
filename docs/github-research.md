# GitHub projects evaluated

Reviewed 2026-11-14 for an Android camera that an RLN8-410 (3.5.1.368) accepts natively.

| Project | License | Useful pieces | Important limits |
|---|---|---|---|
| [DigitallyRefined/android-ip-camera](https://github.com/DigitallyRefined/android-ip-camera) | MIT | Existing Android capture, H.264/AAC, RTSP/ONVIF, motion detection, recording and controls | No native Baichuan camera server. **Selected as app foundation.** |
| [mblakley/camera-simulator](https://github.com/mblakley/camera-simulator) | MIT | Camera-side Python Baichuan handshake, encryption, BCMedia framing, HTTP CGI simulator | Source handles login, UID and recording replay; not a complete native live doorbell or tested RLN8-410 replacement. Useful protocol reference. |
| [starkillerOG/reolink_aio](https://github.com/starkillerOG/reolink_aio) | MIT | Detailed Baichuan commands, authentication, XML, visitor events, stream decoding | Client-side library; cannot simply run it as a camera. Selected as reference and independent test client. |
| [QuantumEntangledAndy/neolink](https://github.com/QuantumEntangledAndy/neolink) | AGPL-3.0 | Rust Baichuan model, stream framing and protocol research; Wireshark dissector | Camera-to-RTSP bridge, the opposite direction. Used to inspect protocol structures; source not vendored. |
| [borexola/neolink.net](https://github.com/borexola/neolink.net) | AGPL-3.0 | C# Baichuan client, talk/events and fake-camera testing tools | Bridge/NVR software, not an Android native-camera replacement. |
| [pedroSG94/RootEncoder](https://github.com/pedroSG94/RootEncoder) + [RTSP-Server](https://github.com/pedroSG94/RTSP-Server) | Apache-2.0 | Android encoding/streaming library and RTSP server plugin | Good alternative media foundation; does not solve Baichuan. Existing full Android app avoids assembling this separately. |
| [klaushofrichter/cam-sim](https://github.com/klaushofrichter/cam-sim) | MIT | Rich Reolink API simulator, ONVIF, recording-download Baichuan server | Models other cameras; simulation tests are not evidence of native NVR pairing. |
| [extinguish/onvif-camera](https://github.com/extinguish/onvif-camera) | No SPDX license reported by GitHub | Older Android ONVIF example | Old project, ONVIF only; not selected for reuse. |

## Exact revisions inspected

```text
DigitallyRefined/android-ip-camera  2e8baac6a98ee766deb2458e24722652646b13e3
mblakley/camera-simulator          1cdeacfcf2c0e4db479ae4925caf65bf8914a264
starkillerOG/reolink_aio           9f80a4c67e2a665210f19b1e31df88aa43fa2cc8
QuantumEntangledAndy/neolink       6e05e7844b5b50f89787d30bffcbbd3471bfcfde
borexola/neolink.net               c4e7f67873b353896f090717fd4637313663186f
pedroSG94/RootEncoder              329d631b9c5e92762da7f1e3fc37c4a7358618be
pedroSG94/RTSP-Server              3b54a59bf21e6d0e337c67516fdd97bb7eed23f7
klaushofrichter/cam-sim            4bbaf2fe5e4c0d38b987c8ccb34d10ce76b199d9
```

No inspected project establishes all-feature native doorbell emulation on the requested NVR. The useful combination is Android IP Camera for capture and standard protocols, with a camera-side Baichuan layer informed by the simulator and client implementations. NVR compatibility still needs hardware testing and additional command coverage.

Reolink's [official third-party integration guide](https://support.reolink.com/articles/360004384713-Guide-on-Integrating-Third-Party-Cameras-with-Reolink-NVRs/) describes ONVIF pairing separately, requires WSSE and standard H.264, and does not guarantee third-party compatibility. ONVIF support is not evidence of native identity or doorbell feature parity.

## Doorbell follow-up

User has no physical Reolink camera/doorbell for reference captures. Additional source inspected: https://github.com/alzgamer-kr/pyneolink/blob/main/pyneolink/core/discovery.py. Its discovery client confirms C2D_S and UDP 2000 probes but treats legacy binary replies as opaque data; it does not establish camera descriptor fields for the emulator. No source from this project was copied into the app.

The current legacy advertisement uses descriptor values measured from two RLN8-410 NVRs. Those fields have not been validated for an IPC/doorbell and must not be described as verified camera identity. Both NVRs issue searches, but neither has opened a native TCP session with the emulator.

After the user re-added the ONVIF camera, NVR 192.0.2.10 requested GetEventProperties and started RTSP playback but did not create a PullPoint subscription. Further real button presses logged zero subscribers. This is not evidence that a Visitor message was delivered to and ignored by the NVR. Response status/authentication diagnostics are needed to distinguish event negotiation failure from capability filtering.
