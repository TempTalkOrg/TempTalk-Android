

package com.difft.android.websocket.api.push.exceptions;

public class RemoteAttestationResponseExpiredException extends NonSuccessfulResponseCodeException {
  public RemoteAttestationResponseExpiredException(String message) {
    super(409, message);
  }
}
