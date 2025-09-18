package com.difft.android.websocket.api.push.exceptions;

public class NonSuccessfulResumableUploadResponseCodeException extends NonSuccessfulResponseCodeException {
  public NonSuccessfulResumableUploadResponseCodeException(int code, String s) {
    super(code, s);
  }
}
