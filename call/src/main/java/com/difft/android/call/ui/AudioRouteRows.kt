package com.difft.android.call.ui

import com.difft.android.call.R
import com.difft.android.call.manager.AudioDeviceKind
import com.difft.android.call.manager.AudioRouteSnapshot
import com.difft.android.call.manager.AudioRouteState
import com.difft.android.call.manager.kind
import com.difft.android.call.manager.settledRoute
import com.difft.android.call.manager.userPendingRoute
import com.twilio.audioswitch.AudioDevice

/**
 * What a picker row asserts about its device. Mutually exclusive by construction.
 *
 * [ACTIVE] is the ONLY status that renders the check mark, and it is reachable only from
 * [AudioRouteSnapshot.confirmed] — i.e. from an observed routing fact. "Request delivered" and
 * "the library says it selected this" never reach it.
 */
enum class AudioRouteRowStatus { NONE, ACTIVE, CONNECTING, FAILED }

/** One picker row: the device, its route identity, and the latest observation about it. */
data class AudioDeviceRow(
    val device: AudioDevice,
    val kind: AudioDeviceKind,
    val status: AudioRouteRowStatus,
)

/**
 * Maps one atomic snapshot to the picker rows. Pure: no Compose, no Android, no I/O.
 *
 * Precedence per row is CONNECTING > FAILED > ACTIVE > NONE. CONNECTING outranks ACTIVE because
 * an in-flight attempt has already invalidated the previous observation: the library tears the
 * old route down inside `onActivate(device)`, so during the attempt the real output is unknown.
 * Re-tapping the already-checked Bluetooth row to force a re-activation is exactly the same kind
 * being both `confirmed` and `Applying`, and the user doing it is the user who distrusts that
 * check mark — showing "✓" and "Connecting…" at once would be self-contradictory.
 */
fun AudioRouteSnapshot.toDeviceRows(): List<AudioDeviceRow> {
    val applyingKind = (state as? AudioRouteState.Applying)?.device?.kind
    val failedKind = (state as? AudioRouteState.Failed)?.device?.kind
    val confirmedKind = confirmed?.kind
    return availableDevices.map { device ->
        val kind = device.kind
        AudioDeviceRow(
            device = device,
            kind = kind,
            status = when (kind) {
                applyingKind -> AudioRouteRowStatus.CONNECTING
                failedKind -> AudioRouteRowStatus.FAILED
                confirmedKind -> AudioRouteRowStatus.ACTIVE
                else -> AudioRouteRowStatus.NONE
            },
        )
    }
}

/** Status label resource, or `null` when the row asserts nothing. */
fun AudioRouteRowStatus.labelRes(): Int? = when (this) {
    AudioRouteRowStatus.NONE -> null
    AudioRouteRowStatus.ACTIVE -> R.string.call_audio_device_status_active
    AudioRouteRowStatus.CONNECTING -> R.string.call_audio_device_status_connecting
    AudioRouteRowStatus.FAILED -> R.string.call_audio_device_status_failed
}

/** Row icon. Exhaustive `when` — a new device class fails compilation, not silently draws a speaker. */
fun AudioDeviceKind.pickerIconRes(): Int = when (this) {
    AudioDeviceKind.EARPIECE -> R.drawable.tabler_device_phone
    AudioDeviceKind.SPEAKERPHONE -> R.drawable.tabler_device_speaker
    AudioDeviceKind.WIRED_HEADSET -> R.drawable.tabler_device_headphones
    AudioDeviceKind.BLUETOOTH_HEADSET -> R.drawable.tabler_device_airpods
}

/** Horn-button icon. */
fun AudioDeviceKind.hornIconRes(): Int = when (this) {
    // Earpiece and loudspeaker share the horn glyph; the action bar tells them apart by the
    // selected (white) background, not by the icon.
    AudioDeviceKind.EARPIECE -> R.drawable.call_ic_volume_speaker
    AudioDeviceKind.SPEAKERPHONE -> R.drawable.call_ic_volume_speaker
    AudioDeviceKind.WIRED_HEADSET -> R.drawable.call_ic_volume_headphones
    AudioDeviceKind.BLUETOOTH_HEADSET -> R.drawable.call_ic_volume_airpod
}

/**
 * Which route the horn button should depict: where audio currently IS, to the best knowledge.
 *
 * [settledRoute] has no `Applying` tier, and must not gain one here: depicting an unconfirmed target
 * as the active route is the assertion PR #1120 removed. "Where audio is going" is
 * [hornPresentation]'s question. The terminal default mirrors the per-callType `preferredDeviceList`
 * [com.difft.android.call.manager.AudioDeviceManager] is built with.
 */
fun AudioRouteSnapshot.hornIconKind(isOneVOneCall: Boolean): AudioDeviceKind =
    settledRoute?.kind
        ?: if (isOneVOneCall) AudioDeviceKind.EARPIECE else AudioDeviceKind.SPEAKERPHONE

/**
 * What the horn button draws: which route, and whether that route is still only being driven.
 *
 * [pending] acknowledges the user's tap; it never asserts the audio path. The picker panel stays the
 * only surface that asserts per-device status.
 */
data class HornPresentation(val kind: AudioDeviceKind, val pending: Boolean)

/**
 * Toggle-path presentation. On the panel path this is exactly [hornIconKind].
 *
 * [isToggle] is passed explicitly rather than inherited from [shouldShowAudioDevicePanel]: PR #1120's
 * ruling that a Bluetooth activation earns no horn-level optimistic visual must not survive only as a
 * side effect of that formula. The gate alone is NOT sufficient — [userPendingRoute] is itself
 * narrowed to a toggle-eligible target, since a two-device `[ear, spk]` list opens this gate
 * legitimately while the in-flight target is Bluetooth.
 *
 * [HornPresentation.pending] needs no timer and no remembered state: it is a pure read of an
 * `Applying` the applier's retry budget and `collectLatest` cancellation already bound.
 */
fun AudioRouteSnapshot.hornPresentation(
    isOneVOneCall: Boolean,
    isToggle: Boolean,
): HornPresentation {
    val pending = if (isToggle) userPendingRoute else null
    return HornPresentation(
        kind = pending?.kind ?: hornIconKind(isOneVOneCall),
        pending = pending != null,
    )
}

/**
 * Toggle-on-tap is only a good affordance for exactly two deterministic, instantly switchable
 * outputs (earpiece / speaker / wired). Anything else needs the panel, which is the only surface
 * that can show CONNECTING / FAILED:
 *  - a Bluetooth route present at all — its activation is the failure-prone one;
 *  - more than two devices — a blind toggle cannot express a 3-way choice;
 *  - exactly one device — nothing to toggle to; the panel at least shows its status.
 */
fun shouldShowAudioDevicePanel(rows: List<AudioDeviceRow>): Boolean =
    rows.any { it.kind == AudioDeviceKind.BLUETOOTH_HEADSET } || rows.size != 2
