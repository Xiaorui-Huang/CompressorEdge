# Handoff — cloud sandbox session ending 2026-09-29

Read this first, then `CLAUDE.md` (repo conventions, branch topology, merge
notes) and `TODO.md` (feature backlog + status + changelog) — this file is
the narrative connecting them for whoever picks this up next, likely a local
agent with things this cloud sandbox didn't have: a working NDK toolchain,
network access to `dl.google.com`, and/or a real device or emulator.

## TL;DR state

- **`personal/edge`** (`8473204`) is the integration branch — build the fork
  APK from this, not `main`. CI is green
  (`.github/workflows/push-build.yml`, triggers on push to any non-`main`
  branch). Nothing uncommitted anywhere in the repo as of this handoff.
- Two features shipped and hardened this session: **A2 metadata
  preservation** and **A3 replace-original**. Both marked **Done** in
  `TODO.md`'s audit table.
- **A4 (multi-file share)** was already shipped before this session (a
  manifest fix).
- **A1 (VMAF quality report)** is **not implemented** — only a throwaway
  sizing spike exists (`feature/vmaf-spike`, not merged). This is the one
  open item, and the one most likely to need a local agent specifically —
  see "A1 — what's next" below.

## What actually happened this session, briefly

1. Branch topology was split from one working branch into
   `sync/compressor-upstream` / `feature/*` / `personal/edge` (see
   `CLAUDE.md` "Branch topology" — read this before touching branches,
   it has a specific merge/rebase procedure and an exception for
   dependent features).
2. A2 (metadata preservation) and A3 (replace-original) were implemented
   on their own feature branches and merged into `personal/edge`.
3. **An 11-pass iterative code-review loop** (explicitly requested by the
   user, later explicitly stopped by the user once it started finding a
   bug in its own previous fix) found and fixed **29 real bugs** across
   both features — races, silently-dropped saves, main-thread-blocking
   I/O, a foreground auto-save trigger that fired before the state it
   depended on was ever updated, etc. All fixed, all merged, all CI-green.
   Don't re-run that loop reflexively — it converged (a `--level max` pass
   came back empty) and the user asked to stop after it started
   regressing on itself. If you have a real reason to suspect these two
   features (a bug report, a new related feature), review them fresh
   rather than assuming there's more to find.
4. **A1 (VMAF) spike**: this sandbox has no NDK toolchain, no
   `dl.google.com` access (blocks Gradle/AGP resolution entirely — see
   "Environment differences" below), and no device/emulator. So instead of
   trying to build VMAF into the app, a **measurement-only spike** ran in
   GitHub Actions CI (which has full network + toolchains) on a throwaway
   `feature/vmaf-spike` branch: cross-compile Netflix/vmaf's `libvmaf` for
   `arm64-v8a` only, via meson, and report the real `.so` size. That
   itself needed two review-and-fix rounds (see `TODO.md`'s changelog and
   "A1 spike results" section for the play-by-play, including a fix
   attempt that broke the build and had to be reverted — CI caught it
   immediately, which is exactly why this workflow-based approach was
   used instead of guessing).

## A1 — what's next (the actual work for a local agent)

**Real numbers already in hand** (`TODO.md` → "A1 spike results"), not
estimates: a single-ABI (`arm64-v8a`) `libvmaf.so` is **716 KB (minsize
build, stripped)** to **814 KB (release build, stripped)**, with the
default quality models compiled directly into the library (no separate
asset file). This resolves the size/footprint objection that was A1's
main blocker — even two ABIs stays comfortably under the fork's own
"~10 MB" character.

**What's still unknown, and is now the only thing blocking a go/no-go
call**: wall-clock cost of actually scoring a real clip on a real device.
The spike proves the library builds and is small; it does not run any
scoring, because that needs decoded frame buffers fed through libvmaf's C
API (`vmaf_read_pictures` / `vmaf_score_pooled`) from a JNI wrapper,
executed and timed on real hardware.

If you have a local NDK + device/emulator, the concrete next steps are:

