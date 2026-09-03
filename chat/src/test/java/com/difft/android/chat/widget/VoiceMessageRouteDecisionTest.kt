package com.difft.android.chat.widget

import android.media.AudioDeviceInfo
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T4-1 … T4-7 — the `preferredOutputType` truth table. This pure decision function IS the
 * routing contract for voice-message playback: the earpiece is only forced when the device has
 * no external output attached.
 *
 * Runs under Robolectric although the function is pure, because the built-in whitelist gates
 * `TYPE_BUILTIN_SPEAKER_SAFE` on `Build.VERSION.SDK_INT`, which is not a compile-time constant — a
 * bare JVM run would read 0 and exercise a whitelist no real device has.
 *
 * Verify: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class VoiceMessageRouteDecisionTest {

    @Test
    fun `T4-1 near with builtin outputs only targets the earpiece`() {
        val target = AudioMessageManager.preferredOutputType(
            isNear = true,
            availableTypes = setOf(
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            ),
        )
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, target)
    }

    @Test
    fun `T4-2 near with a wired headset attached leaves the route alone`() {
        val target = AudioMessageManager.preferredOutputType(
            isNear = true,
            availableTypes = setOf(
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
            ),
        )
        assertNull(target)
    }

    @Test
    fun `T4-3 near with A2DP attached leaves the route alone`() {
        val target = AudioMessageManager.preferredOutputType(
            isNear = true,
            availableTypes = setOf(
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            ),
        )
        assertNull(target)
    }

    @Test
    fun `T4-4 near with SCO active never disturbs the call device`() {
        val target = AudioMessageManager.preferredOutputType(
            isNear = true,
            availableTypes = setOf(
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            ),
        )
        assertNull(target)
    }

    @Test
    fun `T4-5 near on a device without an earpiece leaves the route alone`() {
        val target = AudioMessageManager.preferredOutputType(
            isNear = true,
            availableTypes = setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
        )
        assertNull(target)
    }

    @Test
    fun `T4-6 far always falls back to the platform default route`() {
        assertNull(
            AudioMessageManager.preferredOutputType(
                isNear = false,
                availableTypes = setOf(
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                ),
            ),
        )
        assertNull(
            AudioMessageManager.preferredOutputType(
                isNear = false,
                availableTypes = setOf(
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                ),
            ),
        )
    }

    @Test
    fun `T4-7 an output type outside the whitelist falls on the safe side`() {
        val target = AudioMessageManager.preferredOutputType(
            isNear = true,
            availableTypes = setOf(
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_HDMI,
            ),
        )
        assertNull(target)
    }

    @Test
    fun `T4-7b telephony and speaker-safe stay inside the whitelist`() {
        val target = AudioMessageManager.preferredOutputType(
            isNear = true,
            availableTypes = setOf(
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
                AudioDeviceInfo.TYPE_TELEPHONY,
            ),
        )
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, target)
    }
}
