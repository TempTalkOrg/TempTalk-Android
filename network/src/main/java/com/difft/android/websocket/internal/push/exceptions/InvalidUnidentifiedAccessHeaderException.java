package com.difft.android.websocket.internal.push.exceptions;


import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException;

/**
 * Indicates that the unidentified authorization header provided to the multi_recipient endpoint
 * was incorrect (i.e. one or more of your unauthorized access keys is invalid);
 */
public class InvalidUnidentifiedAccessHeaderException extends NonSuccessfulResponseCodeException {

  public InvalidUnidentifiedAccessHeaderException() {
    super(401);
  }
}