1. Look at `feature/vmaf-spike`'s `.github/workflows/vmaf-spike.yml` for
   the known-working meson/NDK cross-compile config (pinned to
   `Netflix/vmaf` commit `17a67b238ce0539bdeafdc95961abac64fa16ea8`, API
   26 to match this app's `minSdk`). That's a *standalone* meson build
   outside Gradle — for real app integration you'll want to either wire
   libvmaf into `app/build.gradle.kts` via `externalNativeBuild` (CMake
   wrapping the meson build, or a Gradle task that shells out to
   meson/ninja) or produce a prebuilt `.so`/headers pair and check it in
   under `app/src/main/jniLibs/arm64-v8a/`.
2. Write a small JNI wrapper (C or C++) that: opens two decoded YUV frame
   streams (source + compressed output — Media3/`MediaCodec` can give you
   raw frames, or decode both files with `MediaExtractor`/`MediaCodec` to
   YUV420), feeds them through `vmaf_read_pictures`, and calls
   `vmaf_score_pooled` at the end. Netflix's own `libvmaf/tools/vmaf.c`
   CLI tool (built as part of the spike, just not shipped) is the
   reference implementation for the read/score loop — read it before
   writing your own.
3. Time it: run against one real 1080p clip on one real mid-range device,
   per the original handoff doc's ask. Record the actual number in
   `TODO.md`.
4. Decide go/no-go using both real numbers (size + perf), then either
   build out A1 properly (bands, settings toggle, notification, self-check
   per `TODO.md`'s "Recommended design" section) or drop it and note why.
5. If VMAF isn't wired into `app/build.gradle.kts` for a real integration
   pass, an SSIM-on-sampled-frames alternative (pure JVM/Kotlin, or a
   much smaller native routine) remains a documented fallback in
   `TODO.md` if the perf number comes back too slow — see B1 in "B-question
   findings".
6. Delete `feature/vmaf-spike` and its CI workflow once you've extracted
   what you need from it, per its own header comment ("Delete this
   workflow... once A1's go/no-go decision is made").

## Environment differences you should know about

This cloud sandbox specifically could not: resolve Google's Maven
(`dl.google.com` blocked — meant `./gradlew` never worked here at all,
verification was CI-only the entire session), run an NDK/CMake native
build, or run anything requiring a device/emulator. None of that may be
true locally. Concretely this means:

- **You can probably run `./gradlew assembleDebug`/`compileDebugKotlin`
  directly** to verify changes, instead of the CI-round-trip workflow
  this session used throughout (push → poll GitHub Actions →
  read job logs). Prefer the local build — it's faster and doesn't spam
  CI. `CLAUDE.md`'s network note describes the cloud-sandbox limitation
  in more detail in case you hit the same thing in a different
  environment.
- **You can actually build and test A1's native code**, which is the
  entire reason A1 stalled here.
- Git identity: commits in this repo use the human owner's name/email,
  not an AI identity — this was configured in the working directory's
  git config throughout the session (see `CLAUDE.md` "Git identity"). If
  your local checkout doesn't have `user.name`/`user.email` set to
  match, set them before committing.

## Branch pointers (as of this handoff)

| Branch | Head | Status |
|---|---|---|
| `personal/edge` | `8473204` | Integration branch — build from here. CI green. |
| `sync/compressor-upstream` | `5633337` | Merge-tracking only, untouched this session beyond the initial split. |
| `feature/metadata-preservation` | merged into `personal/edge` | A2, done. |
| `feature/replace-original` | `dbe8c0b` | A3, done, merged into `personal/edge`. Depends on `feature/save-next-to-original` + `feature/metadata-preservation` (see `CLAUDE.md` branch-topology exception). |
| `feature/vmaf-spike` | `cbf8655` | A1 sizing spike only. **Not merged, not app code, throwaway.** Reference only. |
| `feature/save-next-to-original`, `feature/multi-file-share`, `feature/ci-personal-builds` | merged into `personal/edge` earlier | Pre-existing, untouched this session except as merge inputs. |
| `backup/pre-branch-split-20260928` | frozen | Reference/diff only, per `CLAUDE.md`. Don't build from it. |

## Files worth reading, in order

1. `CLAUDE.md` — repo conventions, branch topology and its merge
   procedure, upstream-merge notes, the `dl.google.com` limitation.
2. `TODO.md` — the actual feature backlog: A1–A4 status table, the B1–B5
   question findings from the original handoff doc, recommended designs
   for anything not-yet-Done, the full changelog (dated, in order — the
   review-loop entries and A1 spike entries are both there with real
   numbers and links to the CI runs that produced them).
3. This file, for the narrative connecting the two.

Everything below `TODO.md`'s "Open questions for you" section is
genuinely still open and needs a human (or an agent with tools this one
didn't have) to answer or act on — it's not a TODO in the sense of
"forgot to do this," it's "blocked on something this session's tools
couldn't provide."
