package com.difft.android.websocket.internal.push;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class OutgoingPushMessageList {

    @JsonProperty
    private List<OutgoingPushMessage> messages;
    @JsonProperty
    private int silent;
    @JsonProperty
    private long timestamp;

    public OutgoingPushMessageList(List<OutgoingPushMessage> messages, boolean silent, long timestamp) {
        this.messages = messages;
        if (silent) {
            this.silent = 1;
        } else {
            this.silent = 0;
        }
        this.timestamp = timestamp;
    }
}
