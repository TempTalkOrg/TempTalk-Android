#!/usr/bin/env bash
# Provision TempTalk Android for an OpenAI Codex Cloud environment.
#
# Codex Cloud runs this script with network access before the agent phase. The
# script is intentionally idempotent so it can also repair a resumed cache.
set -Eeuo pipefail

MODE="setup"
case "${1:-}" in
    "") MODE="setup" ;;
    --maintenance) MODE="maintenance" ;;
    --verify) MODE="verify" ;;
    *) echo "Usage: $0 [--maintenance|--verify]" >&2; exit 2 ;;
esac

# Capture Cloud secrets as non-exported shell variables, then remove the
# originals before invoking installers or project code. This keeps npm,
# sdkmanager, Gradle, and their children from inheriting credentials.
GITHUB_TOKEN_SECRET="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
GIT_SSH_SIGNING_KEY_SECRET="${GIT_SSH_SIGNING_KEY_B64:-}"
FIREBASE_TOOLS_CONFIG_SECRET="${FIREBASE_TOOLS_CONFIG_B64:-}"
export -n GITHUB_TOKEN_SECRET GIT_SSH_SIGNING_KEY_SECRET FIREBASE_TOOLS_CONFIG_SECRET 2>/dev/null || true
unset GITHUB_TOKEN GH_TOKEN GIT_SSH_SIGNING_KEY_B64 FIREBASE_TOOLS_CONFIG_B64

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/difft-codex"
LOG_FILE="$CACHE_DIR/setup.log"
LOCAL_BIN="$HOME/.local/bin"
CMDLINE_TOOLS_REV="11076708"
GH_VERSION="2.95.0"
CODEX_CLI_VERSION="0.146.0"
DYNAMIC_WORKFLOW_MARKETPLACE="cctools-codex-plugins"
DYNAMIC_WORKFLOW_PLUGIN_ID="dynamic-workflow@${DYNAMIC_WORKFLOW_MARKETPLACE}"
DYNAMIC_WORKFLOW_VERSION="0.2.1"
DYNAMIC_WORKFLOW_SOURCE="pchalasani/claude-code-tools"
DYNAMIC_WORKFLOW_REF="6c8a74da1a33ae4ddf6a43b9997a6b998ed7fa6a"
MARKITDOWN_VERSION="0.1.7"
SDKMANAGER_LICENSE_TIMEOUT="${CODEX_SDKMANAGER_LICENSE_TIMEOUT:-120}"
SDKMANAGER_INSTALL_TIMEOUT="${CODEX_SDKMANAGER_INSTALL_TIMEOUT:-900}"
APT_UPDATE_TIMEOUT="${CODEX_APT_UPDATE_TIMEOUT:-120}"
APT_INSTALL_TIMEOUT="${CODEX_APT_INSTALL_TIMEOUT:-600}"
APT_RECOVERY_TIMEOUT="${CODEX_APT_RECOVERY_TIMEOUT:-600}"
PIPX_INSTALL_TIMEOUT="${CODEX_PIPX_INSTALL_TIMEOUT:-900}"

mkdir -p "$CACHE_DIR" "$LOCAL_BIN"
chmod 700 "$CACHE_DIR"
touch "$LOG_FILE"
chmod 600 "$LOG_FILE"
exec > >(tee -a "$LOG_FILE") 2>&1

log() { printf '[codex-setup] %s\n' "$*"; }
warn() { printf '[codex-setup] WARN: %s\n' "$*" >&2; }
die() { printf '[codex-setup] ERROR: %s\n' "$*" >&2; exit 1; }

require_positive_seconds() {
    case "$2" in
        ''|*[!0-9]*|0) die "$1 must be a positive number of seconds (found '$2')." ;;
    esac
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "Required command '$1' is missing from the Codex base image."
}

terminate_managed_process_group() {
    local process_group_id="$1" attempt=0

    case "$process_group_id" in
        ''|0|*[!0-9]*) return 1 ;;
    esac
    [ "$process_group_id" -ne "$$" ] || return 1

    kill -0 "-$process_group_id" 2>/dev/null || return 0
    kill -TERM "-$process_group_id" 2>/dev/null || true
    while kill -0 "-$process_group_id" 2>/dev/null && [ "$attempt" -lt 50 ]; do
        sleep 0.1
        attempt=$((attempt + 1))
    done
    if kill -0 "-$process_group_id" 2>/dev/null; then
        kill -KILL "-$process_group_id" 2>/dev/null || true
        attempt=0
        while kill -0 "-$process_group_id" 2>/dev/null && [ "$attempt" -lt 50 ]; do
            sleep 0.1
            attempt=$((attempt + 1))
        done
    fi
    ! kill -0 "-$process_group_id" 2>/dev/null
}

