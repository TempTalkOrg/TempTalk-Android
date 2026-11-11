package com.difft.android.microbenchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.websocket.api.util.paddedMessageBody
import com.difft.android.websocket.api.util.removePadding
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.whispersystems.signalservice.internal.push.SignalServiceProtos










@RunWith(AndroidJUnit4::class)
class PipelineStageBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var fixture: PipelineFixture

    @Before
    fun setUp() {
        fixture = PipelineFixture.create(BACKLOG)
    }

    
    @Test
    fun fixtureRoundTripsToOriginalPlaintext() {
        val unpadded = fixture.decryptOne(fixture.envelopes[0]).removePadding()
        assertEquals(
            SignalServiceProtos.Content.parseFrom(fixture.plaintextContents[0]).dataMessage.body,
            SignalServiceProtos.Content.parseFrom(unpadded).dataMessage.body,
        )
    }

    @Test
    fun parseEnvelope_1000() {
        benchmarkRule.measureRepeated {
            fixture.envelopeBytes.forEach { bytes ->
                SignalServiceProtos.Envelope.parseFrom(bytes)
            }
        }
    }

    @Test
    fun decrypt_1000() {
        var iterations = 0
        benchmarkRule.measureRepeated {
            fixture.envelopes.forEach { fixture.decryptOne(it) }
            if (++iterations % 5 == 0) runWithTimingDisabled { System.gc() }
        }
    }

    




    @Test
    fun unpadParseConvert_1000() {
        val padded = fixture.envelopes.mapIndexed { i, envelope ->
            envelope to fixture.plaintextContents[i].paddedMessageBody()
        }
        benchmarkRule.measureRepeated {
            padded.forEach { (envelope, plaintext) ->
                val content = SignalServiceProtos.Content.parseFrom(plaintext.removePadding())
                toMessageModel(envelope, content)
            }
        }
    }

    private companion object {
        const val BACKLOG = 1000
    }
}
