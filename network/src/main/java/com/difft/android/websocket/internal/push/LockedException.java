package com.difft.android.websocket.internal.push;


import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException;

public final class LockedException extends NonSuccessfulResponseCodeException {

  private final int    length;
  private final long   timeRemaining;
  private final String basicStorageCredentials;

  public LockedException(int length, long timeRemaining, String basicStorageCredentials) {
    super(423);
    this.length                  = length;
    this.timeRemaining           = timeRemaining;
    this.basicStorageCredentials = basicStorageCredentials;
  }

  public int getLength() {
    return length;
  }

  public long getTimeRemaining() {
    return timeRemaining;
  }

  public String getBasicStorageCredentials() {
    return basicStorageCredentials;
  }
}
