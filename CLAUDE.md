# Reelay

Android app (Kotlin, minSdk 29, target/compile 36) that acts as a share target for
Instagram reel links, downloads the reel at Instagram's best rendition, re-encodes it,
and opens it in TikTok's editor so "Your Story" is one tap away. Published for others to
install (signed APK on GitHub Releases); tier 2 of the house engineering standard.

## Structure

- `app/src/main/kotlin/com/clearcmos/reelay/` - one file per step; see README "Layout".
  `InstagramLink`, `ReelPageParser`, `DashManifest`, `EncodingBudget`, `Mp4EditLists`,
  `ClipCache`, `LeadTrimAudioProcessor`, and `VideoDownloader` are JVM-testable and carry
  the unit tests. The Android-bound classes are listed under "Test exemptions".
- `app/src/test/` - JUnit 4 tests. `FakeHttpServer` is a loopback HTTP/1.1 server on plain
  sockets (Android's unit-test compile classpath has no `com.sun.net.httpserver`).
- `app/src/test/resources/reel_page.html` - trimmed copy of a real logged-out reel page
  (shortcode CDUMkliABpa), captured 2026-08-29 with a Chrome TLS fingerprint. Keep the
  Relay wrapper shape when refreshing it; the parser must not depend on the wrapper keys.
  `dash_manifest.mpd` is the `video_dash_manifest` of reel DclewV1NSC9 (2026-08-30) cut to
  four representations (1080p60, 720p, 240p, audio).
- `gradle/libs.versions.toml` - the only place dependency versions are declared.
  `app/gradle.lockfile`, `buildscript-gradle.lockfile`, `settings-gradle.lockfile` - the
  resolved graph, strict mode; the build fails when resolution drifts from them.
- `flake.nix` - devShell with JDK 17, Gradle 8.14.4, SDK platforms 35/36, build-tools
  35.0.0 from nixpkgs `androidenv`. Sets `ANDROID_HOME`, `JAVA_HOME`, and the aapt2
  override so the build never tries to write into the read-only SDK.
- `.github/workflows/ci.yml` - lint, unit tests plus Kover gate, debug APK on the GitHub
  runner's own Android SDK (SHA-pinned actions, least privilege, concurrency cancel,
  timeouts, aggregate `ci-ok` job). `release.yml` - on `v*` tags, signed APK plus sha256
  attached to a GitHub Release. `.github/dependabot.yml` - weekly grouped Actions bumps,
  monthly grouped Gradle bumps (kotlin / androidx / other).
- `LICENSE` - Apache-2.0, copyright clearcmos.
## Commands

Run inside `nix develop`:

- Test: `gradle :app:testDebugUnitTest`
- Coverage gate: `gradle :app:koverVerifyDebug` (85% line over the non-exempt classes)
- Coverage report: `gradle :app:koverHtmlReportDebug`
- Lint: `gradle ktlintCheck :app:lintDebug`
- Format: `gradle ktlintFormat`
- Typecheck: `gradle :app:compileDebugKotlin`
- Build: `gradle :app:assembleDebug`
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Refresh lockfiles after a version bump: `gradle dependencies :app:dependencies buildEnvironment :app:buildEnvironment --write-locks`
- Local signed release: write `keystore.properties` (see Release), then `gradle :app:assembleRelease -PversionName=0.1.0 -PversionCode=1`
- Drive the share path from adb: see README "Development".
## Conventions

- No Gradle wrapper; Gradle comes from the devShell locally and from `setup-gradle`
  (pinned version) in CI. Keep the two versions equal.
- Dependency versions live only in `gradle/libs.versions.toml`; after changing one, run
  the lockfile refresh command and commit the lockfile diff with it.
- Android Lint runs with `warningsAsErrors`. The four version-drift checks are disabled
  on purpose (see decision log); do not widen that list without a dated entry here.
- ktlint uses the `android_studio` code style from `.editorconfig`. Run `ktlintFormat`
  before patching a file by string match: it reflows signatures and trailing commas, and a
  patch written against unformatted text silently misses (bit twice on 2026-08-30).
- Strings shown to the user live in `strings.xml`; exceptions carry developer wording.
- Every Android-bound class stays thin enough to verify on a device by hand; logic that
  can run on the JVM lives in a class that does, so it can be unit tested.
- House style: no emojis, hyphens instead of em dashes, factual README, no secrets or
  personal identifiers in tracked files.

## Dependency updates

