package com.difft.android.websocket.api.messages

enum class DetailMessageType(val value: Int) {
    Unknown(0),
    Forward(1),
    Contact(2),
    Recall(3),
    Task(4),
    Vote(5),
    Reaction(6),
    Card(7),
    Screenshot(8),
    Confidential(9),
    VerifyIdentity(10),
    CardRefresh(1000),
    CallEnd(1001),
    GroupCallEnd(1002),
}