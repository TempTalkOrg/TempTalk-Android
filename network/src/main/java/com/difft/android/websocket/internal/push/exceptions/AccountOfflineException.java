package com.difft.android.websocket.internal.push.exceptions;

import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException;

public final class AccountOfflineException extends NonSuccessfulResponseCodeException {
    private final int status;
    private final String reason;

    public AccountOfflineException(int status, String reason) {
        super(404);
        this.status = status;
        this.reason = reason;
    }

    public int getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

}
