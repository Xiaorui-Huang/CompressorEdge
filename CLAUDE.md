# CompressorEdge — notes for future work

## What this repo is

CompressorEdge is a fork/edge-channel of [JoshAtticus/Compressor](https://github.com/JoshAtticus/Compressor)
(the "upstream" repo, added here as git remote `compressor`). Compressor is the
stable, wider-audience app; CompressorEdge ships extra features early (batch/queue
compression, background compression with Live Activities) but historically lagged
behind upstream on bug fixes, especially around audio compression.

Key naming difference: upstream's Kotlin package is `compress.joshattic.us`;
this fork renamed everything to `compressedge.joshattic.us` (applicationId
`compressedge.joshattic.us`). Any future merge from upstream will hit this
rename as a `modify/delete` conflict on every file upstream touches — see
"Merging upstream" below.

## Branch topology

This repo intentionally keeps "pull in upstream" and "our own features"
separate, so a future upstream sync doesn't have to be untangled from
whatever feature work is in flight, and any one feature can be dropped or
reworked without touching the others. Four kinds of branch:

- **`sync/compressor-upstream`** — the merge-tracking branch. Contains only:
  fork history + periodic `git merge compressor/main` commits + any fixes
  needed to make that merge actually compile/work (e.g. a compile error the
  merge itself introduced). Never contains a feature. Updated by fetching
  `compressor` and merging its `main` into this branch again next time
  upstream has a release worth taking.
- **`feature/<name>`** — one independent feature per branch (currently
  `feature/multi-file-share`, `feature/save-next-to-original`,
  `feature/ci-personal-builds`), each based on (and rebased onto, when
  `sync/compressor-upstream` moves) the tip of `sync/compressor-upstream`.
  Each should be buildable/reviewable in isolation.
- **`personal/edge`** — the integration branch: `sync/compressor-upstream`
  at the bottom, every `feature/*` branch merged on top (`--no-ff`, so each
  feature's merge is visible as its own commit in `git log --graph`), plus
  this repo's own documentation (`CLAUDE.md`, `TODO.md`). **This is the
  branch to build the custom fork APK from and the one CI runs against for
  day-to-day development** — not `main`.
- **`backup/pre-branch-split-<date>`** — a frozen snapshot taken before this
  topology existed (the old single working branch, everything jammed
  together). Kept for reference/diffing only; not meant to be built from or
  developed on.

To update after upstream releases: `git fetch compressor && git checkout
sync/compressor-upstream && git merge compressor/main`, fix whatever that
needs (commit directly on `sync/compressor-upstream`), then on each
`feature/*` branch `git rebase sync/compressor-upstream` (resolving any
conflicts with the new upstream code there, not on `personal/edge`, and
force-pushing each `feature/*` branch afterward since rebase rewrites it —
fine here since these are personal working branches, not something anyone
else builds a PR on top of).

Then rebuild `personal/edge` itself: don't try to rebase it (it has merge
commits, which rebase mangles) — instead build a throwaway branch the same
way it was first built (`git checkout -b personal/edge-next
sync/compressor-upstream`, then `git merge --no-ff` each `feature/*`
branch in turn, then re-copy `CLAUDE.md`/`TODO.md` if they changed), verify
it (`git diff personal/edge personal/edge-next` should show only the
changes you expect — new upstream code plus whatever the features'
rebases picked up), then move the `personal/edge` ref onto it: `git branch
-f personal/edge personal/edge-next && git checkout personal/edge && git
branch -D personal/edge-next`. This **rewrites `personal/edge`'s history**
(same reason as the `feature/*` rebases above), so a `git push --force
origin personal/edge` is required afterward, and any in-flight PR opened
from `personal/edge` or local checkout tracking its old tip will need to
be rebased or recreated — acceptable for a personal integration branch
nobody else bases work on, but confirm that's still true before forcing.

To add a new feature: branch it off the current `sync/compressor-upstream`
tip (not off `personal/edge`), then merge it into `personal/edge` once it's
ready. **Exception**: if the feature genuinely depends on another feature
already merged into `personal/edge` (reuses its helpers, extends its state)
rather than being independent, branch off `personal/edge` instead — that's
what `feature/replace-original` did, since it reuses
`feature/save-next-to-original`'s MediaStore-relative-path helpers and
`feature/metadata-preservation`'s verification check. Don't force
independence where there isn't any; just branch from wherever the
dependency is actually satisfied, and note the dependency in the feature's
own commit message.

## Repo structure quick reference

- `app/src/main/java/compressedge/joshattic/us/`
  - `MainActivity.kt` — handles share-sheet intents (`ACTION_SEND` /
    `ACTION_SEND_MULTIPLE`) and forwards selected URIs into the ViewModel.
  - `viewmodel/CompressorViewModel.kt` — the app's single big ViewModel (~2400
    lines). Owns `CompressorUiState`, drives compression (single item, batch
    queue, and background-service modes), persists settings to
    `SharedPreferences`, and handles saving compressed output.
  - `compression/CompressionExecutor.kt` — actually builds and runs the
    Media3 `Transformer` for a single item (this file doesn't exist upstream;
    upstream builds the `Transformer` inline in its ViewModel). Owns the
    audio-passthrough optimization, HDR tone-mapping, watchdog timeout, codec
    selection.
  - `compression/BackgroundCompressionService.kt` /
    `BackgroundCompressionManager` — foreground service + shared state object
    used when "Background Compression" is enabled, so compression survives
    the app being backgrounded/killed.
  - `model/CompressorUiState.kt` — the app's single state data class.
  - `model/QueueItem.kt` — one item in the batch queue; carries the original
    source `Uri` plus per-item overrides and (once done) `compressedUri`.
  - `ui/screens/settings/DisplaySettingsScreen.kt` — output/save-location
    settings UI (auto-save to Photos, background compression toggle, custom
    output folder picker, "save next to original" toggle, filename builder).

