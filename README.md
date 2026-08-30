# Reelay

Android share target that moves a public Instagram reel into TikTok's editor with the
clip already loaded, so posting it to your TikTok Story is one tap.

Neither app offers this. Instagram's share sheet only sends a link, TikTok only imports
video files, and TikTok has no public API for stories. Reelay sits between the two: it
downloads the reel at the quality Instagram itself plays, re-encodes it into a file every
player times identically, and hands it to TikTok.

## Requirements

- Android 10 or newer (API 29).
- TikTok installed (`com.zhiliaoapp.musically`; the `com.ss.android.ugc.trill` build is
  also recognised).
- Android System WebView present and up to date (it ships with Android; Reelay uses it to
  fetch the reel page).
- Instagram is optional. Reelay accepts a reel link from any app, or a pasted link.

## Install

Download `reelay-vX.Y.Z.apk` from the
[Releases](https://github.com/clearcmos/reelay/releases) page and open it on the phone;
Android asks once to allow installs from your browser or file manager. Releases are signed
with the project's release key, so later versions install as upgrades. Each release also
carries a `.sha256` file:

```
sha256sum -c reelay-vX.Y.Z.apk.sha256
```

Or install over adb from a computer:

```
adb install -r reelay-vX.Y.Z.apk
```

To build from source instead, see Development.

## Use

1. In Instagram, open a reel, tap Share, and pick "TikTok Story" in the share sheet.
2. Reelay shows a small progress dialog: fetching, downloading, re-encoding (a few seconds
   per minute of video), then opens TikTok.
3. TikTok's editor appears with the clip. Tap "Your Story" to post, or "Next" for a
   regular post.

Sharing a link from any other app works the same way. So does opening Reelay from the
launcher and pasting a link. A video file shared to Reelay (not a link) is handed to
TikTok as is.

## How it works

1. `ShareActivity` receives the `text/plain` share, finds the Instagram URL, and
   normalises it (`/reel/`, `/reels/`, `/p/`, `/tv/`, and `/share/...` redirect links).
2. A hidden WebView loads the reel's public page. Instagram serves the media JSON only to
   clients with a browser TLS fingerprint; a plain HTTP library gets an empty shell, the
   system WebView (Chromium) passes.
3. The page embeds two things: a progressive MP4 capped at 720p30, and a DASH manifest
   with renditions up to the upload's native size and frame rate (1080p60 for a 1080p60
   reel). Reelay takes the best DASH video and audio renditions when present, otherwise
   the progressive file.
4. Media3 Transformer re-encodes on-device to H.264 without B-frames, AAC-LC, portrait
   encoded as portrait, no edit lists. Instagram's files rely on MP4 edit lists for
   audio/video alignment; a consumer that ignores them puts lips 50-115 ms off the voice.
   Re-encoding bakes the alignment into the stream. The bitrate is twice the source
   (scaled for frame rate, 2 to 8 Mbps), which is visually lossless.
5. TikTok's share activity receives a `content://` URI into Reelay's private cache.
   TikTok copies the clip on import.

Nothing is written to the Gallery. The cache is pruned an hour after each handoff and at
the start of every run.

## Privacy

Reelay talks to two hosts only: `www.instagram.com` (the reel page) and Instagram's CDN
(the video). No analytics, no accounts, no other network calls. The WebView stores
Instagram's cookies like a browser would; clearing Reelay's storage in Android settings
removes them. Nothing is sent to TikTok except the clip, through Android's share
mechanism.

## Limits

- Public reels only. Private, followers-only, age-gated, or region-gated reels fail with
  a message saying so. Logging in to Instagram inside Reelay is a possible follow-up, not
  implemented.
- Photos and carousels are rejected; TikTok's share handler needs a single video.
- The final "Your Story" tap happens in TikTok. Reelay never posts on its own.
- TikTok's editor preview plays audio late for any imported clip, including a synthetic
  flash-and-beep test file, while the posted result of that same file is in sync. Judge
  sync on the posted result, not in the editor.
- Instagram's page structure and TikTok's share activity are internal interfaces that can
  change without notice. CLAUDE.md records what was verified and when.

## Troubleshooting

