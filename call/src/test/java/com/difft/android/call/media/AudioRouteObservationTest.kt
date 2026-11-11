package com.difft.android.call.media

import android.media.AudioDeviceInfo
import com.difft.android.call.manager.AudioDeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test







class AudioRouteObservationTest {

    
    @Test
    fun `the observed route is inferred from the legacy readings, cross-checked by commType`() {
        assertEquals(
            "sco on wins",
            ObservedRoute.BLUETOOTH,
            inferObservedRoute(commType = null, scoOn = true, speakerOn = false),
        )
        assertEquals(
            "speakerphone on",
            ObservedRoute.SPEAKER,
            inferObservedRoute(commType = null, scoOn = false, speakerOn = true),
        )
        assertEquals(
            "two negatives mean earpiece or wired",
            ObservedRoute.EARPIECE_OR_WIRED,
            inferObservedRoute(commType = null, scoOn = false, speakerOn = false),
        )
        assertEquals(
            "SCO communication device without the legacy flag",
            ObservedRoute.BLUETOOTH,
            inferObservedRoute(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, scoOn = false, speakerOn = false),
        )
        assertEquals(
            "LE Audio headset is only visible through the modern API",
            ObservedRoute.BLUETOOTH,
            inferObservedRoute(AudioDeviceInfo.TYPE_BLE_HEADSET, scoOn = false, speakerOn = false),
        )
        assertEquals(
            ObservedRoute.SPEAKER,
            inferObservedRoute(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, scoOn = false, speakerOn = false),
        )
        assertEquals(
            ObservedRoute.EARPIECE_OR_WIRED,
            inferObservedRoute(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, scoOn = false, speakerOn = false),
        )
        assertEquals(
            ObservedRoute.EARPIECE_OR_WIRED,
            inferObservedRoute(AudioDeviceInfo.TYPE_WIRED_HEADSET, scoOn = false, speakerOn = false),
        )
    }

    
    




    @Test
    fun `a communication device type maps only to the route it actually indicates`() {
        assertEquals(ObservedRoute.BLUETOOTH, commDeviceRoute(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(ObservedRoute.BLUETOOTH, commDeviceRoute(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(ObservedRoute.SPEAKER, commDeviceRoute(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(
            ObservedRoute.EARPIECE_OR_WIRED,
            commDeviceRoute(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE),
        )
        assertEquals(
            ObservedRoute.EARPIECE_OR_WIRED,
            commDeviceRoute(AudioDeviceInfo.TYPE_WIRED_HEADSET),
        )
        assertEquals(
            ObservedRoute.EARPIECE_OR_WIRED,
            commDeviceRoute(AudioDeviceInfo.TYPE_WIRED_HEADPHONES),
        )
        assertEquals(
            ObservedRoute.EARPIECE_OR_WIRED,
            commDeviceRoute(AudioDeviceInfo.TYPE_USB_HEADSET),
        )
        assertNull(commDeviceRoute(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertNull(commDeviceRoute(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertNull(commDeviceRoute(AudioDeviceInfo.TYPE_TELEPHONY))
    }

    @Test
    fun `the confirmation fingerprint names the signal that carried the observation`() {
        assertEquals("sco", observedVia(ObservedRoute.BLUETOOTH, scoOn = true, speakerOn = false, commType = null))
        assertEquals(
            "speakerphoneOn",
            observedVia(ObservedRoute.SPEAKER, scoOn = false, speakerOn = true, commType = null),
        )
        assertEquals(
            "commDevice",
            observedVia(
                ObservedRoute.BLUETOOTH,
                scoOn = false,
                speakerOn = false,
                commType = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            ),
        )
        assertEquals(
            "legacyNegative",
            observedVia(ObservedRoute.EARPIECE_OR_WIRED, scoOn = false, speakerOn = false, commType = null),
        )
    }

    
    @Test
    fun `every observed route matches exactly the kinds it can be`() {
        val all = AudioDeviceKind.entries
        val expected = mapOf(
            ObservedRoute.BLUETOOTH to setOf(AudioDeviceKind.BLUETOOTH_HEADSET),
            ObservedRoute.SPEAKER to setOf(AudioDeviceKind.SPEAKERPHONE),
            
            
            ObservedRoute.EARPIECE_OR_WIRED to
                setOf(AudioDeviceKind.EARPIECE, AudioDeviceKind.WIRED_HEADSET),
        )
        ObservedRoute.entries.forEach { route ->
            all.forEach { kind ->
                val shouldMatch = kind in expected.getValue(route)
                if (shouldMatch) {
                    assertTrue("$route should match $kind", route.matches(kind))
                } else {
                    assertFalse("$route must not match $kind", route.matches(kind))
                }
            }
        }
    }
}