Dependabot bumps `gradle/libs.versions.toml` but does not touch the Gradle lockfiles, so
with strict locking every Gradle PR it opens fails CI with "Dependency version enforced by
Dependency Locking" (observed on the first two PRs, 2026-08-30). That red check is the
expected state, not a bug. To land one:

1. `git fetch origin && git switch <dependabot branch>`
2. `nix develop --command gradle dependencies :app:dependencies buildEnvironment :app:buildEnvironment --write-locks`
3. Run the full verification (lint, tests, coverage gate, lint, assembleDebug) and fix
   whatever the bump broke.
4. Commit the lockfile diff on the branch and push; CI re-runs. Do not comment
   `@dependabot rebase` afterwards, it would drop the lockfile commit.

Actions PRs need no lockfile step. AGP major bumps are ignored in `dependabot.yml`; the AGP
9 migration (Gradle 9, new Kotlin plugin wiring) is a deliberate change.

## Test exemptions

Classes excluded from the Kover gate (`app/build.gradle.kts`), each verified on a device
instead:

- `MainActivity`, `ShareActivity`, `RelayException`: Activity lifecycle and views; the
  logic they call is in tested classes.
- `InstagramWebFetcher`: needs a real Chromium WebView for the TLS fingerprint that is the
  whole point of the class.
- `VideoNormalizer`: drives Media3 Transformer and the device's hardware codecs.
- `TikTokHandoff`: `PackageManager.resolveActivity` against installed apps.
- `CleanupJobService`: `JobScheduler`.

Adding a class to that list needs a reason here.

## Release

Tag `v*` to publish a signed APK via `.github/workflows/release.yml`. Signing material
comes from four repository secrets, and locally from a gitignored `keystore.properties`
at the repo root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without it,
`assembleRelease` still builds but the output is `app-release-unsigned.apk`, which the
release workflow rejects. `versionName` comes from the tag with its leading `v` stripped;
`versionCode` from the workflow run number, so it always increases.

**The signing key is permanent.** The key that signs the first published release is the
only key Android will ever accept as an upgrade for `com.clearcmos.reelay`. GitHub
secrets cannot be read back, so whoever holds the key must keep a durable copy of the
keystore and its password outside GitHub; losing them means every user has to uninstall
before installing any later version. A fork publishing under its own application id
generates its own key (`keytool -genkeypair -keyalg RSA -keysize 4096 -validity 10000`)
and sets the four secrets above.

Maintainer's copy: generated 2026-08-30 (RSA 4096, alias `reelay`, CN=Reelay O=clearcmos,
public certificate SHA-256
`30:C9:DE:19:5B:8A:CE:50:45:00:23:81:24:41:D0:B7:90:6F:A5:D2:18:22:C5:23:63:53:49:93:5F:E5:B2:12`),
kept at `~/.local/share/reelay/release.jks` with its password beside it in
`keystore-password` (both 0600), backed up the same day in the maintainer's password
manager under `REELAY_KEYSTORE` and `REELAY_KEYSTORE_PASSWORD`. It is deliberately a
different key from deskremote's: one compromised key must not cover both apps.

A debug build installed on a phone carries the debug key; the release APK will not install
over it. Uninstall the debug build first.
## Verified behaviour and dates

Re-verify these before assuming they still hold; each is an external dependency.

- 2026-08-29, TikTok 46.6.3 on a Galaxy S25 (Android 16): `ACTION_SEND video/mp4` with
  a MediaStore content URI to
  `com.zhiliaoapp.musically/com.ss.android.ugc.aweme.share.SystemShareActivity` opens
  the editor with "Your Story" and "Next" buttons on screen. Verified over adb with a
  test clip and a screenshot; no story was posted.
- 2026-08-29, Instagram web: the logged-out reel page embeds
  `xig_polaris_media.if_not_gated_logged_out.video_versions` in a `data-sjs` script
  block, but only when the TLS handshake looks like a browser. Plain curl and OkHttp
  style clients get a 620 KB HTML shell with no media. The same gate applies to
  `/api/graphql` (`doc_id` 27130156389949648, `PolarisLoggedOutDesktopWWWPostRootContentQuery`).
  The CDN URLs in `video_versions` download with any client (HTTP 200/206, video/mp4).
- 2026-08-29, end to end from the debug APK on the S25: sharing
  `https://www.instagram.com/reel/CDUMkliABpa/` to Reelay fetched the page through the
  WebView (the TLS gate passed), wrote a 3,257,414-byte MP4 to `Movies/Reelay`, and
  opened TikTok's editor with "Your Story" and "Next" on screen; 2 to 16 seconds end to
  end depending on WebView warm-up. Backed out without posting.
