package com.difft.android.websocket.internal.push.http;


import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import com.difft.android.websocket.api.crypto.DigestingOutputStream;
import com.difft.android.websocket.api.crypto.ProfileCipherOutputStream;

import java.io.IOException;
import java.io.OutputStream;

public class ProfileCipherOutputStreamFactory implements OutputStreamFactory {

  private final ProfileKey key;

  public ProfileCipherOutputStreamFactory(ProfileKey key) {
    this.key = key;
  }

  @Override
  public DigestingOutputStream createFor(OutputStream wrap) throws IOException {
    return new ProfileCipherOutputStream(wrap, key);
  }

}
