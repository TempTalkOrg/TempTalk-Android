/**
 * Copyright (C) 2014-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */

package com.difft.android.websocket.internal.push.exceptions;

import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException;
import com.difft.android.websocket.internal.push.MismatchedDevices;

public class MismatchedDevicesException extends NonSuccessfulResponseCodeException {

  private final MismatchedDevices mismatchedDevices;

  public MismatchedDevicesException(MismatchedDevices mismatchedDevices) {
    super(409);
    this.mismatchedDevices = mismatchedDevices;
  }

  public MismatchedDevices getMismatchedDevices() {
    return mismatchedDevices;
  }
}