- 2026-08-29, TikTok 46.6.3 editor and Instagram MP4s: a reel downloaded straight from
  `video_versions` plays with audio visibly out of sync in TikTok's editor while
  Instagram plays it correctly. ffprobe shows edit lists: video media time 1024/15360 s
  (66.7 ms, the B-frame reorder offset) and audio media time 5058 samples at 44.1 kHz
  (114.7 ms of HE-AAC encoder priming). Players that ignore edit lists would put the
  tracks 48-115 ms apart, so the re-encode removes that dependency. Whether TikTok is such
  a player was not proven: the later flash-and-beep test (below) showed the editor preview
  delays audio for any file, which masks the comparison.
- 2026-08-29, final re-encode path on the S25 (reel DclewV1NSC9, 69 s): 8 s from share to
  TikTok's editor including the Media3 re-encode; output 33.5 MB at 3.7 Mbps, AAC-LC, no
  B-frames, zero edit lists (`Mp4EditLists.inspect`), encoder priming matched the expected
  1600 frames on the first pass. Cross-correlation against an edit-list-honoring ffmpeg
  decode of the original: audio +11.6 ms, video 0 frames. Method for re-measuring lives in
  the 2026-08-29 session notes: extract 8 kHz mono PCM and 32x32 grey frames from both
  files with ffmpeg, FFT cross-correlate the audio, nearest-frame match the video.
- 2026-08-29, TikTok 46.6.3 editor preview: a synthetic clip with a white flash and a
  1 kHz beep every second (plain H.264, AAC-LC 44.1 kHz, no B-frames, no edit lists,
  generated with ffmpeg) previews in the editor with the beep audibly after the flash.
  The same Reelay output that looks off in the editor plays in sync in Samsung Gallery.
  So the visible desync in the editor is TikTok's preview latency, independent of the
  file. The user then posted the test clip privately: the posted video is in sync. The
  editor preview is the only place the desync exists.
- 2026-08-29, FileProvider handoff on the S25: TikTok's `SystemShareActivity` accepts a
  `content://com.clearcmos.reelay.files/clips/...` URI and opens the editor with the clip
  (7 s end to end). `dumpsys activity permissions` shows the URI grant to TikTok being
  released about 10 s after the handoff, consistent with TikTok copying the clip on
  import rather than holding the original. Nothing was written to `Movies/Reelay`; the
  cleanup job appears in `dumpsys jobscheduler` with a 1 h minimum latency.
- 2026-08-30, Instagram media JSON: `video_versions` (types 101/102/103) all point at the
  same 720p30 H.264 progressive file even for a 1080p60 upload (reel DclewV1NSC9:
  `original_width` 1080, `number_of_qualities` 9). `video_dash_manifest` on the same object
  is an inline MPD with VP9 video representations up to 1080x1920 at 60 fps
  (`frameRate="15360/256"`, `FBQualityLabel="1080p"`, 2.9 Mbps) and one HE-AAC audio
  representation; each is a plain single-file `BaseURL` that downloads with curl (HTTP
  200, no range games). This is what the Instagram app plays, and why a download from
  `video_versions` looked softer than the reel in the app.
