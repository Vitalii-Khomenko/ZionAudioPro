# High-Fidelity Audio Engine Roadmap

## 1. Current Product State

The Android player is no longer at the "technical specification" stage. It is a working app with a custom native playback engine, Kotlin/Compose UI, MediaSession foreground service, persistent playlists/bookmarks, DLNA browsing, shuffle/repeat logic, DSD decoding, ReplayGain, spectrum visualization, and an Oboe-based output path.

Current architectural baseline:
- Native playback core is implemented in C++ with `double` precision through decode, DSP, gain, EQ, ring buffer, and final float handoff to Oboe.
- App control flow is implemented in Kotlin through `MainActivity` → `PlaybackService` → JNI → `AudioPlayer`.
- Playback speed now uses a vendored Sonic-based native time-stretch path, and the app also exposes a persisted 5-band user EQ through the same Kotlin → service → JNI → DSP stack.
- Stability work has become a first-class roadmap item alongside feature development because real-device behavior on Xiaomi/HyperOS and other aggressive Android variants matters as much as feature completeness.

## 2. Completed Milestones

### 2.1 Core Native Engine
- [x] `AudioBuffer` and lock-free `RingBuffer<double>`
- [x] Native decode thread + Oboe callback split
- [x] Persistent Oboe stream that stays alive between tracks
- [x] Gapless next-decoder preloading and atomic decoder handoff
- [x] 64-bit `GainProcessor` smoothing and ReplayGain integration
- [x] 64-bit RBJ `BiquadFilter`
- [x] 64-bit `GraphicEqProcessor` with 5 persisted user bands
- [x] Sonic-backed native speed processor
- [x] Spectrum analyzer with protected shared-state access

### 2.2 Format Support
- [x] FLAC via `dr_flac`
- [x] MP3 via `dr_mp3` with Xing/Info-less VBR recovery
- [x] WAV via `dr_wav`
- [x] DSF/DFF via `DsdDecoder`

### 2.3 Playback UX / Service Layer
- [x] MediaSession + MediaStyle notification controls
- [x] Audio focus state machine with pause, duck, and auto-resume branches
- [x] Mute-first start/stop/switch transitions
- [x] Fast preroll start/resume with silent-buffer clearing before unmute
- [x] Persistent playlist restore by exact `Uri`
- [x] Per-track audiobook bookmarks
- [x] Shuffle queue + history-backed Previous
- [x] Repeat off / one / all
- [x] Sleep timer with final fade window
- [x] Top-bar equalizer dialog with live native updates and reset-to-flat

### 2.4 Library / Source Management
- [x] MediaStore import
- [x] Recursive SAF folder scanning
- [x] Separate app-level Library Folders manager with independent SAF-root persistence and no automatic playlist rebuild from those access roots
- [x] Room-backed named playlists
- [x] DLNA / UPnP browse and add-to-playlist flow
- [x] Safe DLNA caching to temp file before commit

### 2.5 Stability and Security Hardening
- [x] JNI global-player access serialized with `g_playerMutex`
- [x] Oboe reconnect thread converted from detached to managed lifetime
- [x] Oboe reconnect teardown no longer re-enters stream shutdown through nested locking
- [x] Service foreground lifetime hardened for Xiaomi/MIUI focus-loss behavior
- [x] Service callback references cleared on Activity destroy
- [x] Spectrum write/read race fixed
- [x] `Stop -> Next/Play` transport flow hardened with service rebind replay
- [x] Shuffle `Stop -> Next` no longer loses the next-track path after the random queue was cleared by manual stop
- [x] Playlist can now be re-sorted by natural name or by numeric tokens parsed anywhere in filenames
- [x] Saved library roots are now managed separately from the current playlist, and loading a saved playlist skips unreadable tracks instead of failing as an all-or-nothing flow

## 3. Current Known Direction

### 3.1 Playback Speed
- [x] Speed range and UI/state plumbing are complete end-to-end
- [x] High-quality native speed control is now provided through vendored Sonic processing
- [x] `Speech` / `Music` mode plumbing is reused as Sonic profile selection

Rationale:
- The earlier overlap/crossfade and WSOLA-like experiments produced strong audible artifacts and unstable behavior on device.
- The current engine now keeps the custom native path but uses Sonic as the practical quality/stability tradeoff instead of another bespoke stretch implementation.

