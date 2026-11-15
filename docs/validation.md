# Validation

Tested with an RLN8-410 NVR (N3MB01, firmware v3.5.1.368_25010352) and Android 14.

User-confirmed: native live video, Visitor alarm logging, and Visitor push delivered through the NVR. DeviceInfo/type=smart_bell is necessary for this firmware to expose VISITOR schedules. Enable Visitor in the NVR push schedule. The emulator itself does not register for Reolink cloud push.

Version 0.2.15 adds a fixed 1280×720 output canvas and gravity/sensor-based pixel rotation. Portrait content is fitted with side bars; orientation is retained when the phone is flat. Front/rear rotation, pixel rotation, mirroring, chroma layouts and letterboxing have unit coverage. Build, unit tests and lint passed. A live RTSP frame from the shared H.264 encoder decoded at 1280×720. All physical mounting orientations have not been visually verified on an NVR.

The Android local preview remains visible during streaming. Native OSD queries expose the fixed channel label Doorbell; the NVR channel-list readback confirmed that label after SetOsd. Overlay rendering and arbitrary remote renaming are not implemented.

Known diagnostic limitation: the reolink-aio preview capture path in scripts/probe_native.py encounters a binary-response parsing error with the NVR-compatible preview framing. Authentication and metadata probes pass. Do not treat that client's capture mode as a passing video test.

Local device identifiers, network addresses, credentials, logs, captures and configuration backups are intentionally excluded from this repository.

Version 0.2.16 handles display orientation changes without recreating MainActivity, avoids redundant capture restarts for the same preview surface, and fits the on-device preview without cropping. A stable orientation change requests a keyframe. Rectangle-to-landscape fitting has regression coverage for all four quarter turns. This addresses capture restarts observed during rotation; final user confirmation of the reported distortion is pending.

A direct native TCP capture using the NVR wire signature received 199 video frames at 1280×720 with zero skipped BCMedia bytes. FFmpeg decoded the entire H.264 sample without errors. Phone logs recorded orientation changes without camera restarts during this check. Media samples remain local and are not distributed.

Version 0.2.17 replaces letterboxing with a center crop, selects 1280×720 capture at 15 fps, limits encoded-frame delivery to 15 fps, keeps the local preview attached during streaming, and avoids restarts when additional viewers share a running encoder. Runtime readback confirmed camera AE range [15,15] and the encoder running 1280×720 at 15 fps. Earlier versions produced user-reported NVR color corruption; improvement with this version needs user confirmation and should not be presented as guaranteed resolved.
