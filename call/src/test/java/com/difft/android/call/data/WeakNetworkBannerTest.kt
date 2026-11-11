package com.difft.android.call.data

import com.difft.android.call.network.NetworkQualityLevel
import com.difft.android.call.network.NetworkQualityView
import org.junit.Assert.assertEquals
import org.junit.Test





class WeakNetworkBannerTest {

    private fun view(
        local: NetworkQualityLevel = NetworkQualityLevel.EXCELLENT,
        remote: Map<String, NetworkQualityLevel> = emptyMap(),
        suppressed: Boolean = false,
    ) = NetworkQualityView(local = local, remote = remote, suppressed = suppressed)

    @Test
    fun `local bad shows the local banner in a multi-party call`() {
        assertEquals(
            "a bad local link is announced in every scene, multi-party included",
            WeakNetworkBanner.LOCAL,
            WeakNetworkBanner.resolve(view(local = NetworkQualityLevel.BAD), participantCount = 4),
        )
    }

    @Test
    fun `local bad shows the local banner with only two people`() {
        assertEquals(
            WeakNetworkBanner.LOCAL,
            WeakNetworkBanner.resolve(view(local = NetworkQualityLevel.BAD), participantCount = 2),
        )
    }

    @Test
    fun `remote bad shows the remote banner when exactly two people are in the call`() {
        assertEquals(
            WeakNetworkBanner.REMOTE,
            WeakNetworkBanner.resolve(
                view(remote = mapOf("alice" to NetworkQualityLevel.BAD)),
                participantCount = 2,
            ),
        )
    }

    @Test
    fun `a third person in the call hands a bad remote to the tile badge`() {
        assertEquals(
            "three people means the badge can name the bad peer; the banner would say only \"the other party\"",
            WeakNetworkBanner.NONE,
            WeakNetworkBanner.resolve(
                view(remote = mapOf("alice" to NetworkQualityLevel.BAD)),
                
                
                participantCount = 3,
            ),
        )
    }

    @Test
    fun `a good remote entry never raises the banner`() {
        
        
        assertEquals(
            WeakNetworkBanner.NONE,
            WeakNetworkBanner.resolve(
                view(remote = mapOf("alice" to NetworkQualityLevel.GOOD)),
                participantCount = 2,
            ),
        )
    }

    @Test
    fun `a suppressed snapshot resolves to none without reading the flag`() {
        
        
        assertEquals(
            WeakNetworkBanner.NONE,
            WeakNetworkBanner.resolve(view(suppressed = true), participantCount = 2),
        )
    }

    @Test
    fun `a healthy call shows nothing at any headcount`() {
        assertEquals(WeakNetworkBanner.NONE, WeakNetworkBanner.resolve(NetworkQualityView.NONE, participantCount = 2))
        assertEquals(WeakNetworkBanner.NONE, WeakNetworkBanner.resolve(NetworkQualityView.NONE, participantCount = 4))
    }

    @Test
    fun `local bad outranks a bad remote`() {
        
        
        assertEquals(
            WeakNetworkBanner.LOCAL,
            WeakNetworkBanner.resolve(
                view(local = NetworkQualityLevel.BAD, remote = mapOf("alice" to NetworkQualityLevel.BAD)),
                participantCount = 2,
            ),
        )
    }
}