execute_bounded_command() {
    local timeout_seconds="$1" timeout_pid
    shift

    BOUNDED_COMMAND_STATUS=0
    # Close interactive input so timeout can manage the command without a
    # package prompt suspending its process group. GNU timeout is that group's
    # leader, so its PID remains the cleanup target even if timeout is killed.
    timeout -k 30 "$timeout_seconds" "$@" </dev/null &
    timeout_pid=$!
    wait "$timeout_pid" || BOUNDED_COMMAND_STATUS=$?

    if [ "$BOUNDED_COMMAND_STATUS" -ne 0 ]; then
        if ! terminate_managed_process_group "$timeout_pid"; then
            die "Could not terminate process group $timeout_pid after a bounded command failure."
        fi
        wait "$timeout_pid" 2>/dev/null || true
    fi
    [ "$BOUNDED_COMMAND_STATUS" -eq 0 ]
}

bounded_failure_message() {
    local description="$1" timeout_seconds="$2" status="$3"

    case "$status" in
        124) printf '%s timed out after %ss.' "$description" "$timeout_seconds" ;;
        137) printf '%s was killed with SIGKILL (exit status 137); the command may have exceeded the timeout grace period or the container may have run out of memory.' "$description" ;;
        *) printf '%s failed with exit status %s.' "$description" "$status" ;;
    esac
}

run_bounded_command() {
    local description="$1" timeout_seconds="$2"
    shift 2

    execute_bounded_command "$timeout_seconds" "$@" && return
    die "$(bounded_failure_message "$description" "$timeout_seconds" "$BOUNDED_COMMAND_STATUS")"
}

read_project_versions() {
    # TempTalk has no buildSrc — compileSdk and the NDK pin both live in
    # gradle/libs.versions.toml (ndkVersion required since AGP 9, PR #896).
    # No externalNativeBuild/cmake in this project, so CMake is not provisioned.
    COMPILE_SDK="$(sed -nE 's/^compileSdk[[:space:]]*=[[:space:]]*"([0-9]+)".*/\1/p' \
        "$PROJECT_DIR/gradle/libs.versions.toml" | head -1)"
    NDK_VERSION="$(sed -nE 's/^ndk[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' \
        "$PROJECT_DIR/gradle/libs.versions.toml" | head -1)"

    [ -n "$COMPILE_SDK" ] || die "Could not derive compileSdk from gradle/libs.versions.toml."
    [ -n "$NDK_VERSION" ] || die "Could not derive the NDK version from gradle/libs.versions.toml."
    BUILD_TOOLS_VERSION="${COMPILE_SDK}.0.0"
}

configure_java() {
    local java_path java_major javac_path
    java_path="$(command -v java 2>/dev/null || true)"
    if [ -n "$java_path" ]; then
        java_major="$("$java_path" -version 2>&1 | awk -F'[".]' '/version/ { print $2; exit }')"
    fi

    # The Cloud runtime installer can leave the universal image's newer JDK
    # first on PATH even after it has provisioned the selected Java 17 package.
    # Prefer the already-installed mise toolchain before rejecting the image.
    if [ "${java_major:-}" != "17" ] && command -v mise >/dev/null 2>&1; then
        java_path="$(mise which java --tool java@17 2>/dev/null || true)"
        if [ -n "$java_path" ] && [ -x "$java_path" ]; then
            java_major="$("$java_path" -version 2>&1 | awk -F'[".]' '/version/ { print $2; exit }')"
        fi
    fi

    [ "${java_major:-}" = "17" ] \
        || die "JDK 17 is required (found ${java_major:-unknown}). Select Java 17 in the Codex environment package versions."

    java_path="$(readlink -f "$java_path")"
    javac_path="$(dirname "$java_path")/javac"
    [ -x "$javac_path" ] || die "JDK 17 was found, but javac is missing beside $java_path."
    JAVA_HOME="$(cd "$(dirname "$javac_path")/.." && pwd)"
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
}

