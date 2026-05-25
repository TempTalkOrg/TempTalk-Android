# Detekt Baseline — Classification Index

This document classifies every entry in the Detekt baseline files for
TempTalk Android, per the categorization scheme adopted for issue #723.

**Why this file exists**: baselines without justification become problem-hiding
layers. Each entry below has a category that determines its lifecycle.

---

## Baseline file locations

Baselines are stored per-module (not at root) because Detekt's multi-module
Gradle plugin can't share a single baseline file across modules without
last-writer-wins clobbering:

```
:app       → app/config/detekt/baseline.xml
:base      → base/config/detekt/baseline.xml
:call      → call/config/detekt/baseline.xml
:chat      → chat/config/detekt/baseline.xml
:database  → database/config/detekt/baseline.xml
:network   → network/config/detekt/baseline.xml
```

Modules with **0 baseline entries** at issue #723 land time:
`:image-editor`, `:login`, `:security`, `:selector`, `:video`. New violations
in those modules will fail CI immediately — no historical noise to absorb.

---

## Classification scheme

Every baseline entry MUST fit one of these four categories:

| Category | Meaning | Disposition | Lifecycle |
|---|---|---|---|
| **LEGITIMATE** | Intentional design choice (e.g., OkHttp Interceptor sync API). Should NEVER reach baseline — use in-code `@Suppress("RuleName")` + KDoc instead. | ✅ migrated to `@Suppress` | None — should be empty |
| **REAL_BUG** | Confirmed problem that needs fixing. Must be removed from baseline as part of the follow-up PR. | ⚠️ tracked here with target PR | Removed in PR 2 |
| **TECH_DEBT** | Acknowledged code smell. Not a bug, but ideally cleaned up over time. No deadline. | 🟡 stays in baseline | Removed opportunistically |
| **UNKNOWN** | Needs investigation. Often present when a rule has high FP rate without type resolution. | 🔍 needs call-chain analysis | Reclassified after analysis |

**Rule**: when fixing or modifying production code, you MUST also remove the
corresponding baseline entry (and update this README) in the same PR. Do not
let baseline entries go stale.

---

## Entries by rule

### `BanRunBlockingOutsideTests` (10 entries in baseline)

17 originally-found entries are **NOT in baseline** — they were migrated to
in-code `@Suppress("BanRunBlockingOutsideTests")` with KDoc rationale.
See "Legitimate suppressions" section below for the index.

All `BanRunBlockingOutsideTests` entries have been resolved in PR 2. See "Resolved in PR 2" section below.

### Resolved in PR 2

