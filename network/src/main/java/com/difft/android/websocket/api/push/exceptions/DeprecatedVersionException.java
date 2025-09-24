package com.difft.android.websocket.api.push.exceptions;

public class DeprecatedVersionException extends NonSuccessfulResponseCodeException {
  public DeprecatedVersionException() {
    super(499);
  }
}
