# Project Audit Report

Date: 2026-04-03
Project: MusicPlayerPro / HiFi Player
Audited by: Codex

## Scope

This audit covered:

- Android app architecture and build configuration
- Kotlin application code and service lifecycle
- Native JNI/audio-engine integration
- Persistence layer (Room + SharedPreferences)
- DLNA/network playback path
- Test/build verification

## Executive Summary

The project is technically ambitious and already has several strong foundations: a custom native audio engine, meaningful JVM unit coverage around playback logic, successful debug/release builds, and a clear attempt to separate playback state transitions from Android framework glue.

The main risks are not in the core playback concept, but in reliability and maintainability around the app shell:

- The DLNA path has at least one concrete correctness bug and a few robustness gaps.
- Playlist persistence is vulnerable to partial writes, destructive schema resets, and weak relational guarantees.
- The activity layer owns too many responsibilities, which raises regression risk.
- The build pipeline is functional, but showed Windows/KAPT instability and currently suppresses an unsupported `compileSdk` warning.

Overall assessment: solid prototype / advanced hobby product, but not yet production-hardened.

## What Is Working Well

- Native engine integration is encapsulated behind `AudioEngine` and guarded in JNI with a global mutex (`src/jni/audio_engine_jni.cpp`).
- Playback behavior is partially modeled as pure Kotlin logic with unit tests (`PlaybackStateMachine`, shuffle/order/restore tests).
- Release hardening is enabled through R8/resource shrinking (`app/build.gradle.kts`).
- Both `assembleDebug --no-daemon` and `assembleRelease --no-daemon` completed successfully during this audit.
- `./gradlew.bat test` passed.

## Findings

### High 1: DLNA control URL resolution breaks on default ports

Evidence:

- `app/src/main/java/com/aiproject/musicplayer/DlnaDiscovery.kt:139-148`

Problem:

- `resolveControlUrl()` builds `base` with `${u.host}:${u.port}` even when `u.port == -1`.
- For a common device-description URL such as `http://server/desc.xml`, the computed base becomes `http://server:-1`, which is invalid.

Impact:

- DLNA discovery may succeed but browsing can fail against standards-compliant servers that use default HTTP/HTTPS ports.

Recommendation:

- Resolve `controlURL` with `URL(location)`/`URI.resolve(...)` semantics and omit the port when it is unspecified.

### High 2: Playlist persistence is not transaction-safe and can be destructively reset

Evidence:

- `app/src/main/java/com/aiproject/musicplayer/db/Entities.kt:13-20`
- `app/src/main/java/com/aiproject/musicplayer/db/Daos.kt:23-30`
- `app/src/main/java/com/aiproject/musicplayer/db/MusicDatabase.kt:19-25`
- `app/src/main/java/com/aiproject/musicplayer/MainActivity.kt:1347-1362`
- `app/src/main/java/com/aiproject/musicplayer/MainActivity.kt:1505-1507`

Problem:

- `playlist_tracks` has no foreign key, no explicit index, and no stored track order column.
- Save/delete operations are executed as multi-step UI-driven sequences instead of a single Room transaction.
- `fallbackToDestructiveMigration()` will silently wipe saved playlists on schema changes.

Impact:

- Partial playlist saves/deletes can leave inconsistent state.
- Orphaned `playlist_tracks` rows are possible.
- User data durability is weak for a media app that explicitly promotes playlist persistence.

Recommendation:

- Introduce relational integrity (`ForeignKey`, indexes, explicit order column).
- Move save/load/delete into transactional DAO/repository methods.
- Replace destructive migration fallback with real migrations.

### Medium 3: Remote playback startup is owned by the Activity instead of the Service

Evidence:

- `app/src/main/java/com/aiproject/musicplayer/MainActivity.kt:917-926`
- `app/src/main/java/com/aiproject/musicplayer/DlnaPlaybackCache.kt:11-48`

Problem:

- DLNA cache resolution/download happens inside `MainActivity.lifecycleScope` before playback is handed to `PlaybackService`.
- If the activity is recreated or backgrounded during that work, remote playback startup can be interrupted.

Impact:

- Network playback is less reliable than local playback.
- Background/lock-screen behavior is coupled to UI lifecycle instead of playback lifecycle.

Recommendation:

- Move remote URI resolution and cache/download policy into the service or a dedicated playback repository owned by the service.

### Medium 4: Gapless preload path is inconsistent across supported formats

Evidence:

- Current-track loading supports DSD: `src/jni/audio_engine_jni.cpp:69-76`
- Next-track preload does not: `src/jni/audio_engine_jni.cpp:196-210`

Problem:

- `loadFileFd()` attempts FLAC, MP3, WAV, and DSD.
- `loadNextFileFd()` only attempts FLAC, MP3, and WAV.

Impact:

- Gapless preloading silently excludes DSD tracks even though DSD is advertised as supported.

Recommendation:

- Add `DsdDecoder` handling to `loadNextFileFd()` or explicitly document the limitation in UI/README.

### Medium 5: Build pipeline is functional but not yet stable or fully future-proof