| Module | File:Line | Action |
|---|---|---|
| `:app` | `LCallToChatControllerImpl.getDisplayName(context, id)` | **Deleted** — 0 callers in production (interface method + impl both removed) |
| `:app` | `LCallToChatControllerImpl.getTheirPublicKey(uid)` | `@Suppress` + KDoc — caller is `CallRtmFactory` RTM decryptor lambda on SDK background thread, not Main |
| `:chat` | `MessageNotificationUtil.showCallNotificationNew(...)` (2 entries) | **Refactored** — wrapped body in `appScope.launch { ... }`; resolve `getContactWithID` / `getSingleGroupInfo` off Main, then post notification. No more `runBlocking { HTTP }` on the Main-dispatched incoming-call path. |
| `:app` | `LogoutManagerImpl.clearStoragesForAuthOnly` / `clearStoragesForFullClear` | `@Suppress` + KDoc — 3 s bounded block accepted by design (logout → restartApp; sub-5 s ANR threshold; user-initiated wait). |
| `:base` | `InsetAwareConstraintLayout.Companion.getKeyboardHeight` | `@Suppress` + KDoc — 1 s bounded layout-init read; DataStore pre-warmed (#725), cold-start edge case only. |
| `:call` | `CallFeedbackManager.readBlocking` / `writeBlocking` | `@Suppress` + KDoc — 1 s bounded call-end SP bridge; same pattern as InsetAwareConstraintLayout. |
| `:chat` | `PushTextSendJob.uploadAttachment` (line 391) | **Refactored** — function converted to `suspend fun`, `runBlocking { groupUtil.getSingleGroupInfo(...) }` replaced with direct suspend call. Already on JobRunner IO scope. |
| `:chat` + `:database` (multi) | 17 classes with wcdb-in-suspend (all 42 entries) | Class-level `@Suppress("BlockingWcdbInSuspend")` + 1-line KDoc anchoring each class to its IO entry point (`appScope.launch(Dispatchers.IO)` / JobRunner IO / `viewModelScope.launch(Dispatchers.IO)` / `withContext(Dispatchers.IO)` upstream). |
| `:database` | `ContactRemarkCache.preload` (2 entries) | KDoc requiring caller in `Dispatchers.IO`-bound coroutine + `@Suppress`. |

### `BlockingWcdbInSuspend` (0 entries in baseline)

All 42 entries resolved in PR 2 — see the "Resolved in PR 2" table above.

### `LongMethod` (52 entries in baseline) — **TECH_DEBT**

Methods exceeding 100 lines. Historical, generally not blocking. Cleanup
opportunistic per module owner. No follow-up PR.

### `LargeClass` (11 entries in baseline) — **TECH_DEBT**

Classes exceeding 500 lines (matches CLAUDE.md "NO files >500 lines" hard
rule). Historical. Cleanup as classes are refactored. No follow-up PR.

---

## Legitimate suppressions (migrated to `@Suppress` in code)

These 17 sites were originally Detekt violations but reflect design intent
documented in their respective class/function KDocs. Tracked here for
auditability:

| File:Line | Why suppressed |
|---|---|
| `HttpClientInterceptor.kt` (`intercept`, `changeHostAndReSendRequest`) | OkHttp `Interceptor.intercept` is sync API; runs on OkHttp dispatcher background thread, never Main |
| `DatabaseRecoveryPreferences.kt` (class-level) | All public methods bounded 2s timeout for "flush before `Process.killProcess()`" semantic on DB-recovery startup path |
| `TempTalkApplication.kt:initStorageLayer`, `initUserData` | Startup-only 2s bounded; 承重墙 design from #722 |
| `StorageBoundUserManagerImpl.kt:setUserData/update (commit=true)` | Non-suspend bridge for logout/login-critical paths; explicitly opt-in by caller |
| `GlobalConfigsManager.kt:loadInitialConfigBlocking` | DataStore pre-warmed by #725 StoragePreloader; 76+ legacy non-suspend caller sites |
| `CallServiceUrlManager.kt:loadFromDiskLocked, persistLocked` | DataStore pre-warmed; all callers already dispatch to IO per #725 design §3.7 |
| `JobManager.kt:getDebugInfo, flush` | `@WorkerThread` contract; production callers test-only |

Note: `LogoutManagerImpl`, `InsetAwareConstraintLayout.getKeyboardHeight`,
and `CallFeedbackManager.readBlocking/writeBlocking` were initially included
here but moved to **UNKNOWN baseline entries** because they involve bounded
main-thread blocking (1–3 s). PR 2 will revisit each — the bounded design
may still be the right call, but it deserves explicit decision rather than
implicit acceptance.

---

## Baseline maintenance commands

```bash
# Regenerate all per-module baselines from current code state
./gradlew detektBaseline

# Verify no violations escape current baselines
./gradlew detekt

# Inspect what's in a specific module's baseline
cat <module>/config/detekt/baseline.xml | grep -oE "<ID>.*</ID>"
```

**When you fix a REAL_BUG or TECH_DEBT entry**:
1. Modify the source code
2. Remove the corresponding `<ID>` line(s) from the relevant module's `baseline.xml`
3. Update this README — move the entry from active to "Resolved" archive section
4. Verify `./gradlew detekt` still passes

**When you introduce a new design-intentional `runBlocking`** (or similar):
DO NOT add to baseline. Add `@Suppress("BanRunBlockingOutsideTests")` directly
at the function/property declaration with a KDoc explaining why.