## How compression + saving actually flows

1. `startCompression` / `startBackgroundCompression` build `itemsToProcess`
   (either `state.queue` in batch mode, or a synthetic single-item list built
   from `state.selectedUri` + friends in single mode) — **always** in the same
   order, with each item's original source `Uri` at `item.uri`.
2. Each item is compressed to a temp file under `context.cacheDir`, and the
   resulting `file://` URI is appended to `completedUris` in the same order
   as `itemsToProcess`. This list ends up in `state.compressedUris` (and
   `state.compressedOriginalUris`, added for the "save next to original"
   feature — see below), and `state.compressedUri` holds the last one.
3. `saveCompressedOutput(context)` is the single entry point the UI calls to
   persist output. It decides between:
   - **Save next to original** (`state.saveNextToOriginal`): for each item,
     query the *original* URI's `MediaStore.MediaColumns.RELATIVE_PATH` (this
     only resolves when the original is itself a MediaStore item — e.g. a
     video from the device gallery/camera, which is the overwhelmingly common
     case for a compression app). If that resolves, insert the compressed
     file into MediaStore at that same relative path — i.e. genuinely next to
     the original. Anything that doesn't resolve (original came from a
     content provider that isn't MediaStore-backed, e.g. some chat apps'
     "share" providers) falls back per-item to whichever destination is
     configured below.
   - **Custom SAF folder** (`state.customOutputTreeUri` set): write into that
     `DocumentFile` tree.
   - **Default**: insert into `MediaStore.Video` under
     `Movies/Compressor Edge`.
   Scoped storage (Android 10+) does *not* require any extra runtime
   permission for either the MediaStore-relative-path trick or the default
   path — inserting a *new* MediaStore item under a public top-level
   directory (Movies/DCIM/etc.) is always allowed for the app that creates
   it. This is why "if you have permission, otherwise fall back" mostly
   reduces to "if the original's directory info is resolvable at all."
   True same-folder writes via direct filesystem paths would require
   `MANAGE_EXTERNAL_STORAGE`, which this app deliberately doesn't request.

## Sharing / bulk import

`MainActivity.handleShareIntent` already had code for both `ACTION_SEND` and
`ACTION_SEND_MULTIPLE` before any of this session's changes — but
`AndroidManifest.xml`'s intent-filter only advertised `ACTION_SEND`, so
Android's share sheet / file pickers never actually offered CompressorEdge as
a "share multiple videos" target. The fix was just adding the matching
`SEND_MULTIPLE` intent-filter to the manifest (see `MainActivity`'s
`<activity>` entry) — the Kotlin-side handling for a list of URIs
(`viewModel.updateSelectedUris`, which populates the batch queue) was already
correct. **Lesson: when a feature "isn't working," check the manifest before
assuming the code path is missing** — Android intent-filter wiring is a very
common silent gap between "the code handles it" and "the OS will ever call
it."

