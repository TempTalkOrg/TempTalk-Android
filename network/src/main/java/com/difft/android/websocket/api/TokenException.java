package com.difft.android.websocket.api;

import com.difft.android.websocket.internal.contacts.entities.TokenResponse;

class TokenException extends Exception {

  private final TokenResponse nextToken;
  private final boolean       canAutomaticallyRetry;

  TokenException(TokenResponse nextToken, boolean canAutomaticallyRetry) {
    this.nextToken             = nextToken;
    this.canAutomaticallyRetry = canAutomaticallyRetry;
  }

  public TokenResponse getToken() {
    return nextToken;
  }

  public boolean isCanAutomaticallyRetry() {
    return canAutomaticallyRetry;
  }
}