- 2026-08-30, S25 encode of the split 1080p60 renditions: Media3 muxes a video-only
  sequence and an audio-only sequence into one H.264/AAC file; 16 s end to end for a 69 s
  reel, 68 MB at the 8 Mbps cap, SSIM 0.992 against the VP9 source, A/V +11.6 ms, no edit
  lists. With Media3's default the encoder wrote landscape 1920x1080 plus a rotate -90
  matrix (TikTok's editor still displayed it upright); `setPortraitEncodingEnabled(true)`
  removes that dependency, see the decision log.
- 2026-08-29, Instagram Android 444.0.0.46.85: "Share to" from a reel sends
  `text/plain` with the reel URL.

## Decision log

- 2026-08-29: Fetch the Instagram page through a hidden WebView, not OkHttp. Reason:
  the TLS fingerprint gate above. Android System WebView is Chromium, so its handshake
  matches what Instagram accepts; a desktop Chrome UA is set to match the verified
  combination. All subresources are answered with an empty body so only the document
  loads.
- 2026-08-29: Parse the server-rendered page rather than call `/api/graphql`. The page
  already contains the identical payload and needs no LSD or CSRF bootstrapping.
- 2026-08-29: `ReelPageParser` walks every `data-sjs` block recursively for objects
  with `video_versions` and `code`/`pk` instead of following the Relay path. The
  wrapper keys (`adp_PolarisLoggedOutDesktopWWWPostRootContentQuery...`) are generated
  and will change.
- 2026-08-29: Re-encode every downloaded reel with Media3 Transformer before the
  handoff (`VideoNormalizer`). Reason: the edit-list finding above; the output must not
  depend on the consumer honoring edit lists. Transformer applies
  the edit lists while decoding, its encoder emits no B-frames, and the output needs no
  edit list. Re-encoding is forced by passing non-default `VideoEncoderSettings` (any
  custom settings flip Transformer from transmux to transcode) and a `SonicAudioProcessor`
  in the effects list (any audio processor forces audio decode plus encode). Remuxing
  alone was rejected: the source's B-frame offset would need an edit list again, which
  is the thing TikTok ignores.
- 2026-08-29: Media3 itself writes one edit list on the re-encoded audio track for the
  AAC-LC encoder priming (1600 frames = 36 ms on the S25; this is fdk-aac's AAC-LC
  delay). TikTok would ignore that one as well. So `LeadTrimAudioProcessor` drops the
  same 1600 frames from the decoded input before encoding, and `Mp4EditLists.neutralize`
  renames every `edts` box in the output to `free`. Result: no edit lists, identical
  timing in every player. If a device's encoder reports a different priming value, the
  normalizer re-runs once with the measured amount. Measured against an
  edit-list-honoring decode of the original (audio cross-correlation plus frame
  matching): the first pass without the trim landed at +11.6 ms audio lag for honoring
  players and about +48 ms for ignoring players; the residual ~12 ms is decoder-side and
  below any perception threshold.
- 2026-08-29: Media3 1.11.0 is the floor that fits compileSdk 36 (1.10.0+ require 36,
  1.9.x require 35). Do not bump past the compileSdk the toolchain provides.
- 2026-08-29 (superseded the same day, see below): the finished clip went to MediaStore
  (`Movies/Reelay`) and was passed as a MediaStore URI, pruned after 24 h on the next run.
- 2026-08-29: Nothing in the Gallery. The finished clip stays in the app cache and TikTok
  gets a FileProvider URI (`com.clearcmos.reelay.files`). The user did not want clips
  accumulating in `Movies/Reelay`; with weekly use the 24 h prune only ran weekly. A
  JobScheduler job (`CleanupJobService`, unpersisted, minimum latency 1 h) plus a prune at
  the start of every run delete clips older than one hour. One hour is the safety margin
  in case TikTok re-reads the original at post time; it copies the clip when the editor
  opens, so this is generous.
- 2026-08-29: No AccessibilityService automation of the "Your Story" tap in this
  version. It would make the flow fully unattended but is brittle against TikTok UI
  changes and posts without a review step. Revisit if the manual tap becomes annoying.
- 2026-08-29: Toolchain pinned to AGP 8.13.2 + Kotlin 2.3.21 + Gradle 8.14.4 (the
  nixpkgs default) rather than AGP 9.x, which needs Gradle 9 and changes Kotlin
  plugin wiring. Bump deliberately, together.
- 2026-08-29: CI installs Gradle with `setup-gradle` and uses the runner's Android SDK
  instead of `nix develop`, avoiding a 1 GB SDK download on every run. The pinned
  Gradle version must match `nixpkgs.gradle` in the devShell.
- 2026-08-29: No OkHttp. OkHttp 5.5.0 declares a compileSdk 37 floor in its AAR metadata,
  which AGP 8.13 refuses; the CDN download is a plain streamed GET, so
  `HttpURLConnection` does the job with zero dependencies.
- 2026-08-29: Backup attributes: `allowBackup="false"` alone trips Lint's
  `DataExtractionRules`; the manifest therefore also points `dataExtractionRules` and
  `fullBackupContent` at exclude-everything XML rules. The app holds no data worth
  backing up.
- 2026-08-29: The adaptive icon lives in `res/mipmap-anydpi/` (not `-v26`): with
  minSdk 29 Lint flags the version qualifier as obsolete.
- 2026-08-30: Prefer the DASH renditions over `video_versions`. The user compared the
  posted result with the reel in the Instagram app and it looked worse; the cause was the
  720p30 cap on the progressive file, not the re-encode (SSIM 0.99 against that file).
  `DashManifest` picks the largest video representation and the audio one,
  `VideoDownloader` fetches both in parallel, `VideoNormalizer` muxes them via a
  two-sequence `Composition` (`EditedMediaItemSequence.withVideoFrom` /
  `withAudioFrom`; the deprecated varargs builder plus `experimentalSetForceAudioTrack`
  throws "trackTypes must only contain TRACK_TYPE_AUDIO and/or TRACK_TYPE_VIDEO" in Media3
  1.11). Any failure on this path falls back to the progressive file, so nothing that
  worked before can stop working.
- 2026-08-30: `EncodingBudget` scales its per-pixel floor with frame rate (4 bits per pixel
  per second at 30 fps) so 60 fps sources get twice the budget; the 8 Mbps cap stays.
  Frame rate comes from `MediaMetadataRetriever` frame count over duration.
- 2026-08-30: `Transformer.setPortraitEncodingEnabled(true)`. Media3 otherwise rotates
  portrait video to landscape for the encoder and relies on a rotation matrix in the
  container, the same class of dependency as edit lists. Google documents this as
  "likely to result in more failures" on some encoders; the S25's Qualcomm encoder is
  fine, and a failure falls back to the progressive file.
- 2026-08-29: Android Lint version-drift checks (`GradleDependency`,
  `AndroidGradlePluginVersion`, `NewerVersionAvailable`, `OldTargetApi`) are disabled
  because with `warningsAsErrors` they would fail CI whenever upstream ships a release.

- 2026-08-30: Raised to tier 2 of the house standard because the app is published for
  others. Added: Apache-2.0 LICENSE; Kover line-coverage gate at 85% over the non-exempt
  classes (96.7% at the time), wired into CI; Gradle dependency locking in strict mode with
  committed lockfiles (Gradle has no lockfile by default, so transitive versions floated);
  dependabot for Actions (weekly) and Gradle (monthly, grouped); signed-release workflow on
  `v*` tags copied from deskremote's proven pattern; README rewritten for a stranger.
- 2026-08-30: Changelog decision: no CHANGELOG.md. Git history plus the auto-generated
  notes on each GitHub Release serve the purpose for a tool this size.
- 2026-08-30: Contributions: issues welcome, pull requests after discussion in an issue.
  No CONTRIBUTING.md, templates, or CODEOWNERS; README carries the two sentences.
- 2026-08-30: `VideoDownloader` deletes both split targets when either rendition fails.
  Before, the rendition that had succeeded stayed in the cache until the hourly prune.
  Pinned by the regression test in `VideoDownloaderTest`.
- 2026-08-30: Unit tests compile against android.jar, not the JDK, so `com.sun.net.*` is
  unavailable and `ByteBuffer.flip()` returns `Buffer`. `FakeHttpServer` (plain sockets)
  replaced the JDK server; `unitTests.isReturnDefaultValues = true` lets Media3's common
  classes load in tests.
- 2026-08-30: Dependabot stays on for Gradle despite the lockfile gap. Its value is the
  notification and the grouped diff; the lockfile refresh is one command and the red check
  is honest. Renovate (which does update Gradle lockfiles) would need a GitHub App install
  and was not worth it for a repo with one module.
- 2026-08-30: Release builds are not minified. Media3 Transformer loads codecs
  reflectively and the APK is 17 MB either way; turning R8 on is a deliberate change with
  a device test, not a default.

## Follow-ups not started

- Private or followers-only reels: let the user log in to Instagram inside a WebView
  once; `CookieManager` persists the session and the same parser should work on the
  logged-in page (unverified).
- Long downloads: move the fetch, download, and re-encode into a foreground service so
  leaving the dialog does not kill the run.
- Auto-tap "Your Story" with an AccessibilityService for a fully unattended flow. Rejected
  for now: brittle against TikTok UI changes and removes the review step.
- Instrumented (on-device) tests for the exempt classes if a second device or an emulator
  in CI ever becomes worth the cost.
## Troubleshooting

- `AAPT: error: resource mipmap/ic_launcher not found` right after moving or renaming a
  resource folder: the incremental resource merge kept the old folder in its blame
  index. `gradle --rerun-tasks :app:mergeDebugResources` clears it (seen 2026-08-29
  when moving the icon out of `mipmap-anydpi-v26`).
- KtLint or kotlinc "failed to parse" on a file whose comments mention a MIME type like
  `video/*`: Kotlin nests block comments, so `/*` inside a KDoc opens a new one. Spell
  the type out in prose instead.