- "Instagram did not expose the video": the reel is not public, or Instagram changed its
  page. Open the link in a browser while logged out; if the video does not play there,
  Reelay cannot fetch it either.
- "TikTok is not installed": Reelay looks for the two TikTok package names above. A
  regional TikTok build with another package name is not recognised; open an issue with
  the package name.
- Re-encoding fails on a device: Reelay logs a warning and hands TikTok Instagram's
  progressive 720p file instead, so the flow still completes at lower quality.
- Slow: the re-encode runs at roughly 4x real time on a 2025 flagship; a 60 s 1080p60
  reel takes about 15 s end to end.

## Development

The toolchain (JDK 17, Gradle 8.14.4, Android SDK platforms 35 and 36, build-tools
35.0.0) comes from the nix devShell; nothing is installed system-wide:

```
nix develop
```

Without nix: install the same JDK, Gradle, and SDK components, set `ANDROID_HOME`, and run
the same `gradle` commands. Android Studio opens the project as a normal Gradle build.

| Task | Command |
|---|---|
| Unit tests | `gradle :app:testDebugUnitTest` |
| Coverage gate (85% line, JVM-testable modules) | `gradle :app:koverVerifyDebug` |
| Coverage report | `gradle :app:koverHtmlReportDebug` (app/build/reports/kover/htmlDebug) |
| Lint (ktlint plus Android Lint) | `gradle ktlintCheck :app:lintDebug` |
| Format | `gradle ktlintFormat` |
| Typecheck | `gradle :app:compileDebugKotlin` |
| Debug APK | `gradle :app:assembleDebug` |
| Install debug build | `adb install -r app/build/outputs/apk/debug/app-debug.apk` |
| Refresh dependency lockfiles | `gradle dependencies :app:dependencies buildEnvironment :app:buildEnvironment --write-locks` |

Dependency versions live in `gradle/libs.versions.toml`; resolved versions are locked in
`*.lockfile` and the build fails on drift. Dependabot opens grouped update PRs; they
arrive with a failing check until the lockfiles are refreshed on the branch (CLAUDE.md,
"Dependency updates").

CI runs lint, unit tests with the coverage gate, and a debug build on every push and pull
request. Pushing a `v*` tag runs the release workflow, which builds a signed APK and
attaches it to a GitHub Release (signing material comes from repository secrets; see
CLAUDE.md, "Release").

Drive the share path over adb without Instagram:

```
adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "https://www.instagram.com/reel/CDUMkliABpa/" -n com.clearcmos.reelay/.ShareActivity
```

## Layout

```
app/src/main/kotlin/com/clearcmos/reelay/
  InstagramLink.kt           finds and classifies the Instagram URL in shared text
  InstagramWebFetcher.kt     hidden WebView that fetches the page with a browser TLS stack
  ReelPageParser.kt          pulls video_versions and the DASH manifest out of the page's JSON
  DashManifest.kt            picks the best video and audio renditions from that manifest
  ClipCache.kt               app-private clip directory with age-based pruning
  CleanupJobService.kt       JobScheduler job that prunes the cache an hour after a handoff
  VideoDownloader.kt         streams the renditions (or the progressive MP4) into the clip cache
  VideoNormalizer.kt         Media3 Transformer re-encode that removes the edit-list dependency
  LeadTrimAudioProcessor.kt  drops the encoder-priming amount from the audio input
  Mp4EditLists.kt            reads and neutralises edit lists in the written MP4
  EncodingBudget.kt          bitrate rule for the re-encode
  TikTokHandoff.kt           builds the ACTION_SEND intent for TikTok's share activity
  ShareActivity.kt           share-sheet entry point tying the steps together
  MainActivity.kt            launcher screen with a paste-a-link test path
app/src/test/                JVM unit tests; reel_page.html and dash_manifest.mpd are trimmed
                             real Instagram responses, FakeHttpServer serves the downloader tests
.github/workflows/           ci.yml (lint, tests, coverage, build) and release.yml (signed APK on tag)
flake.nix                    the development toolchain
```

## Contributing

Bug reports and questions are welcome as GitHub issues, with the Android version, TikTok
version, and whether the reel is public. Please open an issue to discuss a change before
sending a pull request; this is a small personal tool and not every feature fits.

## License

Apache-2.0. See LICENSE.
