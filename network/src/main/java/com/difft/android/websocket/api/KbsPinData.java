package com.difft.android.websocket.api;

import com.difft.android.websocket.api.kbs.MasterKey;
import com.difft.android.websocket.internal.contacts.entities.TokenResponse;

public final class KbsPinData {

  private final MasterKey     masterKey;
  private final TokenResponse tokenResponse;

  // Visible for testing
  public KbsPinData(MasterKey masterKey, TokenResponse tokenResponse) {
    this.masterKey     = masterKey;
    this.tokenResponse = tokenResponse;
  }

  public MasterKey getMasterKey() {
    return masterKey;
  }

  public TokenResponse getTokenResponse() {
    return tokenResponse;
  }

  public int getRemainingTries() {
    return tokenResponse.getTries();
  }
}