choose_android_sdk() {
    local candidate
    SDK_ROOT=""
    for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk" "/opt/android-sdk"; do
        if [ -n "$candidate" ] && [ -d "$candidate" ]; then
            SDK_ROOT="$candidate"
            break
        fi
    done
    [ -n "$SDK_ROOT" ] || SDK_ROOT="$HOME/android-sdk"

    case "$SDK_ROOT" in
        /|"$HOME"|/usr|/opt) die "Refusing unsafe Android SDK path: $SDK_ROOT" ;;
    esac

    mkdir -p "$SDK_ROOT"
    SDK_ROOT="$(cd "$SDK_ROOT" && pwd)"
    export ANDROID_HOME="$SDK_ROOT"
    export ANDROID_SDK_ROOT="$SDK_ROOT"
    export PATH="$JAVA_HOME/bin:$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$LOCAL_BIN:$PATH"
}

persist_environment() {
    local bashrc="$HOME/.bashrc" tmp
    tmp="$(mktemp)"
    if [ -f "$bashrc" ]; then
        awk '
            $0 == "# >>> difft-codex >>>" { skip=1; next }
            $0 == "# <<< difft-codex <<<" { skip=0; next }
            !skip { print }
        ' "$bashrc" > "$tmp"
    fi
    {
        printf '%s\n' '# >>> difft-codex >>>'
        printf 'export JAVA_HOME=%q\n' "$JAVA_HOME"
        printf 'export ANDROID_HOME=%q\n' "$SDK_ROOT"
        printf 'export ANDROID_SDK_ROOT=%q\n' "$SDK_ROOT"
        printf 'export PATH=$JAVA_HOME/bin:%q:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH\n' "$LOCAL_BIN"
        printf '%s\n' '# <<< difft-codex <<<'
    } >> "$tmp"
    mv "$tmp" "$bashrc"
}

write_local_properties() {
    local properties="$PROJECT_DIR/local.properties" tmp
    tmp="$(mktemp)"
    if [ -f "$properties" ]; then
        grep -v '^sdk\.dir=' "$properties" > "$tmp" || true
    fi
    printf 'sdk.dir=%s\n' "$SDK_ROOT" >> "$tmp"
    mv "$tmp" "$properties"
}

install_gh() {
    if command -v gh >/dev/null 2>&1; then
        return
    fi

    require_command curl
    require_command tar
    local machine archive_arch tmp
    machine="$(uname -m)"
    case "$machine" in
        x86_64) archive_arch="amd64" ;;
        aarch64|arm64) archive_arch="arm64" ;;
        *) die "Unsupported architecture for GitHub CLI: $machine" ;;
    esac

    tmp="$(mktemp -d)"
    log "Installing GitHub CLI $GH_VERSION."
    if ! curl -fsSL --retry 3 --retry-all-errors \
        "https://github.com/cli/cli/releases/download/v${GH_VERSION}/gh_${GH_VERSION}_linux_${archive_arch}.tar.gz" \
        -o "$tmp/gh.tgz"; then
        rm -rf "$tmp"
        die "Could not download GitHub CLI."
    fi
    tar -xzf "$tmp/gh.tgz" -C "$tmp"
    install -m 0755 "$tmp/gh_${GH_VERSION}_linux_${archive_arch}/bin/gh" "$LOCAL_BIN/gh"
    rm -rf "$tmp"
}

install_firebase() {
    if command -v firebase >/dev/null 2>&1 && firebase --version 2>/dev/null | grep -q '^14\.'; then
        return
    fi
    require_command npm
    log "Installing firebase-tools 14.x."
    npm install --global --prefix "$HOME/.local" firebase-tools@14 >/dev/null
    command -v firebase >/dev/null 2>&1 || die "firebase-tools installed but firebase is not on PATH."
}

system_evidence_tools_complete() {
    command -v ffmpeg >/dev/null 2>&1 \
        && command -v ffprobe >/dev/null 2>&1 \
        && command -v pdftotext >/dev/null 2>&1 \
        && command -v pdfinfo >/dev/null 2>&1 \
        && command -v file >/dev/null 2>&1 \
        && command -v pipx >/dev/null 2>&1
}

evidence_tools_complete() {
    system_evidence_tools_complete \
        && command -v markitdown >/dev/null 2>&1
}

package_configuration_pending() {
    local audit_output

    if ! audit_output="$(dpkg --audit 2>&1)"; then
        die "Could not audit package configuration state: $audit_output"
    fi
    [ -n "$audit_output" ]
}

