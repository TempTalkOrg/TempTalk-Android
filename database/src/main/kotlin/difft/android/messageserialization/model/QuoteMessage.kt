//package com.difft.android.messageserialization.model
//
//import difft.android.messageserialization.For
//
//abstract class QuoteMessage(
//    id: String,
//    fromWho: For,
//    toWho: For,
//    systemShowTimestamp: Long,
//    timeStamp: Long,
//    sendType: Int,
//    expiresInSeconds: Int,
//    notifySequenceId: Long,
//    mode: Int,
//    val quote: Quote? = null,
//    val forwardContext: ForwardContext? = null,
//    var recall: Recall? = null,
//    var card: Card? = null,
//    var mentions: List<Mention>? = null,
//    var reactions: MutableList<Reaction>? = null
//) : Message(id, fromWho, toWho, systemShowTimestamp, timeStamp, sendType, expiresInSeconds, notifySequenceId, mode)