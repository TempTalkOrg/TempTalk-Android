package com.difft.android.websocket.api.util

import com.difft.android.websocket.api.ContentTooLargeException
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content
import org.whispersystems.signalservice.internal.push.content


class SignalServiceContentCreator(
    private val maxEnvelopeSize: Long,
) {
    fun createFrom(message: SignalServiceProtos.DataMessage): Content {
        return createMessageContent(message)
    }

    fun createFrom(message: SignalServiceProtos.NotifyMessage): Content {
        return createClientNotifyContent(message)
    }

    fun createFrom(message: SignalServiceProtos.CallMessage): Content {
        return createCallContent(message)
    }

    private fun createMessageContent(message: SignalServiceProtos.DataMessage): Content {
        return enforceMaxContentSize(content { dataMessage = message })
    }


    private fun createClientNotifyContent(message: SignalServiceProtos.NotifyMessage): Content {
        val content = content { notifyMessage = message }
        return enforceMaxContentSize(content)
    }

    private fun createCallContent(message: SignalServiceProtos.CallMessage): Content {
        val content = content { callMessage = message }
        return enforceMaxContentSize(content)
    }

    private fun enforceMaxContentSize(content: Content): Content {
        val size = content.toByteArray().size
        if (maxEnvelopeSize > 0 && size > maxEnvelopeSize) {
            throw ContentTooLargeException(size.toLong())
        }
        return content
    }
}