repair_interrupted_package_installation() {
    local status

    log "Repairing any package configuration interrupted by the previous install attempt (timeout ${APT_RECOVERY_TIMEOUT}s)."
    if ! execute_bounded_command "$APT_RECOVERY_TIMEOUT" \
        env DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a APT_LISTCHANGES_FRONTEND=none \
        dpkg --configure -a; then
        status="$BOUNDED_COMMAND_STATUS"
        case "$status" in
            124|137)
                die "$(bounded_failure_message "interrupted package configuration repair" "$APT_RECOVERY_TIMEOUT" "$status")"
                ;;
            *)
                warn "$(bounded_failure_message "interrupted package configuration repair" "$APT_RECOVERY_TIMEOUT" "$status") Attempting dependency repair."
                ;;
        esac
    fi

    run_bounded_command "broken package dependency repair" "$APT_RECOVERY_TIMEOUT" \
        env DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a APT_LISTCHANGES_FRONTEND=none \
        apt-get -o Acquire::Retries=3 -o Acquire::http::Timeout=60 \
            -o Acquire::https::Timeout=60 -o DPkg::Lock::Timeout=60 -o Dpkg::Use-Pty=0 \
            --fix-broken install -y --no-install-recommends
}

install_system_evidence_packages() {
    local status
    local -a packages=()

    if ! command -v ffmpeg >/dev/null 2>&1 || ! command -v ffprobe >/dev/null 2>&1; then
        packages+=(ffmpeg)
    fi
    if ! command -v pdftotext >/dev/null 2>&1 || ! command -v pdfinfo >/dev/null 2>&1; then
        packages+=(poppler-utils)
    fi
    command -v file >/dev/null 2>&1 || packages+=(file)
    command -v pipx >/dev/null 2>&1 || packages+=(pipx)

    [ "${#packages[@]}" -gt 0 ] || return

    log "Installing video and document evidence tools (timeout ${APT_INSTALL_TIMEOUT}s)."
    if execute_bounded_command "$APT_INSTALL_TIMEOUT" \
        env DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a APT_LISTCHANGES_FRONTEND=none \
        apt-get -o Acquire::Retries=3 -o Acquire::http::Timeout=60 \
            -o Acquire::https::Timeout=60 -o DPkg::Lock::Timeout=60 -o Dpkg::Use-Pty=0 \
            install -y --no-install-recommends "${packages[@]}"; then
        return
    fi

    status="$BOUNDED_COMMAND_STATUS"
    warn "$(bounded_failure_message "evidence tool package installation" "$APT_INSTALL_TIMEOUT" "$status") Repairing package state before one retry."
    repair_interrupted_package_installation

    log "Retrying video and document evidence tool installation once (timeout ${APT_INSTALL_TIMEOUT}s)."
    run_bounded_command "evidence tool package installation retry" "$APT_INSTALL_TIMEOUT" \
        env DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a APT_LISTCHANGES_FRONTEND=none \
        apt-get -o Acquire::Retries=3 -o Acquire::http::Timeout=60 \
            -o Acquire::https::Timeout=60 -o DPkg::Lock::Timeout=60 -o Dpkg::Use-Pty=0 \
            install -y --no-install-recommends "${packages[@]}"
}

install_evidence_tools() {
    require_command timeout
    require_command dpkg
    require_positive_seconds CODEX_APT_RECOVERY_TIMEOUT "$APT_RECOVERY_TIMEOUT"
    if package_configuration_pending; then
        command -v apt-get >/dev/null 2>&1 \
            || die "apt-get is required to repair interrupted package configuration."
        repair_interrupted_package_installation
    fi

    evidence_tools_complete && return

    if ! system_evidence_tools_complete; then
        require_positive_seconds CODEX_APT_UPDATE_TIMEOUT "$APT_UPDATE_TIMEOUT"
        require_positive_seconds CODEX_APT_INSTALL_TIMEOUT "$APT_INSTALL_TIMEOUT"
        command -v apt-get >/dev/null 2>&1 \
            || die "apt-get is required to provision video and document evidence tools."

        log "Refreshing apt package indexes (timeout ${APT_UPDATE_TIMEOUT}s)."
        run_bounded_command "apt package index refresh" "$APT_UPDATE_TIMEOUT" \
            env DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a APT_LISTCHANGES_FRONTEND=none \
            apt-get -o Acquire::Retries=3 -o Acquire::http::Timeout=60 \
                -o Acquire::https::Timeout=60 -o DPkg::Lock::Timeout=60 -o Dpkg::Use-Pty=0 update

        install_system_evidence_packages
        system_evidence_tools_complete \
            || die "Video or document system packages are still unavailable after installation."
    fi

    export PATH="$LOCAL_BIN:$PATH"
    if ! command -v markitdown >/dev/null 2>&1; then
        require_positive_seconds CODEX_PIPX_INSTALL_TIMEOUT "$PIPX_INSTALL_TIMEOUT"
        log "Installing MarkItDown $MARKITDOWN_VERSION document conversion support (timeout ${PIPX_INSTALL_TIMEOUT}s)."
        # --force also repairs a partially created pipx environment whose
        # metadata exists but whose console script was never exposed.
        run_bounded_command "MarkItDown installation" "$PIPX_INSTALL_TIMEOUT" \
            pipx install --force "markitdown[all]==${MARKITDOWN_VERSION}"
    fi

    evidence_tools_complete \
        || die "Video or document evidence tools are still unavailable after installation."
}

