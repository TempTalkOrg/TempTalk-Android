
package com.difft.android.websocket.api.push.exceptions;

public class ExpectationFailedException extends NonSuccessfulResponseCodeException {
  public ExpectationFailedException() {
    super(417);
  }
}
