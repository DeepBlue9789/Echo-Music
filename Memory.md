# Echo Music - Project Development & Architectural Memory

> **Purpose**: This document contains a comprehensive architectural and operational summary of the enhancements, protocols, synchronization engines, and deployment workflows implemented in this repository. It serves as permanent context for future engineering sessions.

---

## 1. Repository & Fork Details

- **Upstream Repository**: [`https://github.com/EchoMusicApp/Echo-Music`](https://github.com/EchoMusicApp/Echo-Music)
- **Fork Repository (Origin)**: [`https://github.com/DeepBlue9789/Echo-Music`](https://github.com/DeepBlue9789/Echo-Music)
- **Active Working Branch**: `main`
- **Application Package ID**: `echo.music.iad1tya` (Debug suffix: `.debug`)
- **Gradle Build Tasks**:
  - Debug APK: `./gradlew assembleUniversalGmsDebug`
  - Release APK: `./gradlew assembleUniversalGmsRelease`
- **Release Output Path**: `app/build/outputs/apk/universalGms/release/app-universal-gms-release.apk`
- **In-App Updater API Endpoint**: `https://api.github.com/repos/DeepBlue9789/Echo-Music/releases/latest`
- **In-App Changelog API Endpoint**: `https://api.github.com/repos/DeepBlue9789/Echo-Music/releases`
- **In-App Commit History API Endpoint**: `https://api.github.com/repos/DeepBlue9789/Echo-Music/commits?branch=main`

---

## 2. Connected Test Devices & Deployment Environment

| Device | Model | IP / ADB Identifier | Role in Testing |
| :--- | :--- | :--- | :--- |
| **Device A (Host/Peer)** | Samsung Galaxy S23 (`SM-S911B`) | `100.99.1.23:5555` | Primary Host / P2P Peer |
| **Device B (Guest/Peer)** | Samsung Galaxy Tab S9+ (`SM-X810`) | `100.99.1.9:5555` | Secondary Guest / P2P Peer |

- **Network Environment**: Direct Tailscale mesh network / Local Wi-Fi subnet.
- **Direct Stream Install Command**:
  ```powershell
  adb connect 100.99.1.23:5555; adb connect 100.99.1.9:5555; Start-Sleep -Milliseconds 500
  adb -s 100.99.1.23:5555 install -r "app/build/outputs/apk/universalGms/debug/app-universal-gms-debug.apk"
  adb -s 100.99.1.9:5555 install -r "app/build/outputs/apk/universalGms/debug/app-universal-gms-debug.apk"
  ```

---

## 3. Listen Together Real-Time Synchronization Engine

### A. Mathematical Model & Server-Authoritative Virtual Timeline
All playback timing across connected devices strictly follows the continuous virtual timeline function:

$$\text{Position}(t) = p_{\text{ref}} + r \cdot (t - t_{\text{ref}})$$

Where:
- $t$: Synchronized network timestamp in milliseconds ($t = \text{localMonotonicTime} + \theta$).
- $\theta$: Calculated network clock offset relative to the host/server.
- $t_{\text{ref}}$: Synchronized network timestamp when the last state transition occurred.
- $p_{\text{ref}}$: Audio track position in seconds at timestamp $t_{\text{ref}}$.
- $r$: Playback rate ($0.0$ when paused or buffering, $1.0$ during active playback).

### B. Two-Phase Scheduled Play (`PLAY_SCHEDULED`)
- **Phase 1 (Preparation)**: The server calculates an execution target $t_{\text{exec}} = \text{now} + 300\text{ms}$ and dispatches `PLAY_SCHEDULED(executeAt, startPosition)`.
- **Phase 2 (Execution)**: Connected peers schedule execution at local monotonic timestamp:
  $$t_{\text{local}} = t_{\text{exec}} - \theta$$
- **Decoder Flush Prevention on Resume**:
  - In `handlePlayScheduled`, if the player's current position is already within $2500\text{ms}$ of `startPosition`, `player.seekTo()` is skipped.
  - Calling `seekTo` on ExoPlayer flushes the audio sink and network buffers, causing an audible 1-second stutter. Skipping redundant seeks ensures instant, stutter-free playback resumption.
- **Authoritative Command Handlers**:
  - In `handlePlaybackSync`, raw `PLAY` and `PAUSE` actions are ignored and delegated exclusively to authoritative `PLAY_SCHEDULED` and `PAUSE_COMMAND` events to prevent dual seek storms.

### C. Cooperative Multi-Device Buffer Barrier (`P2PWebSocketServer.kt`)
- **Track Transition Barrier**:
  - When a track changes, all devices load media into ExoPlayer without playing and dispatch `BUFFER_READY(trackId)`.
  - The server holds playback in `BUFFER_WAIT` until all connected peers report ready.
  - Once the barrier is met, the server broadcasts `BUFFER_COMPLETE` followed by `PLAY_SCHEDULED`, guaranteeing millisecond-synchronized simultaneous start.
- **Mid-Song Buffer Protection**:
  - If a device hits network congestion and enters `Player.STATE_BUFFERING`, it dispatches `BUFFER_WAIT(trackId)`. Connected peers automatically pause until buffering resolves (with a 7-second fallback safety timer).

### D. Dynamic Soft Slew with Pitch Preservation (`SyncController`)
Instead of hard seeking which causes audible audio cutouts, follower devices use Sonic audio processor pitch-preserved rate slewing:
- **Deadband ($|\Delta_{\text{drift}}| \le 35\text{ms}$)**: Speed resets to $1.0\times$.
- **Tier 1 ($35\text{ms} < |\Delta| \le 500\text{ms}$)**: Gentle slew ($0.96\times$ if ahead, $1.04\times$ if behind).
- **Tier 2 ($500\text{ms} < |\Delta| \le 3000\text{ms}$)**: Dynamic slew ($0.90\times$ if ahead, $1.10\times$ if behind).
- **Tier 3 ($|\Delta| > 3000\text{ms}$)**: Sustained hard seek triggered only after 6 consecutive ticks (1.5s) with a 5-second cooldown and 2.5-second post-seek stabilization period.
- **Resume Grace Period (3000ms)**: Drift correction is paused for 3 seconds immediately after resume to let ExoPlayer pipelines reach steady state without premature hard seeks.
- **Host Role Assignment**: Loopback connections on the host are designated as `RoomRole.HOST` (the master timeline source) so the host is never slewed or sought.

---

## 4. Floating Circular Chat Bubble & Overlay Service

### A. Edge Coordinate Anchoring (`Gravity.END` & Zero-Movement Previews)
- **Problem**: Previously, incoming message previews caused the floating bubble icon to jump away from the screen edge because WindowManager coordinates were shifted by a hardcoded 226dp offset.
- **Solution**:
  - In `ListenTogetherOverlayService.kt`, when docked on the right side, `params.gravity` is set to `Gravity.TOP or Gravity.END` with a fixed screen margin `x = leftDockX`.
  - When an incoming message arrives, the window naturally expands to the left while the circular bubble at `Alignment.CenterEnd` remains 100% stationary at the edge of the screen.
  - Removed `params.x` shifting from `onCalloutVisibilityChanged`.
- **In-App Compose Coordinate Locking**:
  - In `FloatingChatBubble.kt`, replaced `Modifier.offset` with `Modifier.layout`:
    ```kotlin
    val x = if (isOnRightSide) {
        (clampedX - (placeable.width - bubbleDiameter.toPx())).roundToInt()
    } else {
        clampedX.roundToInt()
    }
    placeable.place(x, clampedY.roundToInt())
    ```
  - Mathematically guarantees that `CircularFloatingBubble` remains pinned at `clampedX` on every animation frame regardless of callout balloon size.

### B. Hardware-Accelerated Cross-Window Background Blur
- Uses `FLAG_HARDWARE_ACCELERATED` and Android 12+ `FLAG_BLUR_BEHIND` (`blurBehindRadius`) in `ListenTogetherOverlayService.kt`.
- Custom appearance controls in `ListenTogetherSettings.kt`:
  - **Glass Blur Intensity** (`0 dp` to `30 dp`)
  - **Tint Opacity** (`10%` to `85%`)
  - **Font Size** (Small 88%, Medium 100%, Large 115%)
  - **Font Weight** (Normal, Medium, Bold)

---

## 5. Automated Upstream Sync & Release CI/CD Pipeline

To ensure the fork remains perpetually up to date with official Echo Music releases while preserving Listen Together enhancements:

### A. Automatic Upstream Sync (`.github/workflows/sync-upstream.yml`)
- Runs every 6 hours via cron and supports manual trigger (`workflow_dispatch`).
- Fetches `https://github.com/EchoMusicApp/Echo-Music.git` (`upstream/main`).
- Compares commit counts and latest release tags.
- If upstream has changes:
  1. Merges `upstream/main` into `origin/main` automatically.
  2. In case of merge conflicts, favors fork Listen Together features (`git checkout --ours .`).
  3. Pushes updated `main` to `origin`.
  4. Generates a new release tag `v<version>` (matching or exceeding upstream) and pushes the tag to trigger the release builder.

### B. Automated Release Publisher (`.github/workflows/build-release.yml`)
- Triggered automatically on tag push (`v*`) or manual dispatch.
- Compiles the production Universal GMS APK using `./gradlew assembleUniversalGmsRelease`.
- Generates `changelog.json` containing structured changelog entries.
- Publishes a new GitHub Release on `DeepBlue9789/Echo-Music` with assets:
  - `app-universal-gms-release.apk`
  - `EchoMusic-<TAG>-universal.apk`
  - `changelog.json`

### C. In-App Update Compatibility (`echomusicupdater.kt`)
- `echomusicupdater.kt` points directly to:
  `https://api.github.com/repos/DeepBlue9789/Echo-Music/releases/latest`
- Reads `versionName` vs `tag_name`, downloads `changelog.json`, and matches non-debug `.apk` assets.
- When GitHub Actions publishes a new release, installed apps immediately prompt users with the new update dialog and one-tap download.

---

## 6. Quick Reference for Key Files

| Component / Feature | File Path |
| :--- | :--- |
| **Playback Sync & Virtual Timeline** | `app/src/main/kotlin/com/music/echo/listentogether/ListenTogetherManager.kt` |
| **P2P WebSocket Server & Barrier Protocol** | `app/src/main/kotlin/com/music/echo/p2p/P2PWebSocketServer.kt` |
| **P2P Client & Time Mapping** | `app/src/main/kotlin/com/music/echo/listentogether/ListenTogetherClient.kt` |
| **P2P Partner Discovery & Lifecycle** | `app/src/main/kotlin/com/music/echo/p2p/P2PPartnerManager.kt` |
| **Floating Chat Bubble UI & Layout** | `app/src/main/kotlin/com/music/echo/ui/component/FloatingChatBubble.kt` |
| **System Overlay Window Service** | `app/src/main/kotlin/com/music/echo/listentogether/ListenTogetherOverlayService.kt` |
| **In-App Update Checker** | `app/src/main/kotlin/com/music/echo/echomusic/updater/echomusicupdater.kt` |
| **Appearance & Integrations Settings** | `app/src/main/kotlin/com/music/echo/ui/screens/settings/integrations/ListenTogetherSettings.kt` |
| **Upstream Sync Workflow** | `.github/workflows/sync-upstream.yml` |
| **Release Build Workflow** | `.github/workflows/build-release.yml` |
| **Gradle Properties & Toolchain Paths** | `gradle.properties` |
| **Lint Rules & ExtraTranslation Ignored** | `app/lint.xml` |

---

## 7. Seek & Track Transition Synchronization Hardening

### A. Seek Command Authority & Ping-Pong Elimination
- **Exclusive Authority**: Raw `PlaybackActions.SEEK` in `handlePlaybackSync` is ignored. Seeks are processed exclusively and authoritatively through `SEEK_COMMAND`.
- **Symmetric Host/Guest Execution**: Removed `if (isHost) return` in `handleSeekCommand`. Both Host and Guest honor incoming seek commands.
- **Local-Echo Suppression**: If `abs(player.currentPosition - targetMs) < 800L`, the device that initiated the seek locally skips redundant `player.seekTo(targetMs)` calls, eliminating ExoPlayer audio decoder flushes.
- **Seek Grace Period**: `resumeGracePeriodUntil = SystemClock.elapsedRealtime() + 3500L` is set on every seek to prevent the 250ms drift evaluator from misinterpreting decoder buffering as drift.

### B. Track Transition Loop & Buffer Barrier Fix
- **Track ID Normalization**: Added `normalizeTrackId(id)` (`id.substringAfterLast("/")`) across all event handlers, preventing false mismatches between prefix-formatted ExoPlayer media IDs and clean track IDs.
- **Position Preservation in Barrier Release**: `releaseBufferBarrier` in `P2PWebSocketServer.kt` preserves `virtualTimelineRefPos` (`resumePosSec = virtualTimelineRefPos.coerceAtLeast(0.0)`) instead of hardcoding `0.0`.
- **Single-Shot Barrier Guard**: `if (currentBufferingTrackId == null) return` prevents repeated barrier firing loops on track changes.
- **Immediate Playback on Pending BufferComplete**: If a device finishes preparing a track and finds `bufferCompleteReceivedForTrack` already set, it plays immediately without pausing.
- **Transient Buffering Suppression**: `onPlaybackStateChanged` suppresses `BUFFER_WAIT` if `now < resumeGracePeriodUntil`, preventing normal seek or track preparation buffering from triggering unnecessary room stalls.

### C. Track Transition Position Reset & Double Skip Prevention
- **Virtual Timeline Zeroing on CHANGE_TRACK**: When a track transitions (`PlaybackActions.CHANGE_TRACK`), both `P2PWebSocketServer.kt` and `ListenTogetherManager.kt` explicitly reset `virtualTimelineRefPos = 0.0`, `virtualTimelineRate = 0.0`, and `timelineRefPosSec = 0.0`.
- **Elimination of Song Cascading Skips**: Previously, `virtualTimelineRefPos` retained the old track's ending position (e.g. `271.019s`). When the next track loaded, `releaseBufferBarrier` scheduled playback at `271.019s`. If the new track's duration was under 271s, ExoPlayer immediately reached `STATE_ENDED` and auto-skipped to the next track (causing 2 songs to skip instantly and guests to freeze in paused state). Zeroing the timeline on every track change ensures new songs start cleanly from 0.000s.
- **Volatile Thread Visibility**: Marked `resumeGracePeriodUntil` as `@Volatile` and set `isApplyingRemoteState = true` / `isSyncing = true` immediately before coroutine dispatch so `onPlaybackStateChanged` on the Main Looper never sends false `BUFFER_WAIT` messages during seeks.

### D. Elimination of Mid-Song Buffer Locks & Periodic Restart Loops
- **Elimination of Mid-Song BUFFER_WAIT**: During active playback, transient ExoPlayer audio buffering (100–200ms) is now handled natively by ExoPlayer and smoothed by the Virtual Timeline's dynamic pitch-preserving soft slew engine. Mid-song `BUFFER_WAIT` emissions have been eliminated, ensuring transient buffers never pause the room or trigger room-wide re-syncs.
- **State Cleanup on STATE_READY**: In `onPlaybackStateChanged`, `bufferingTrackId = null` and `isWaitingForPeersBuffer = false` are now explicitly reset upon reaching `Player.STATE_READY`, preventing repeated emissions of `buffer_ready`.
- **Playback Interrupt Guard on BUFFER_READY_EVENT**: In `P2PWebSocketServer.kt`, `BUFFER_READY_EVENT` is ignored if `virtualTimelineRate > 0.0` (room is already actively playing), eliminating the periodic 7-second jump-to-0 restart loop.

### E. Seamless Gapless Auto-Transitions Between Songs
- **Non-Pausing Host Auto-Transition**: In `onMediaItemTransition`, checked `reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO`. When the current song naturally finishes, both devices already have the next track pre-buffered in ExoPlayer's playlist queue. The host **never calls `player.pause()`** on natural auto-transitions. Both devices continue playing seamlessly and gaplessly.
- **Guest In-Queue Detection & Skip Prevention**: When `PlaybackActions.CHANGE_TRACK` arrives at the guest, the guest checks if its local ExoPlayer has already naturally transitioned to that track (`normalizeTrackId(player.currentMediaItem?.mediaId) == normalizeTrackId(track.id)` and `player.isPlaying`). If so, the guest **skips `applyPlaybackState` and skips `seekTo(0)`**, preventing the 2–3 second delayed reversion to 0:00. The timeline smoothly tracks playback with `rate = 1.0`.
- **Manual Jumps Preserved**: If a user clicks a new track manually (`MEDIA_ITEM_TRANSITION_REASON_SEEK` or playlist click), the cooperative buffer barrier engages normally to ensure both peers prepare the new track before playing. Natural auto-transitions run 100% gapless without stopping.