install_codex() {
    if command -v codex >/dev/null 2>&1 && codex plugin --help >/dev/null 2>&1; then
        return
    fi

    if ! command -v npm >/dev/null 2>&1; then
        warn "npm is unavailable; optional Codex CLI provisioning was skipped."
        return 1
    fi
    log "Installing Codex CLI $CODEX_CLI_VERSION for plugin provisioning."
    if ! npm install --global --prefix "$HOME/.local" "@openai/codex@${CODEX_CLI_VERSION}" >/dev/null; then
        warn "Could not install optional Codex CLI $CODEX_CLI_VERSION."
        return 1
    fi
    if ! command -v codex >/dev/null 2>&1; then
        warn "Codex CLI installed but codex is not on PATH."
        return 1
    fi
    if ! codex plugin --help >/dev/null 2>&1; then
        warn "Codex CLI $CODEX_CLI_VERSION does not provide plugin management."
        return 1
    fi
}

dynamic_workflow_record() {
    if [ "${1:-}" = "--available" ]; then
        set -- --available
    else
        set --
    fi
    codex plugin list \
        --marketplace "$DYNAMIC_WORKFLOW_MARKETPLACE" \
        "$@" \
        --json 2>/dev/null \
        | node -e '
            let input = "";
            process.stdin.on("data", chunk => { input += chunk; });
            process.stdin.on("end", () => {
                const data = JSON.parse(input);
                const plugins = [...(data.installed || []), ...(data.available || [])];
                const plugin = plugins.find(item => item.pluginId === process.argv[1]);
                if (!plugin) process.exit(3);
                const sourcePath = plugin.source && plugin.source.path ? plugin.source.path : "";
                process.stdout.write([
                    plugin.version || "",
                    plugin.installed ? "true" : "false",
                    plugin.enabled ? "true" : "false",
                    sourcePath,
                ].join("\t"));
            });
        ' "$DYNAMIC_WORKFLOW_PLUGIN_ID"
}

verify_dynamic_workflow() {
    local record version installed enabled plugin_path runner
    if ! command -v codex >/dev/null 2>&1; then
        warn "Codex CLI is unavailable."
        return 1
    fi
    if ! command -v node >/dev/null 2>&1; then
        warn "Node.js is unavailable."
        return 1
    fi
    if ! record="$(dynamic_workflow_record)"; then
        warn "$DYNAMIC_WORKFLOW_PLUGIN_ID is unavailable."
        return 1
    fi
    IFS=$'\t' read -r version installed enabled plugin_path <<< "$record"
    if [ "$version" != "$DYNAMIC_WORKFLOW_VERSION" ] \
        || [ "$installed" != "true" ] \
        || [ "$enabled" != "true" ]; then
        warn "$DYNAMIC_WORKFLOW_PLUGIN_ID $DYNAMIC_WORKFLOW_VERSION is not installed and enabled."
        return 1
    fi

    runner="$plugin_path/bin/workflow.mjs"
    if [ ! -f "$runner" ] || ! node "$runner" help >/dev/null 2>&1; then
        warn "The dynamic-workflow runner failed verification."
        return 1
    fi
    return 0
}