## Merging upstream (`compressor` remote)

To pull in upstream fixes, do this on `sync/compressor-upstream` (see
"Branch topology" above), never on `personal/edge` or a `feature/*` branch
directly: `git fetch compressor main && git merge compressor/main`. Expect
conflicts roughly proportional to how much upstream touched files this fork
also customized. As of the last sync (merge commit `fa90c64`, originally
landed on the now-retired single working branch before the topology above
was introduced):

- The package rename (`compress.joshattic.us` → `compressedge.joshattic.us`)
  turns every upstream-only new file into a `modify/delete` or
  "file location" conflict. Resolve by porting the upstream file's *content*
  into the equivalent path under `compressedge/joshattic/us/...` and fixing
  its `package` declaration — do not keep anything under the old
  `compress/joshattic/us` path.
- `CompressorViewModel.kt` is the highest-risk file: upstream still builds
  the `Transformer` inline there, while this fork delegates that to
  `CompressionExecutor.kt`. When upstream lands a compression-engine fix
  (audio passthrough, HDR tone-mapping, watchdog timeout, codec probing,
  etc.), the logic needs to be hand-ported into `CompressionExecutor.kt`
  rather than merged inline into the ViewModel.
  - Also watch for the batch/queue loop structure (`updateSelectedUris`,
    `startCompression`, `startBackgroundCompression`, `buildCompressionPlan`)
    — this fork's structure doesn't exist upstream at all, so a 3-way diff
    can misplace braces around it. After any hand-merge here, sanity-check
    with a brace/paren balance count and re-read the touched function
    boundaries; a full `./gradlew compileDebugKotlin` is the real
    verification once network access allows it (see below).
- `strings.xml` (all locales) conflicts are almost always just "what's new"
  dialog content reusing the same string keys for different release notes —
  keep this fork's own release notes/translations, not upstream's.
- `app/build.gradle.kts` / `gradle.properties`: keep this fork's own
  `versionCode`/`versionName` (independent numbering from upstream).

**Network note**: this sandbox's environment network policy blocks
`dl.google.com`, which the Android Gradle Plugin needs to resolve from Google
Maven. `./gradlew compileDebugKotlin` cannot run here as a result (fails at
plugin resolution, `--offline` also fails with nothing cached). Broaden the
environment's network access (cloud environment menu → Edit → Network
access, allow `dl.google.com` or a broader preset) before relying on a
Gradle build to verify a merge/change in this environment. Until then,
static checks (grep for conflict markers, brace/paren balance, XML
well-formedness, cross-referencing `R.string.x` usages against
`values/strings.xml`) are the fallback verification method.

**Better alternative — use CI instead of a local build for verification**:
GitHub Actions runners have full internet access (no `dl.google.com` block),
so a real build there is a far stronger signal than the static checks above.
This fork already inherited two Actions workflows from upstream:
- `.github/workflows/pr-build.yml` — builds `assembleDebug` and uploads the
  APK as an artifact whenever a PR targets `main` (this is the "upstream
  contribution" path — keep using it as-is for any PR opened against
  upstream or this fork's own `main`). It also accepts `workflow_dispatch`
  (with a `pr_number` input) so the `/rebuild` PR-comment command
  (`pr-commands.yml`) can re-trigger it.
- `.github/workflows/push-build.yml` (added in this session) — the personal/
  testing counterpart: builds `assembleDebug` on every push to any branch
  *other than* `main` (e.g. this fork's own working branches), no PR
  required. Purely for quick manual on-device testing — not a release
  workflow (no signing/publishing). Uploads the APK (or the build log on
  failure) as a build artifact.

Trigger either by pushing, or manually via the Actions tab / `gh workflow run`
(needs a token with `actions:write`, not available in this sandbox's git
proxy). Neither workflow signs or publishes anything, so they're safe to run
freely and won't interfere with however actual signed releases get cut.

## Git identity / attribution note

Commits to this repo should use the human owner's name/email
(`git config user.name` / `user.email`, set locally in this checkout), not an
AI assistant identity — no AI co-author trailers on commits here.
