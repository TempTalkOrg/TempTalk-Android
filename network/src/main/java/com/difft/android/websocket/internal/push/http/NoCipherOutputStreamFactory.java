package com.difft.android.websocket.internal.push.http;

import com.difft.android.websocket.api.crypto.DigestingOutputStream;
import com.difft.android.websocket.api.crypto.NoCipherOutputStream;

import java.io.OutputStream;

/**
 * See {@link NoCipherOutputStream}.
 */
public final class NoCipherOutputStreamFactory implements OutputStreamFactory {

  @Override
  public DigestingOutputStream createFor(OutputStream wrap) {
    return new NoCipherOutputStream(wrap);
  }
}
