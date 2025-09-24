package com.difft.android.websocket.api.push.exceptions;

public class UsernameTakenException extends NonSuccessfulResponseCodeException {
  public UsernameTakenException() {
    super(409);
  }
}
