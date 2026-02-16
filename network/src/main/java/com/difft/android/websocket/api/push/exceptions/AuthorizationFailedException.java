
package com.difft.android.websocket.api.push.exceptions;

public class AuthorizationFailedException extends NonSuccessfulResponseCodeException {
  public AuthorizationFailedException(int code, String s) {
    super(code, s);
  }
}
