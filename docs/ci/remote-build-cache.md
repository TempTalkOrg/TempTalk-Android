# Remote build cache

The HTTP task-output cache is opt-in. The ordinary `PR Checks` workflow uses
`scripts/ci/run-with-build-cache.py` for its unit-test Gradle commands. It leaves
remote caching disabled unless the repository variable `RUNNER_REMOTE_CACHE`
is exactly `enabled`. This PR does not set that variable or enable a consumer.

On the designated ms01 macOS ARM64 runners, private same-repository PRs to
`develop` and manual dispatches read only. The existing nightly schedule on `develop` can write; the helper also supports
non-deletion develop pushes without adding a push trigger. Fork PRs, public repositories, unknown events/hosts, and a disabled
switch run with all four remote-cache variables removed. The helper preserves
other environment values, working directory, arguments, and the build's exit
or signal through `exec`; the existing unit-test/screenshot step IDs, conditions, and report uploads
are preserved. Detekt, lint, packaging, and release steps are not wrapped.

The selected writer reads the `runner-build-cache-production` Keychain item
with account `temptalk-android-writer`, decodes its base64 password, and passes it
only in that build process's environment. It does not write `GITHUB_ENV` or
put the password in command arguments or output. If that lookup fails, the
helper disables HTTP caching and continues the original build with a fixed
message. A reader never reads the writer credential. Writers append
`--no-configuration-cache` to avoid serializing their credential in Gradle's
configuration cache. Compare writer timings against controls with the same
flag; otherwise configuration-cache changes confound the cache measurement.

The service is installed on ms01 at
`http://127.0.0.1:18765/cache/temptalk-android/`; live service health is an
operations check, not evidence of Android correctness or speedup. Names and
event checks prevent accidental activation, not malicious same-UID jobs.
The initial rollout still requires confirmed trusted contributors/consumers
and fork policy. Release, signing and publication workflows are not changed.

An absent or blank `GRADLE_REMOTE_CACHE_URL` leaves the settings-level remote
cache unconfigured outside this helper. Do not inject it globally: other
workflows could then use the settings without this selected-job policy.

| Variable | Behavior |
| --- | --- |
| `GRADLE_REMOTE_CACHE_URL` | HTTPS endpoint, or HTTP with the parsed host exactly `127.0.0.1`. No embedded credentials, query or fragment. Other forms fail configuration. |
| `GRADLE_REMOTE_CACHE_PUSH` | Only the exact value `true` enables upload attempts; unset, blank and other values keep the client read-only. `CI=true` alone does not enable writes. |
| `GRADLE_REMOTE_CACHE_USER` / `GRADLE_REMOTE_CACHE_PASSWORD` | Basic authentication is configured only when both are nonblank. The service must independently authenticate and authorize writes. |

The push flag is a client option, not an identity or trust boundary. A task
can set it itself. A shared host UID does not isolate writer credentials from
other tasks. Enable remote reads and writes only for explicitly trusted jobs;
keep the URL and credentials out of common runner environments. Release,
signing and publication jobs are excluded from the initial rollout and must
not inherit the remote URL. Namespace separation alone is not isolation.

Build caching must still be enabled (`--build-cache` or
`org.gradle.caching=true`). Keep each ephemeral runner's independent
`GRADLE_USER_HOME`; do not share daemon registries or the entire Gradle home.
The existing setup-gradle cache is separate from this HTTP task-output cache.
Its `cache-read-only` option does not govern HTTP uploads. `--offline` disables
the remote cache; it is not a way to test remote hits without dependencies.

Transport, authentication and throttling failures in the Gradle 9.4.1 synthetic
checks disabled the remote cache for that build and allowed execution to
continue. This is not a guarantee of fallback for every failure: an invalid
URL fails configuration, and a corrupt cache archive delivered with HTTP 200
can fail the build. Redirect handling was not verified. A hot local cache hit
does not automatically upload that entry to the remote service.

The configuration uses environment providers. The synthetic configuration-cache
checks observed reuse with unchanged inputs and invalidation when the push
flag changed. Android settings integration, task correctness, cross-directory
hits, representative paired build timings and rollback remain rollout checks;
the synthetic Java/I/O fixture proves no Android performance improvement.

Before enabling this in CI:

1. Finish the runner pilot, then refresh the current settings, Gradle wrapper,
   workflows and applicable repository verification requirements.
2. Check inherited cache-variable presence and URL form without disclosing
   credentials. HTTP `localhost`, IPv6 and other hosts are rejected. Review
   every affected job before merging if variables are already present.
3. Confirm trusted writers and consumers, service authorization, namespace,
   storage limits/cleanup, and exclusion of release/signing jobs. Scope variables
   to the selected build step/job, never all runners.
4. Complete Android checks and paired cache-off/cache-on experiments at fixed
   inputs and concurrency. Verify actual GET/PUT keys, test results and outputs;
   `FROM-CACHE` alone may be a local hit. Keep runner routing unchanged.

For the wrapped CI test steps, disable or remove the repository variable
`RUNNER_REMOTE_CACHE`, then start a new build. Removing only
`GRADLE_REMOTE_CACHE_URL` is insufficient: an enabled helper recreates it.
For direct Gradle invocations without the helper, remove
`GRADLE_REMOTE_CACHE_URL` from the build environment. Both procedures leave
local caching in place and do not change builds already running.
If corrupted outputs are suspected, isolate the remote namespace and remove
only the affected disposable workspace outputs/local task-cache entries before
retrying; do not delete the shared Gradle home. Rolling back the settings code
alone in TempTalk would restore the former `CI=true` writer policy, so disable
the URL first when responding to a cache incident.

The exact DSL and synthetic evidence are in the private infrastructure lab's
[Gradle 9.4.1 validation](https://github.com/difftim/runner-scaleset-lab/blob/3057b9978356350e1c845bf16c0e45839e7de366/GRADLE_CACHE.md).
The helper has isolated policy/process tests. Real Android correctness, paired
performance, and consumer activation remain rollout gates, not completed claims.

## Notification workspace cleanup

Notifications use persistent runners but retain checkout's default cleaning.
Release workflows share that pool and may leave generated configuration and
signing-related workspace state. Do not disable checkout cleaning to preserve
build outputs before running PR-controlled notification scripts. Gradle user-home
and remote task-output caches are separate from the checkout; this policy does
not make jobs sharing the same host UID isolated. Release workflow hardening is
separate from this opt-in cache change.
