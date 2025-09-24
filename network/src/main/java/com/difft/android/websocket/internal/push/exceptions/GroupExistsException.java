package com.difft.android.websocket.internal.push.exceptions;


import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException;

public final class GroupExistsException extends NonSuccessfulResponseCodeException {
  public GroupExistsException() {
    super(409);
  }
}
