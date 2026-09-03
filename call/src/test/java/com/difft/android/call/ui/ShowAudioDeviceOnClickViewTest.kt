package com.difft.android.call.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.btDevice
import com.difft.android.call.earDevice
import com.difft.android.call.manager.AudioDeviceKind
import com.difft.android.call.spkDevice
import com.twilio.audioswitch.AudioDevice
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Rendering behaviour of the audio-device picker (design inventory rows #77–#81).
 *
 * Behaviour assertions (node text / tag / existence), not pixel baselines: this project adds no
 * screenshot baselines, so the status slot — the user-visible half of this fix — is pinned here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ShowAudioDeviceOnClickViewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun rowOf(
        device: AudioDevice,
        kind: AudioDeviceKind,
        status: AudioRouteRowStatus,
    ) = AudioDeviceRow(device = device, kind = kind, status = status)

    private fun setPicker(
        rows: List<AudioDeviceRow>,
        expanded: Boolean = true,
        onClickItem: (AudioDevice) -> Unit = {},
    ) {
        Locale.setDefault(Locale.US)
        composeTestRule.setContent {
            DifftTheme(darkTheme = true) {
                ShowAudioDeviceOnClickView(
                    rows = rows,
                    expanded = expanded,
                    setExpanded = {},
                    onClickItem = onClickItem,
                )
            }
        }
    }

    private fun statusText(index: Int) =
        composeTestRule.onNodeWithTag("call_audio_picker_item_status_$index", useUnmergedTree = true)

    // ── #77 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `each row renders its own status label`() {
        setPicker(
            listOf(
                rowOf(btDevice(), AudioDeviceKind.BLUETOOTH_HEADSET, AudioRouteRowStatus.CONNECTING),
                rowOf(spkDevice(), AudioDeviceKind.SPEAKERPHONE, AudioRouteRowStatus.ACTIVE),
                rowOf(earDevice(), AudioDeviceKind.EARPIECE, AudioRouteRowStatus.FAILED),
            ),
        )

        statusText(0).assertTextEquals("Connecting…")
        statusText(1).assertTextEquals("✓")
        statusText(2).assertTextEquals("Failed")
    }

    // ── #78 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `a long bluetooth name cannot swallow the status`() {
        setPicker(
            listOf(
                rowOf(
                    btDevice("Nate's AirPods Pro 2nd generation headset"),
                    AudioDeviceKind.BLUETOOTH_HEADSET,
                    AudioRouteRowStatus.ACTIVE,
                ),
            ),
        )

        statusText(0).assertIsDisplayed()
        statusText(0).assertTextEquals("✓")
    }

    // ── #79 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `clicking a row reports that row's device`() {
        val bt = btDevice()
        val spk = spkDevice()
        var clicked: AudioDevice? = null
        setPicker(
            listOf(
                rowOf(bt, AudioDeviceKind.BLUETOOTH_HEADSET, AudioRouteRowStatus.NONE),
                rowOf(spk, AudioDeviceKind.SPEAKERPHONE, AudioRouteRowStatus.NONE),
            ),
            onClickItem = { clicked = it },
        )

        composeTestRule.onNodeWithTag("call_audio_picker_item_1", useUnmergedTree = true).performClick()

        assertSame(spk, clicked)
    }

    // ── #80 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `a collapsed picker composes nothing`() {
        setPicker(
            listOf(
                rowOf(btDevice(), AudioDeviceKind.BLUETOOTH_HEADSET, AudioRouteRowStatus.NONE),
                rowOf(spkDevice(), AudioDeviceKind.SPEAKERPHONE, AudioRouteRowStatus.NONE),
            ),
            expanded = false,
        )

        composeTestRule.onNodeWithTag("call_audio_picker", useUnmergedTree = true).assertDoesNotExist()
    }

    // ── #81 ─────────────────────────────────────────────────────────────────────
    @Test
    fun `a disappearing device does not leave its state on the surviving row`() {
        val bt = btDevice()
        val spk = spkDevice("Speakerphone")
        val both = listOf(
            rowOf(bt, AudioDeviceKind.BLUETOOTH_HEADSET, AudioRouteRowStatus.CONNECTING),
            rowOf(spk, AudioDeviceKind.SPEAKERPHONE, AudioRouteRowStatus.ACTIVE),
        )
        val speakerOnly = listOf(rowOf(spk, AudioDeviceKind.SPEAKERPHONE, AudioRouteRowStatus.ACTIVE))

        Locale.setDefault(Locale.US)
        composeTestRule.setContent {
            var rows by remember { mutableStateOf(both) }
            DifftTheme(darkTheme = true) {
                ShowAudioDeviceOnClickView(
                    rows = rows,
                    expanded = true,
                    setExpanded = {},
                    onClickItem = { rows = speakerOnly },
                )
            }
        }

        statusText(0).assertTextEquals("Connecting…")
        // Dropping the Bluetooth row shifts the speaker up to index 0; without a per-kind key the
        // old row's remembered content would follow the position instead of the device.
        composeTestRule.onNodeWithTag("call_audio_picker_item_0", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()

        statusText(0).assertTextEquals("✓")
        composeTestRule.onNodeWithText("Speakerphone", useUnmergedTree = true).assertExists()
    }
}
