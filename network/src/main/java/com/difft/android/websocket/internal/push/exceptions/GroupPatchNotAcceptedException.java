package com.difft.android.websocket.internal.push.exceptions;

import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException;

public final class GroupPatchNotAcceptedException extends NonSuccessfulResponseCodeException {
  public GroupPatchNotAcceptedException() {
    super(400);
  }
}