install_dynamic_workflow() {
    local node_major marketplaces record version installed enabled plugin_path
    node_major="$(node --version | sed -nE 's/^v([0-9]+).*/\1/p')"
    if [ -z "$node_major" ] || [ "$node_major" -lt 20 ]; then
        warn "dynamic-workflow requires Node.js 20 or newer."
        return 1
    fi

    if ! marketplaces="$(codex plugin marketplace list --json 2>/dev/null)"; then
        warn "Could not list Codex plugin marketplaces."
        return 1
    fi
    if ! printf '%s' "$marketplaces" | grep -q "\"name\": \"${DYNAMIC_WORKFLOW_MARKETPLACE}\""; then
        log "Adding the pinned $DYNAMIC_WORKFLOW_MARKETPLACE marketplace."
        if ! codex plugin marketplace add "$DYNAMIC_WORKFLOW_SOURCE" \
                --ref "$DYNAMIC_WORKFLOW_REF" \
                --json >/dev/null; then
            warn "Could not add the pinned $DYNAMIC_WORKFLOW_MARKETPLACE marketplace."
            return 1
        fi
    fi

    if ! record="$(dynamic_workflow_record --available)"; then
        warn "$DYNAMIC_WORKFLOW_PLUGIN_ID is missing from the pinned marketplace."
        return 1
    fi
    IFS=$'\t' read -r version installed enabled plugin_path <<< "$record"
    if [ "$version" != "$DYNAMIC_WORKFLOW_VERSION" ]; then
        warn "Expected $DYNAMIC_WORKFLOW_PLUGIN_ID $DYNAMIC_WORKFLOW_VERSION, found ${version:-unknown}."
        return 1
    fi

    if [ "$installed" != "true" ]; then
        log "Installing $DYNAMIC_WORKFLOW_PLUGIN_ID $DYNAMIC_WORKFLOW_VERSION."
        if ! codex plugin add "$DYNAMIC_WORKFLOW_PLUGIN_ID" --json >/dev/null; then
            warn "Could not install $DYNAMIC_WORKFLOW_PLUGIN_ID."
            return 1
        fi
    elif [ "$enabled" != "true" ]; then
        warn "$DYNAMIC_WORKFLOW_PLUGIN_ID is installed but disabled."
        return 1
    fi

    log "Configured $DYNAMIC_WORKFLOW_PLUGIN_ID $DYNAMIC_WORKFLOW_VERSION."
}

provision_dynamic_workflow() {
    install_codex && install_dynamic_workflow
}

repair_dynamic_workflow() {
    install_codex || return 1
    install_dynamic_workflow || return 1
    if verify_dynamic_workflow; then
        return
    fi

    log "Reinstalling $DYNAMIC_WORKFLOW_PLUGIN_ID after failed maintenance verification."
    codex plugin remove "$DYNAMIC_WORKFLOW_PLUGIN_ID" --json >/dev/null 2>&1 || true
    install_dynamic_workflow && verify_dynamic_workflow
}

android_sdk_complete() {
    [ -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ] \
        && [ -d "$SDK_ROOT/platform-tools" ] \
        && [ -d "$SDK_ROOT/platforms/android-${COMPILE_SDK}" ] \
        && [ -d "$SDK_ROOT/build-tools/${BUILD_TOOLS_VERSION}" ] \
        && [ -d "$SDK_ROOT/ndk/${NDK_VERSION}" ]
}

install_android_sdk() {
    local sdkmanager="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" tmp
    require_command timeout
    require_positive_seconds CODEX_SDKMANAGER_LICENSE_TIMEOUT "$SDKMANAGER_LICENSE_TIMEOUT"
    require_positive_seconds CODEX_SDKMANAGER_INSTALL_TIMEOUT "$SDKMANAGER_INSTALL_TIMEOUT"

    if [ ! -x "$sdkmanager" ]; then
        require_command curl
        require_command unzip
        tmp="$(mktemp -d)"
        log "Installing Android command-line tools."
        if ! curl -fsSL --retry 3 --retry-all-errors --max-time 300 \
            "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_REV}_latest.zip" \
            -o "$tmp/tools.zip"; then
            rm -rf "$tmp"
            die "Could not download Android command-line tools."
        fi
        unzip -q "$tmp/tools.zip" -d "$tmp/unpacked"
        mkdir -p "$SDK_ROOT/cmdline-tools"
        rm -rf "$SDK_ROOT/cmdline-tools/latest"
        mv "$tmp/unpacked/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
        rm -rf "$tmp"
    fi

    sdkmanager="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
    if ! timeout -k 10 "$SDKMANAGER_LICENSE_TIMEOUT" \
        "$sdkmanager" --sdk_root="$SDK_ROOT" --licenses \
        < <(yes 2>/dev/null) >/dev/null 2>&1; then
        warn "Android SDK license acceptance failed or timed out after ${SDKMANAGER_LICENSE_TIMEOUT}s; package installation will still be attempted."
    fi

    log "Ensuring Android SDK $COMPILE_SDK, build-tools $BUILD_TOOLS_VERSION, and NDK $NDK_VERSION (timeout ${SDKMANAGER_INSTALL_TIMEOUT}s)."
    if ! timeout -k 30 "$SDKMANAGER_INSTALL_TIMEOUT" \
        "$sdkmanager" --sdk_root="$SDK_ROOT" \
            "platform-tools" \
            "platforms;android-${COMPILE_SDK}" \
            "build-tools;${BUILD_TOOLS_VERSION}" \
            "ndk;${NDK_VERSION}" >/dev/null; then
        die "Android SDK package installation failed or timed out after ${SDKMANAGER_INSTALL_TIMEOUT}s."
    fi
}

