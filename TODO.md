# CompressorEdge feature backlog — VMAF, metadata, replace-original, batch share

> Tracks the handoff doc's A1-A4 items. Check items off as they land; each
> checked box should point at a commit. Do not implement an item until it's
> been explicitly confirmed (see "Status" column) — this file is a plan, not
> a queue to work through unattended.

## Step 0 audit (done 2026-09-28)

| Item | Status | Evidence | Gap |
|---|---|---|---|
| A1 — VMAF quality report | **Missing** | No `vmaf`/`ssim`/`quality.?score` hits anywhere in `app/src/main/java`; no libvmaf/native dependency in `gradle/libs.versions.toml` or `app/build.gradle.kts`; `git log --all --grep` for vmaf/quality-score is empty. | Everything — scoring, bands, settings toggle, notification, self-check. |
| A2 — Metadata preservation + verification | **Done** (`feature/metadata-preservation`) | Creation date + GPS location are now read from the source via `MediaMetadataRetriever` and injected into the output via `InAppMp4Muxer.Factory`'s `MetadataProvider` (`Mp4TimestampData`/`Mp4LocationData`, verified against real `androidx/media` source rather than guessed). Output is re-read and compared after compression; a field that was present on the source but didn't survive is reported via the existing warnings UI. | Not covered: the MediaStore-visible relative gallery path (distinct from container metadata — see A3's `originalRelativePath` for the closest existing thing) and rotation (already correct — applied to the track itself by Media3's normal pipeline, never actually broken). |
| A3 — Replace-original w/ backup, save-alongside | **Done** (`feature/replace-original`, depends on `feature/save-next-to-original` + `feature/metadata-preservation`) | New `replaceOriginal` setting: verifies the compressed output (playback/track probe, duration within tolerance, the A2 metadata check) before touching anything, backs up the original to internal storage, deletes it (`MediaStore.createDeleteRequest` batched on API 30+, `RecoverableSecurityException` consent flow on exactly API 29, a plain attempt with no dialog on API 26-28), then inserts the compressed file at the same MediaStore relative path/name. An "Undo" action on the result screen restores from the backup. Anything that fails verification, isn't MediaStore-resolvable, or gets consent-denied never touches the original — it falls back to the default save location instead (no VMAF gate since A1 doesn't exist yet). | Known scope limit, documented in code: on exactly API 29, a *batch* of more than one replace candidate needing consent falls back rather than chaining several consent dialogs (API 30+ batches cleanly via `createDeleteRequest`; API 26-28 typically doesn't need consent at all). Not addressed: a direct SAF parent-folder write for non-MediaStore originals (same gap as save-next-to-original). |
| A4 — Share multiple videos into the app | **Already exists** | Shipped this session: `AndroidManifest.xml` `SEND_MULTIPLE` intent-filter + `MainActivity.handleShareIntent()` already parsed `ACTION_SEND_MULTIPLE` before that (was unreachable until the manifest fix) → feeds `viewModel.updateSelectedUris()`, the same entry point the in-app picker uses for batch mode. | None — nothing to build. |

## B-question findings

**B2 — upstream 1.6.4 metadata/audio status** (answered from code, high confidence — this repo's own merge from `compressor/main` landed these fixes in this session):
- Rotation: read and used to orient UI (portrait/landscape), and Media3's `Transformer`/muxer bakes correct playback orientation into the output track itself — this part is fine and doesn't need new work.
- Creation date/GPS: **not preserved at all**, confirmed above (A2).
- Audio: the over-compression bug **is fixed**. `CompressionExecutor.kt` (`probeAudioTrack`, `audioPassthrough` at lines ~120-125) skips re-encoding entirely when the source audio is already AAC-LC at a matching profile — verified present and wired correctly by an independent review pass during this session's upstream merge (not just copy-pasted and unused).
- Correction to the handoff doc: `app/build.gradle.kts` sets `minSdk = 26`, not 24. Any Android-version-gated design (e.g. `MediaStore.createDeleteRequest()` on API 30+, `RecoverableSecurityException` on 29) should use 26 as the floor.

**B3 — output location / replacing files** (mostly answered from code + platform knowledge):
- Save-alongside already exists (see A3).
- Replace-original does not exist (see A3).
- Platform behavior for deleting/replacing a file the app doesn't own (well-documented Android behavior, not something to "measure"): on API 26-28, legacy storage rules apply (no extra consent beyond `WRITE_EXTERNAL_STORAGE`, which this app doesn't hold and shouldn't add just for this — see "no broad storage permission" note below). On API 29, deleting/overwriting another app's MediaStore-owned row throws `RecoverableSecurityException`, whose `IntentSender` must be launched for one-time user consent. On API 30+, batch this through `MediaStore.createDeleteRequest()` (single system dialog) instead of per-file exception handling. None of this needs `MANAGE_EXTERNAL_STORAGE` — matches upstream's deliberate avoidance of that permission.
- Rollback copy location: `context.cacheDir` (already used for in-flight compression output) is wrong for a rollback that must survive the OS killing/evicting cache under storage pressure — `context.filesDir` (internal, not user-visible, not auto-cleared) with an explicit "purge on next successful confirm or after N days" policy is the safer choice. This is a design recommendation, not a verified measurement.

**B4 — share sheet**: fully answered — see A4, "Already exists."

**B5 — notification**:
- `POST_NOTIFICATIONS` is already declared in `AndroidManifest.xml` and the existing `BackgroundCompressionService` already creates and uses a notification channel (`CHANNEL_ID = "background_compression"`, `createNotificationChannel()` in `BackgroundCompressionService.kt`). A VMAF-completion notification can reuse that channel and its existing runtime-permission handling rather than adding a new one — small addition, not new plumbing.

**B1 — VMAF feasibility**: **cannot be answered responsibly from static code reading alone**, and I'm not going to fabricate APK-size or accuracy numbers I haven't measured (that's an explicit instruction in the handoff doc, and I agree with it). What I can say without a device/build experiment:
- No libvmaf dependency exists in this repo today, so every part of A1 is new native-code integration, which cuts directly against this fork's stated "no third-party libraries / under ~10 MB" character — this is the central tension of A1, not a detail.
- A lighter-weight SSIM-on-sampled-decoded-frames approach (pure Kotlin/JVM or a tiny native routine, no NDK model-file payload) is a real alternative worth prototyping *first*, specifically because it avoids the model-asset size cost and ABI-per-architecture native lib duplication that make VMAF's footprint hard to predict without building it.
- Recommendation: before committing to full libvmaf integration, build a throwaway spike branch that adds libvmaf for a **single ABI only** and measure the actual `.so` + model-asset size delta, plus wall-clock cost of scoring one real 1080p clip on one real mid-range device — then decide. I can build that spike if you want it, but I want to flag now that it needs a real device or at minimum a real Android emulator with the NDK toolchain, neither of which I have in this sandboxed session (no network access to fetch libvmaf sources/prebuilts, no device attached) — the earlier `dl.google.com` block noted in `CLAUDE.md` also limited Gradle itself, and even with that separately resolved, an NDK/CMake native build has its own toolchain and source-fetch requirements I haven't verified are available here.

## Recommended design (for Missing/Partial items only)

### A2 — Metadata (recommend building this first: smallest, least ambiguous, no native code)
- Add `androidx.exifinterface:exifinterface` (small, pure-AndroidX, no native code — doesn't compromise the "no third-party libraries" goal the way libvmaf would) to read the **container-level** creation date/GPS from the source video via `MediaMetadataRetriever` (`METADATA_KEY_DATE`) and/or the MediaStore row (`DATE_TAKEN`, if the source is MediaStore-backed) — video containers don't carry EXIF the way photos do, so this is really "read from MediaStore/MP4 metadata atoms, write into the output's MP4 metadata atoms and/or the destination MediaStore row," not literal EXIF tag copying.
- Write path: Media3's muxer stack doesn't auto-carry this; need explicit `Metadata.Entry` injection (e.g. `Mp4LocationData`, a custom `©day`/creation-time entry) into the muxer, or — the simpler, lower-risk option — set it on the **destination MediaStore row** (`DATE_TAKEN`, and `MediaStore.MediaColumns.GROUP_ID`/location columns don't exist for video the same way; GPS on video MediaStore rows isn't a first-class column, so GPS likely has to live in the container metadata, not MediaStore).
- Verification step: re-open the written output (via `MediaMetadataRetriever` on the new file) and diff against what was captured from the source, report per-field pass/fail rather than a single boolean.

### A3 delta — Replace-original mode
- Gate behind: successful playback probe (open the compressed output with `MediaExtractor`/`MediaMetadataRetriever`, confirm track count and duration within tolerance of source), metadata-verification pass (once A2 exists), and — if VMAF is enabled — score above a user-visible, settings-exposed threshold.
- Rollback: copy original to `context.filesDir/rollback/` before delete, keep until either the user confirms (explicit "keep" action surfaced in the UI, not just "no crash") or a time/count-based eviction (e.g. 3 days or on next successful compression, whichever first) — needs a UI affordance, not just background logic, or a rollback copy sitting silently in internal storage is worse than the original bug.
- Delete flow: `RecoverableSecurityException` handling for API 29, `MediaStore.createDeleteRequest()` for API 30+, direct delete for 26-28 legacy paths — per B3 above.

### A1 — VMAF (recommend as a spike, not a committed feature yet)
- Do the single-ABI size/perf spike described in B1 before any UI/settings work. Everything in A1.1-A1.7 (bands, both-mode, notification, self-check) is UI/plumbing that's easy to build once the underlying scorer exists and is sized/timed — building the UI first would be designing around numbers we don't have yet.

## Implementation order (proposed — confirm before I start)

1. ~~A2 metadata preservation + verification~~ — done, see `feature/metadata-preservation`.
2. **A1 spike** — single-ABI libvmaf size/perf measurement (or the SSIM alternative prototype), to get real numbers before deciding whether A1 ships at all, and in what form.
3. ~~A3 replace-original~~ — done, see `feature/replace-original`.
4. A4 is done — nothing to schedule.

Per the branch topology in `CLAUDE.md`, each of the above gets its own
`feature/<name>` branch off the current `sync/compressor-upstream` tip
(e.g. `feature/metadata-preservation`, `feature/vmaf-spike`,
`feature/replace-original`), merged into `personal/edge` once ready —
not committed directly onto `personal/edge`.

## Open questions for you (from the doc, still unanswered without your input)

- B1 default scan mode: doc leans Off — confirm.
- B1 threshold mapping (same bands for both models vs per-model bands): doc asks for a recommendation with evidence; I don't have evidence without the spike, so this is blocked on step 2 above.
- Whether you want the A1 spike done at all before deciding VMAF's fate, or want the SSIM alternative explored first/instead.

## Changelog
- 2026-09-28: Initial audit + backlog created from the v3 handoff doc. A4 confirmed already shipped, A3 confirmed partial (save-alongside shipped, replace-original missing), A1/A2 confirmed fully missing.
- 2026-09-28: Repo split into `sync/compressor-upstream` + `feature/*` + `personal/edge` branches (see `CLAUDE.md` "Branch topology"). This file now lives on `personal/edge`; new feature work should branch off `sync/compressor-upstream`, not off wherever this file happens to be checked out.
- 2026-09-28: A2 (metadata preservation + verification) implemented on `feature/metadata-preservation`, verified against real `androidx/media` source via GitHub code search (not guessed) for the `InAppMp4Muxer`/`Mp4TimestampData`/`Mp4LocationData` API shape, including the 1904-vs-1970 epoch gotcha.
- 2026-09-28: A3 (replace-original) implemented on `feature/replace-original`, branched off `personal/edge` rather than `sync/compressor-upstream` since it depends on both A2 and save-next-to-original (see `CLAUDE.md` "Branch topology" exception note). Verified `MediaStore.createDeleteRequest`'s real signature via GitHub code search before using it.
- 2026-09-28: A2 + A3 hardened via 10 iterative `code-review` passes plus one final whole-feature pass on `feature/replace-original`, all merged into `personal/edge`, CI green after every push (`.github/workflows/push-build.yml`). 28 real bugs found and fixed total — main-thread-blocking I/O, races between concurrent save/replace/undo operations, silently dropped or falsely-reported-successful saves across every save code path, lost/overwritten warnings, no persistence of undo state across process death, and (the most severe, caught only in the final whole-feature pass) the foreground auto-save-to-Photos trigger firing before the compression-result state it depends on was ever updated — meaning auto-save was effectively broken for every foreground (non-background-service) compression before this fix. A2 and A3 remain **Done** in the table above; this line documents the hardening, not a status change.
