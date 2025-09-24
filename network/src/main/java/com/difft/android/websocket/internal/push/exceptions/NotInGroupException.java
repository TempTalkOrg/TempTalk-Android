package com.difft.android.websocket.internal.push.exceptions;


import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException;

public final class NotInGroupException extends NonSuccessfulResponseCodeException {
  public NotInGroupException() {
    super(403);
  }
}
