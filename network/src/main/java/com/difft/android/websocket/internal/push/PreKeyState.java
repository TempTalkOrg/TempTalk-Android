package com.difft.android.websocket.internal.push;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import org.signal.libsignal.protocol.IdentityKey;
import com.difft.android.websocket.internal.util.JsonUtil;

public class PreKeyState {

    @JsonProperty
    @JsonSerialize(using = JsonUtil.IdentityKeySerializer.class)
    @JsonDeserialize(using = JsonUtil.IdentityKeyDeserializer.class)
    private IdentityKey identityKey;
    @JsonProperty
    private String newSign;

    public PreKeyState() {
    }

    public PreKeyState(IdentityKey identityKey, String identityKeySign) {
        this.identityKey = identityKey;
        this.newSign = identityKeySign;
    }

    public IdentityKey getIdentityKey() {
        return identityKey;
    }

    public String getNewSign() {
        return newSign;
    }
}
