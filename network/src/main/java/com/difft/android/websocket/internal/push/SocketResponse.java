package com.difft.android.websocket.internal.push;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {"ver":1,"status":10105,"reason":"This account has logged out and messages can not be reached.","data":null}
 */
public class SocketResponse {

    @JsonProperty
    public int ver;

    @JsonProperty
    public int status;

    @JsonProperty
    public String reason;

    public int getVer() {
        return ver;
    }

    public int getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }
}