configure_github() {
    local token name login account_id email
    if [ -n "$GITHUB_TOKEN_SECRET" ]; then
        token="$GITHUB_TOKEN_SECRET"
        log "Configuring GitHub CLI authentication from GITHUB_TOKEN."
        if ! printf '%s' "$token" | env -u GITHUB_TOKEN -u GH_TOKEN \
            gh auth login --hostname github.com --git-protocol https --with-token >/dev/null 2>&1; then
            warn "GITHUB_TOKEN was rejected; GitHub CLI remains unauthenticated."
            token=""
            return
        fi
        chmod 600 "$HOME/.config/gh/hosts.yml" 2>/dev/null || true
        env -u GITHUB_TOKEN -u GH_TOKEN gh auth setup-git >/dev/null 2>&1 || warn "Could not configure gh as Git's credential helper."
        token=""
    fi

    if ! env -u GITHUB_TOKEN -u GH_TOKEN gh auth status >/dev/null 2>&1; then
        warn "GitHub CLI is not authenticated."
        return
    fi

    name="$(env -u GITHUB_TOKEN -u GH_TOKEN gh api user --jq '.name // empty' 2>/dev/null || true)"
    login="$(env -u GITHUB_TOKEN -u GH_TOKEN gh api user --jq '.login // empty' 2>/dev/null || true)"
    account_id="$(env -u GITHUB_TOKEN -u GH_TOKEN gh api user --jq '.id // empty' 2>/dev/null || true)"
    email="$(env -u GITHUB_TOKEN -u GH_TOKEN gh api user --jq '.email // empty' 2>/dev/null || true)"
    [ -n "$name" ] || name="$login"
    if [ -z "$email" ] && [ -n "$login" ] && [ -n "$account_id" ]; then
        email="${account_id}+${login}@users.noreply.github.com"
    fi
    if [ -n "$name" ] && [ -n "$email" ]; then
        git config --global user.name "$name"
        git config --global user.email "$email"
        log "Configured Git identity for $login."
    else
        warn "GitHub identity could not be resolved from the authenticated account."
    fi
}

configure_signing() {
    [ -n "$GIT_SSH_SIGNING_KEY_SECRET" ] || return 0
    if ! command -v ssh-keygen >/dev/null 2>&1; then
        warn "ssh-keygen is unavailable; SSH commit signing was not configured."
        return
    fi

    local key_file="$HOME/.ssh/difft_git_signing_key"
    mkdir -p "$HOME/.ssh"
    chmod 700 "$HOME/.ssh"
    if (umask 077 && printf '%s' "$GIT_SSH_SIGNING_KEY_SECRET" | base64 -d > "$key_file" 2>/dev/null) \
        && [ -s "$key_file" ] \
        && grep -q 'PRIVATE KEY' "$key_file" \
        && ! grep -q 'ENCRYPTED PRIVATE KEY' "$key_file" \
        && ssh-keygen -y -P "" -f "$key_file" >/dev/null 2>&1; then
        chmod 600 "$key_file"
        git config --global gpg.format ssh
        git config --global gpg.ssh.program "$(command -v ssh-keygen)"
        git config --global user.signingkey "$key_file"
        git config --global commit.gpgsign true
        log "Configured SSH commit signing."
    else
        rm -f "$key_file"
        warn "GIT_SSH_SIGNING_KEY_B64 is invalid or passphrase-protected; commit signing remains disabled."
    fi
}

