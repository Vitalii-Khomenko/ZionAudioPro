# Project Audit Report

Date: 2026-04-03
Project: MusicPlayerPro / HiFi Player
Audited by: Codex
Status: Post-remediation audit

## Scope

This audit covered:

- Android build and release pipeline
- Kotlin app architecture and service lifecycle
- Native JNI/audio-engine integration
- Persistence layer (Room + SharedPreferences)
- DLNA/network playback path
- Test/build verification

## Executive Summary

The project is in a materially stronger state than in the initial audit snapshot.

The most important correctness and durability issues identified in the first pass have already been fixed:

- DLNA control URL resolution now handles default ports correctly.
- Remote DLNA playback preparation now runs in `PlaybackService` instead of `MainActivity`.
- Room playlist persistence now has explicit ordering, foreign-key integrity, real migration logic, and transactional writes.
- DSD is now included in next-track gapless preload handling.
- Automatic app backup is disabled, reducing privacy exposure for listening metadata.
- DLNA cache files now use SHA-256-derived keys and are pruned by age/size.

The remaining risks are mostly around toolchain alignment, Windows build ergonomics, app-layer architecture, and missing integration tests.

Overall assessment: advanced prototype with meaningful follow-through on audit findings; remaining work is important, but no longer centered on the previously identified core correctness bugs.

## Resolved Since The Initial Audit

### DLNA path hardening

Resolved in:

- [DlnaDiscovery.kt](C:\Install\AI%20Research\MusicPlayerPro\app\src\main\java\com\aiproject\musicplayer\DlnaDiscovery.kt)
- [DlnaProtocol.kt](C:\Install\AI%20Research\MusicPlayerPro\app\src\main\java\com\aiproject\musicplayer\DlnaProtocol.kt)
- [DlnaPlaybackCache.kt](C:\Install\AI%20Research\MusicPlayerPro\app\src\main\java\com\aiproject\musicplayer\DlnaPlaybackCache.kt)
- [PlaybackService.kt](C:\Install\AI%20Research\MusicPlayerPro\app\src\main\java\com\aiproject\musicplayer\PlaybackService.kt)

What changed:

- `controlURL` resolution now uses proper URL resolution semantics instead of manual host/port concatenation.
- SOAP browse requests now escape `ObjectID`.
- Remote-track preparation moved into the service-owned playback path.
- DLNA cache entries now use SHA-256-derived filenames and pruning rules.

Result:

- The DLNA path is more correct, more lifecycle-safe, and more storage-safe than before.

### Playlist persistence hardening

Resolved in:

- [Entities.kt](C:\Install\AI%20Research\MusicPlayerPro\app\src\main\java\com\aiproject\musicplayer\db\Entities.kt)
- [Daos.kt](C:\Install\AI%20Research\MusicPlayerPro\app\src\main\java\com\aiproject\musicplayer\db\Daos.kt)
- [MusicDatabase.kt](C:\Install\AI%20Research\MusicPlayerPro\app\src\main\java\com\aiproject\musicplayer\db\MusicDatabase.kt)
- [MainActivity.kt](C:\Install\AI%20Research\MusicPlayerPro\app\src\main\java\com\aiproject\musicplayer\MainActivity.kt)

What changed:

- `playlist_tracks` now has a foreign key to `playlists`.
- Playlist order is now explicit via `playOrder`.
- Save/delete flows now use Room transactions.
- `fallbackToDestructiveMigration()` was removed and replaced with a real schema migration.

Result:

- Playlist durability and consistency are significantly improved.

### Native format consistency

Resolved in:

- [audio_engine_jni.cpp](C:\Install\AI%20Research\MusicPlayerPro\src\jni\audio_engine_jni.cpp)

What changed:

- DSD support was added to `loadNextFileFd()`, aligning next-track preload behavior with current-track load behavior.

Result:

- Advertised format support is more internally consistent, including gapless preload scenarios.

### Privacy hardening

Resolved in:

- [AndroidManifest.xml](C:\Install\AI%20Research\MusicPlayerPro\app\src\main\AndroidManifest.xml)

What changed:

- `android:allowBackup` is now `false`.

Result:

- The app no longer opts in to automatic backup of user listening metadata by default.

## Remaining Findings

### Medium 1: AGP 8.5.0 + compileSdk 35 warning remains

Evidence:

- [app/build.gradle.kts](C:\Install\AI%20Research\MusicPlayerPro\app\build.gradle.kts)
- local build output during `assembleRelease --no-daemon`

Problem:

- The project builds with `compileSdk = 35`, but Android Gradle Plugin `8.5.0` warns that it was tested only up to `compileSdk 34`.

Impact:

- This is not an immediate correctness bug and not a current release blocker.
- It does increase the chance of future build-tool surprises, especially after Android Studio / Gradle updates or CI changes.

Assessment:

- Importance: moderate.
- Urgency: not critical if local release builds are green, but worth addressing before the next toolchain refresh or wider distribution cycle.

Recommendation:

- Upgrade AGP/Kotlin/Compose to a fully supported matrix for `compileSdk 35`.

### Medium 2: Windows Gradle/KAPT behavior is improved but still not ideal

Evidence:

- [gradle.properties](C:\Install\AI%20Research\MusicPlayerPro\gradle.properties)
- [app/build.gradle.kts](C:\Install\AI%20Research\MusicPlayerPro\app\build.gradle.kts)

Problem:

- The build is now more reliable locally because Kotlin/KAPT incremental compilation was disabled, but the underlying Windows toolchain path is still less clean than ideal.

Impact:

- Local builds are reliable enough, but slower and still more fragile than a fully aligned toolchain should be.

Recommendation:

- Revisit this after the AGP upgrade and consider reducing KAPT exposure longer term.

### Medium 3: `MainActivity` is still too large and owns too much orchestration

Evidence:

- [MainActivity.kt](C:\Install\AI%20Research\MusicPlayerPro\app\src\main\java\com\aiproject\musicplayer\MainActivity.kt)
- [MainActivitySections.kt](C:\Install\AI%20Research\MusicPlayerPro\app\src\main\java\com\aiproject\musicplayer\MainActivitySections.kt)

Problem:

- The activity layer still centralizes a large amount of UI state, persistence glue, SAF handling, and playback orchestration.

Impact:

- Regression risk remains higher than necessary.
- Independent testing of app-state behavior is harder than it should be.

Recommendation:

- Continue moving orchestration toward `ViewModel`/repository/service-owned boundaries.

### Medium 4: Integration test coverage is still thin

Evidence:

- [app/src/test/java](C:\Install\AI%20Research\MusicPlayerPro\app\src\test\java)
- no `app/src/androidTest`

Problem:

- JVM unit tests are present and useful, but there are still no instrumentation tests for service lifecycle, SAF, notifications, or DLNA UI flows.

Impact:

- High-value user flows still depend heavily on manual regression testing.

Recommendation:

- Add instrumentation coverage for playback service lifecycle, SAF flows, notification/media controls, and DLNA smoke tests.

### Low 5: Service restart semantics may still be weaker than the product messaging suggests

Evidence:

- [PlaybackService.kt](C:\Install\AI%20Research\MusicPlayerPro\app\src\main\java\com\aiproject\musicplayer\PlaybackService.kt)

Problem:

- `onStartCommand()` still returns `START_NOT_STICKY`.

Impact:

- If the process is killed during playback, automatic recovery may be weaker than some users expect from "always alive" wording.

Recommendation:

- Re-evaluate restart behavior and align documentation with the exact runtime contract.

## Test Coverage Assessment

Current state:

- JVM unit tests cover playback state logic, queue behavior, ordering, restore, speed behavior, DSD labeling, and new DLNA protocol cases.
- No instrumentation/UI tests are present yet.
- No dedicated native-engine automated harness was found.

Assessment:

- Logic-level coverage is solid for a project of this size.
- System-level regression coverage is still the next meaningful gap.

## Verification Performed

Commands executed after remediation:

- `.\gradlew.bat --stop`
- `.\gradlew.bat clean test --no-daemon`
- `.\gradlew.bat assembleRelease --no-daemon`

Results:

- JVM tests passed.
- Release build passed.
- Release APK produced successfully at [app-release.apk](C:\Install\AI%20Research\MusicPlayerPro\app\build\outputs\apk\release\app-release.apk).

## Recommended Next Steps

1. Upgrade AGP/Kotlin/Compose onto a fully supported `compileSdk 35` toolchain.
2. Add `androidTest` coverage for service lifecycle, notification/media controls, SAF flows, and DLNA smoke tests.
3. Continue decomposing `MainActivity` into clearer UI/state/repository boundaries.
4. Revisit service restart semantics and document the intended behavior precisely.

## Final Assessment

The original audit found real issues, and the project now reflects meaningful follow-through rather than a superficial patch round. The highest-value correctness and durability problems were addressed successfully.

At this point, the biggest remaining concerns are maintainability and toolchain alignment, not the core playback path.
