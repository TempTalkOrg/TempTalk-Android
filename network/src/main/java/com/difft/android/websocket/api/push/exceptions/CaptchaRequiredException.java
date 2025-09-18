package com.difft.android.websocket.api.push.exceptions;

public class CaptchaRequiredException extends NonSuccessfulResponseCodeException {
  public CaptchaRequiredException() {
    super(402);
  }
}