Evidence:

- `gradle.properties:4`
- Observed during audit:
  - `./gradlew.bat test` succeeded, but Kotlin daemon fell back after a `FileNotFoundException` in KAPT incremental data.
  - `./gradlew.bat assembleDebug` initially failed on Windows because Gradle could not delete a KAPT cache directory.
  - `./gradlew.bat assembleDebug --no-daemon` then succeeded.
  - `./gradlew.bat assembleRelease --no-daemon` succeeded.

Problem:

- The project currently suppresses the unsupported `compileSdk 35` warning.
- KAPT/daemon/cache behavior is flaky enough to hurt local productivity and CI confidence.

Impact:

- Reproducibility is weaker than the successful final build result suggests.
- Future Gradle/AGP upgrades may be harder than expected.

Recommendation:

- Align AGP/Kotlin/Compose/compileSdk onto a fully supported matrix.
- Reduce KAPT exposure where possible.
- Add a clean CI path using `--no-daemon` or isolated runners until root cause is fixed.

### Medium 6: Service restart policy is weaker than the app positioning suggests

Evidence:

- `app/src/main/java/com/aiproject/musicplayer/PlaybackService.kt:108-111`

Problem:

- `onStartCommand()` returns `START_NOT_STICKY`.

Impact:

- If the process is killed during playback, Android will not automatically recreate the service.
- This is at odds with the app's "always alive" product messaging.

Recommendation:

- Re-evaluate restart semantics for active playback sessions and document the intended behavior clearly.

### Low 7: Backup/privacy defaults are permissive

Evidence:

- `app/src/main/AndroidManifest.xml:15-21`
- `app/src/main/java/com/aiproject/musicplayer/MainActivity.kt:294-412`

Problem:

- `android:allowBackup="true"` is enabled.
- The app stores playlist state, bookmarks, shuffle history, library folders, and other listening metadata in SharedPreferences.

Impact:

- Personal listening/bookmark metadata may be included in device backups unless controlled elsewhere.

Recommendation:

- Add explicit backup rules or disable backups if that metadata should remain local-only.

### Low 8: DLNA cache has no eviction policy and uses hash-based file names

Evidence:

- `app/src/main/java/com/aiproject/musicplayer/DlnaPlaybackCache.kt:15-58`

Problem:

- Cached audio files are kept indefinitely.
- Cache filenames are derived from `trackUri.toString().hashCode()`.

Impact:

- Storage can grow without bounds.
- Hash collisions, while uncommon, can alias two different remote tracks.

Recommendation:

- Add LRU/TTL cleanup and switch to a collision-resistant cache key.

## Architecture Observations

- `MainActivity.kt` is 1,937 lines / ~105 KB.
- `MainActivitySections.kt` is ~53 KB.
- `PlaybackService.kt` is 615 lines.

Interpretation:

- The UI layer currently owns service binding, persistence helpers, SAF traversal, MediaStore scanning, network-playback prep, and playback orchestration.
- This makes recomposition behavior, lifecycle behavior, and persistence behavior harder to reason about and test independently.

Recommended direction:

- Introduce a `ViewModel` + repository split.
- Move persistence and playback orchestration out of composables/activity-local helpers.
- Leave the activity mostly responsible for UI composition and Android permission/result plumbing.

## Test Coverage Assessment

Observed:

- 15 JVM unit test files exist under `app/src/test`.
- No `app/src/androidTest` directory is present.
- No instrumentation/UI tests were found for SAF, MediaSession/notification behavior, service lifecycle, or DLNA.
- No native-engine automated test harness was found.

Assessment:

- Pure Kotlin logic coverage is meaningfully better than average for a project of this size.
- Integration coverage is still thin in the highest-risk areas.

Recommended next tests:

- Instrumentation tests for `PlaybackService` lifecycle and media controls
- SAF import/browse regression tests
- DLNA parsing/resolution tests, especially default-port and malformed-XML cases
- Native smoke tests for format loading and gapless transitions

## Verification Performed

Commands executed during the audit:

- `./gradlew.bat test`
- `./gradlew.bat assembleDebug`
- `./gradlew.bat --stop`
- `./gradlew.bat assembleDebug --no-daemon`
- `./gradlew.bat assembleRelease --no-daemon`

Results:

- Unit tests passed.
- Debug build passed after daemon reset and `--no-daemon`.
- Release build passed with `--no-daemon`.

## Priority Action Plan

1. Fix DLNA `controlURL` resolution and add tests for default-port servers.
2. Replace destructive Room migration behavior and make playlist persistence transactional.
3. Move remote playback preparation out of `MainActivity` and into service-owned code.
4. Normalize gapless support behavior across all advertised formats, including DSD.
5. Reduce build fragility by stabilizing the Gradle/KAPT toolchain and removing warning suppression where possible.
6. Start carving `MainActivity` into ViewModel/repository/service-facing layers.

## Final Assessment

This is a capable and interesting audio player with real engineering effort behind the playback core. The next maturity step is not "more features"; it is operational hardening around persistence, networking, lifecycle ownership, and maintainability boundaries.