configure_firebase_credentials() {
    [ -n "$FIREBASE_TOOLS_CONFIG_SECRET" ] || return 0
    local config_dir="$HOME/.config/configstore"
    local config_file="$config_dir/firebase-tools.json"
    mkdir -p "$config_dir"
    chmod 700 "$config_dir"
    if (umask 077 && printf '%s' "$FIREBASE_TOOLS_CONFIG_SECRET" | base64 -d > "$config_file" 2>/dev/null) \
        && [ -s "$config_file" ] \
        && grep -q '"refresh_token"' "$config_file"; then
        chmod 600 "$config_file"
        log "Configured Firebase CLI credentials."
    else
        rm -f "$config_file"
        warn "FIREBASE_TOOLS_CONFIG_B64 is invalid or does not contain a refresh_token."
    fi
}

verify_installation() {
    local missing=0
    [ -x "$JAVA_HOME/bin/java" ] || { warn "JDK 17 is missing at $JAVA_HOME."; missing=1; }
    [ -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ] || { warn "sdkmanager is missing."; missing=1; }
    [ -d "$SDK_ROOT/platform-tools" ] || { warn "Android platform-tools are missing."; missing=1; }
    [ -d "$SDK_ROOT/platforms/android-${COMPILE_SDK}" ] || { warn "Android platform $COMPILE_SDK is missing."; missing=1; }
    [ -d "$SDK_ROOT/build-tools/${BUILD_TOOLS_VERSION}" ] || { warn "Android build-tools $BUILD_TOOLS_VERSION are missing."; missing=1; }
    [ -d "$SDK_ROOT/ndk/${NDK_VERSION}" ] || { warn "Android NDK $NDK_VERSION is missing."; missing=1; }
    command -v gh >/dev/null 2>&1 || { warn "GitHub CLI is missing."; missing=1; }
    command -v firebase >/dev/null 2>&1 || { warn "Firebase CLI is missing."; missing=1; }
    command -v ffmpeg >/dev/null 2>&1 || { warn "ffmpeg is missing."; missing=1; }
    command -v ffprobe >/dev/null 2>&1 || { warn "ffprobe is missing."; missing=1; }
    command -v pdftotext >/dev/null 2>&1 || { warn "pdftotext is missing."; missing=1; }
    command -v pdfinfo >/dev/null 2>&1 || { warn "pdfinfo is missing."; missing=1; }
    command -v file >/dev/null 2>&1 || { warn "file is missing."; missing=1; }
    command -v markitdown >/dev/null 2>&1 || { warn "markitdown is missing."; missing=1; }
    [ -f "$PROJECT_DIR/local.properties" ] || { warn "local.properties is missing."; missing=1; }
    [ "$missing" -eq 0 ] || return 1
    log "Core Android toolchain verification passed."
}

main() {
    [ "$(uname -s)" = "Linux" ] || die "This setup script targets the Linux Codex Cloud image."
    require_command git
    require_command base64
    require_command node
    read_project_versions
    configure_java
    choose_android_sdk
    persist_environment
    write_local_properties

    if [ "$MODE" = "verify" ]; then
        verify_installation
        if verify_dynamic_workflow; then
            log "Optional $DYNAMIC_WORKFLOW_PLUGIN_ID verification passed."
        else
            warn "Optional $DYNAMIC_WORKFLOW_PLUGIN_ID verification failed."
        fi
        return
    fi

    install_gh
    install_firebase
    install_evidence_tools
    if android_sdk_complete; then
        log "Required Android SDK components are already installed; SDK package installation skipped."
    else
        install_android_sdk
    fi
    if [ "$MODE" = "maintenance" ]; then
        if repair_dynamic_workflow; then
            log "Maintenance verified $DYNAMIC_WORKFLOW_PLUGIN_ID $DYNAMIC_WORKFLOW_VERSION."
        else
            warn "Maintenance could not repair optional $DYNAMIC_WORKFLOW_PLUGIN_ID."
        fi
    elif provision_dynamic_workflow; then
        log "Optional $DYNAMIC_WORKFLOW_PLUGIN_ID provisioning completed."
    else
        warn "Optional $DYNAMIC_WORKFLOW_PLUGIN_ID provisioning failed; Android setup will continue."
    fi
    configure_github
    configure_signing
    configure_firebase_credentials
    unset GITHUB_TOKEN_SECRET GIT_SSH_SIGNING_KEY_SECRET FIREBASE_TOOLS_CONFIG_SECRET
    verify_installation

    log "Setup complete. Log: $LOG_FILE"
}

main
