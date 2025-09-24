package com.difft.android.websocket.internal.push;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ProvisioningMessage {

  @JsonProperty
  private String body;

  public ProvisioningMessage(String body) {
    this.body = body;
  }

}
