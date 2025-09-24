package com.difft.android.websocket.internal.push;

import com.fasterxml.jackson.annotation.JsonProperty;

class ReserveUsernameRequest {
  @JsonProperty
  private String nickname;

  ReserveUsernameRequest(String nickname) {
    this.nickname = nickname;
  }

  String getNickname() {
    return nickname;
  }
}
