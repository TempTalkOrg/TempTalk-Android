/*
 * Copyright (C) 2014-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */

package com.difft.android.websocket.api.messages;


public interface ProgressListener {
  /**
   * Called on a progress change event.
   *
   * @param total The total amount to transmit/receive in bytes.
   * @param progress The amount that has been transmitted/received in bytes thus far
   */
  public void onAttachmentProgress(long total, long progress);
}