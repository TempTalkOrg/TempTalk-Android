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

/**
 * Per-stage compute cost of the incoming-message pipeline, L2 of issue #1166: envelope parse,
 * decrypt, and unpad + Content parse + model conversion measured in isolation over a
 * [BACKLOG]-message batch. The three stages partition exactly the work [HalfPipelineBenchmark]
 * chains, so their sum is directly comparable with the L3 total (minus the persist step).
 * The inner EncryptContent parse is deliberately NOT a separate stage: production performs
 * it inside the decrypt call (NewMessageDecryptionUtil), so it is billed to the decrypt
 * stage — timing it again in the parse stage would double-count it in the L2 sum.
 * Together with the L1 ingestion numbers these form the per-stage cost breakdown of an
 * offline backlog.
 *
 * The decrypt stage mirrors `NewMessageDecryptionUtil` line for line (see
 * [PipelineFixture.decryptOne]): a fresh `DtProto(version)` per message, Base64-decode +
 * drop(1) of the sender identity key, and the per-call ByteString→List<UByte> boxing all
 * sit INSIDE the timed region because production pays them per envelope. The production
 * wrapper itself is not callable here (it requires a logged-in identity via
 * EncryptionDataManager); this is a call-shape re-expression, same pattern as
 * PointQueryPath.
 */
@RunWith(AndroidJUnit4::class)
class PipelineStageBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var fixture: PipelineFixture

    @Before
    fun setUp() {
        fixture = PipelineFixture.create(BACKLOG)
    }

    /** Fixture validity: one full roundtrip must reproduce the original plaintext. */
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

    /**
     * Post-decrypt stage: what decrypt hands the next step is a PADDED plaintext, so the
     * untimed setup stops at padding and the timed region pays unpad + Content parse +
     * conversion — the same three steps the L3 chain times after its decrypt call.
     */
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
