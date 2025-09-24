/**
 * Copyright (C) 2014-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */

package com.difft.android.websocket.internal.push.exceptions;

import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException;
import com.difft.android.websocket.internal.push.StaleDevices;

public class StaleDevicesException extends NonSuccessfulResponseCodeException {

  private final StaleDevices staleDevices;

  public StaleDevicesException(StaleDevices staleDevices) {
    super(410);
    this.staleDevices = staleDevices;
  }

  public StaleDevices getStaleDevices() {
    return staleDevices;
  }
}