### 3.2 Equalizer
- [x] User-adjustable 5-band EQ is available in the UI
- [x] EQ state persists across app restarts and service rebinds
- [ ] Manual device tuning and preset packs remain future work

### 3.3 Lock-Screen / Device-Routing Robustness
- [x] Oboe reconnect path hardened for disconnect/reconnect storms
- [ ] Collect device-specific crash logs for Xiaomi/HyperOS lock transitions if field reports continue
- [ ] Add targeted regression coverage or telemetry hooks for route-change transitions where feasible

## 4. Near-Term Roadmap

### Phase A: Playback Reliability
- [ ] Confirm Xiaomi lock-screen stability on real hardware after the latest Oboe reconnect fixes
- [ ] Confirm Samsung A55 de-click behavior after lengthening transport fades/preroll and extending native edge ramps
- [ ] Stress-test repeated screen on/off, headset unplug/replug, Bluetooth handoff, and screen-recording route changes
- [ ] Verify no deadlock or stuck-silence state after rapid pause/resume/next/stop sequences

### Phase B: Speed Control Validation
- [ ] Manually verify Sonic quality on speech and music across 1.1x / 1.25x / 1.5x / 1.75x
- [ ] Continue real-device tuning for the last remaining Sonic acceleration artifacts even after deeper headroom and underrun-softening changes
- [ ] Add native regression tests or offline harnesses for non-1.0x speed processing
- [ ] Tune `Speech` / `Music` Sonic profile defaults from real-device listening feedback

### Phase C: Equalizer Follow-Up
- [ ] Validate 5-band EQ behavior across Bluetooth, wired, and USB outputs
- [ ] Add optional preset packs (Flat / Warm / Vocal / Bright) without replacing manual control
- [ ] Consider per-band Q customization only if the simple 5-band model proves too limiting

### Phase D: Output Path Quality
- [ ] Add direct USB endpoint work beyond the current `UsbAudioEndpoint` stub
- [ ] Improve follow-source sample-rate behavior where Android/Oboe/HAL allow it
- [ ] Consider explicit output format negotiation and diagnostics in the UI

## 5. Mid-Term Roadmap

### Phase E: Library and Playback Intelligence
- [ ] Smarter metadata hydration and background indexing for large libraries
- [ ] Better album / chapter grouping for audiobook-heavy collections
- [ ] Playlist import/export formats
- [ ] Optional reinstall re-grant assistant for Library Folders after Android revokes SAF permissions
- [ ] Search, filtering, and larger-library navigation improvements

### Phase F: QA and Diagnostics
- [ ] Expand JVM regression coverage around service lifecycle and transport edge cases
- [ ] Add reproducible native stress harnesses for decoder swaps, speed changes, and route-change reconnection
- [ ] Add optional debug logging mode for crash reproduction on vendor-specific firmware

## 6. Long-Term Roadmap

### Phase G: Audiophile Output Expansion
- [ ] USB DAC exclusive / direct path implementation
- [ ] Better hardware capability surfacing in UI
- [ ] Optional advanced DSP modules such as dithering/output-format conversion only where they serve the real output path

### Phase H: Cross-Platform Engine Ambitions
- [ ] Reassess Windows / WASAPI / ASIO support only after Android engine maturity is high enough
- [ ] Keep DSP and endpoint abstractions separated so platform expansion remains possible without rewriting decode/DSP logic

## 7. Non-Goals Right Now

These are intentionally not active priorities at the moment:
- Full cross-platform desktop rollout
- Replacing the current Sonic path with another experimental algorithm without clear listening wins
- Broad new feature work that weakens playback stability on real devices
- Large UI redesign unrelated to transport, playback, or diagnostics

## 8. Acceptance Criteria For Upcoming Work

Every next-stage change should satisfy these rules:
- Playback must remain stable on real Android devices, especially Xiaomi/HyperOS variants.
- Native audio changes must compile cleanly through the Android CMake path.
- Transport changes must preserve MediaSession, notification, and foreground-service correctness.
- New DSP work must prove better behavior on device, not just in theory.
- Stability regressions take priority over feature completeness.