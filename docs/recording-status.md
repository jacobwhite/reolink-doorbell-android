Native NVR recording status
===========================

The RLN8-410 user confirmed new recordings after adding POSIX seconds to the BCMedia keyframe extension, using the NVR-synchronized camera clock. Live video alone was not sufficient evidence of recording.

The recording screen still fails to load in the reported client workflow. This change does not claim to fix that screen or implement camera-local storage/playback.

Reference: https://github.com/QuantumEntangledAndy/neolink/blob/master/crates/core/src/bcmedia/model.rs (BcMediaIframe.time).
