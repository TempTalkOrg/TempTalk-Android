# Codex Cloud environment

This repository includes a Codex-native Android toolchain setup, cached-environment maintenance, project guidance, best-effort provisioning of the pinned `dynamic-workflow` plugin, and an optional Firebase MCP server.

## Environment settings

Create or update the Codex Cloud environment for `difftim/TempTalk-Android` with:

- Package versions: Java 17 and Node.js 20 or newer.
- Setup script: `bash .codex/setup.sh`
- Maintenance script: `bash .codex/maintenance.sh`
- Optional provisioning timeout overrides: regular environment variables `CODEX_SDKMANAGER_LICENSE_TIMEOUT=120`, `CODEX_SDKMANAGER_INSTALL_TIMEOUT=900`, `CODEX_APT_UPDATE_TIMEOUT=120`, `CODEX_APT_INSTALL_TIMEOUT=600`, `CODEX_APT_RECOVERY_TIMEOUT=600`, and `CODEX_PIPX_INSTALL_TIMEOUT=900` (seconds).

The setup script installs Android SDK 36, build-tools 36.0.0, the NDK version declared by the project (no CMake — TempTalk has no externalNativeBuild), GitHub CLI, Firebase CLI, and the evidence tools used during bug-context materialization. Those evidence tools include FFmpeg for video frames, Poppler for PDFs, file-type detection, and MarkItDown for PDF and Office-document conversion. It selects the provisioned Java 17 toolchain directly when the universal image leaves a newer JDK first on `PATH`. Android SDK, apt, pipx, and license operations have explicit time limits so a network failure cannot block environment startup indefinitely. Apt runs with stdin closed, fully noninteractive package frontends, 60-second download timeouts, bounded retries, and a managed process group that is terminated and reaped before recovery. Before treating evidence tools as complete, setup audits and repairs pending `dpkg` configuration and broken dependencies. A failed evidence package transaction is then retried once. Package-manager output remains in the provisioning log, and timeout, ordinary failure, and possible SIGKILL/OOM termination are reported separately.

Setup and maintenance do not execute Gradle. Dependency resolution, builds, and tests run in the agent phase, where commands can be scoped to the task and failures remain visible. Maintenance skips Android SDK network operations when all required components are already present.

Setup also attempts to install Codex CLI 0.146.0 and `dynamic-workflow` 0.2.1. Those capabilities are optional: installation failures produce warnings but never fail core Android provisioning. On a cached-container resume, maintenance retries provisioning and verifies the installed plugin metadata and bundled runner before the agent starts.

`dynamic-workflow` is installed from `pchalasani/claude-code-tools` at commit `6c8a74da1a33ae4ddf6a43b9997a6b998ed7fa6a`. Selecting this repository's setup command is explicit approval to install that pinned third-party plugin in the Cloud environment. Provisioning does not silently upgrade it. Start a new Cloud task after installing, repairing, or changing the plugin pin so Codex reloads the skill catalog.

After changing these settings or the secrets, reset the environment cache so Codex rebuilds it.

## Secrets

The setup recognizes these optional Codex Cloud secrets:

| Secret | Purpose |
| --- | --- |
| `GITHUB_TOKEN` | Authenticates `gh`, configures Git's GitHub credential helper, and resolves the developer commit identity. |
| `GIT_SSH_SIGNING_KEY_B64` | Installs an unencrypted, signing-only SSH private key and enables signed commits. |
| `FIREBASE_TOOLS_CONFIG_B64` | Installs the Firebase CLI user OAuth config used by `firebase mcp`. |

Codex removes secret environment values before the agent phase. To provide the requested CLI capabilities, the setup script deliberately materializes them into permission-restricted files in the cached home directory. That means code running in the agent phase can potentially access those credentials. Use a dedicated least-privilege GitHub token, a revocable signing-only SSH key, and a personal/private cloud environment. Do not use this credential-persisting setup in an environment whose cache is shared with untrusted users.

Never paste secret values into the setup or maintenance script fields. The scripts read them directly from the environment and never print them.

For the signing secret, register the corresponding public key in GitHub as a **Signing Key**. The private key must be unencrypted because cloud commits are non-interactive.

## Internet access

Setup and maintenance need internet access to install the SDK and resolve Gradle dependencies. If the agent must use `gh`, Firebase MCP, or add dependencies, enable agent internet access with the narrowest practical domain allowlist.

Start with the **Common dependencies** preset and add the project-specific hosts that are not covered:

- `api.github.com`
- `github.com`
- `dl.google.com`
- `developer.huawei.com`
- `jitpack.io`
- `raw.github.com`
- `raw.githubusercontent.com`
- `googleapis.com`
- `firebase.google.com`

Read-only Android work can restrict methods to `GET`, `HEAD`, and `OPTIONS`. GitHub or Firebase mutations additionally require the relevant write methods; enable them only for tasks that need those actions.

## Verification

In the first cloud chat, run:

```bash
bash .codex/setup.sh --verify
java -version
./gradlew help --no-daemon
./gradlew testAll -Proborazzi.test.verify=true --parallel --build-cache
./gradlew :app:assembleTTDevOfficialDebug --parallel
gh auth status
firebase --version
ffmpeg -version
pdftotext -v
markitdown --help

# Optional capability checks:
codex --version
codex plugin list --marketplace cctools-codex-plugins
```

The debug APK is written under `app/build/outputs/apk/`. `local.properties`, Gradle outputs, the setup log, and credential files must not appear in the Git diff.

## Troubleshooting

- Full provisioning log: `~/.cache/difft-codex/setup.log`.
- Re-run provisioning: `bash .codex/setup.sh`.
- Repair a cached environment: `bash .codex/maintenance.sh`.
- Verify without downloads: `bash .codex/setup.sh --verify`.
- If evidence package installation is interrupted, rerun setup or maintenance; it repairs pending `dpkg` configuration and retries once automatically. Increase `CODEX_APT_INSTALL_TIMEOUT` only when the log reports a genuine timeout rather than exit status 137.
- If the runtime configuration reports Java 17 but `java -version` initially reports a newer JDK, re-run provisioning. The setup script resolves the installed Java 17 toolchain through `mise` and persists its `JAVA_HOME` and `PATH`.
- If Firebase MCP does not appear, confirm the repository is trusted so Codex loads `.codex/config.toml`, then start a new cloud chat after the cache rebuild.
- If `dynamic-workflow` is missing or has the wrong version, let maintenance retry on the next cached task or reset the environment cache. For a manual repair, run `codex plugin marketplace add pchalasani/claude-code-tools --ref 6c8a74da1a33ae4ddf6a43b9997a6b998ed7fa6a`, then `codex plugin add dynamic-workflow@cctools-codex-plugins`, and start a new task.

OpenAI's environment behavior, secret lifetime, cache rules, and internet controls are documented in [Codex Cloud environments](https://developers.openai.com/codex/cloud/environments) and [agent internet access](https://developers.openai.com/codex/cloud/internet-access).
