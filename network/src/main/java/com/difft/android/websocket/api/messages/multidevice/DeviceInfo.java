

package com.difft.android.websocket.api.messages.multidevice;

import com.fasterxml.jackson.annotation.JsonProperty;

public class DeviceInfo {

  @JsonProperty
  public int id;

  @JsonProperty
  public String name;

  @JsonProperty
  public long created;

  @JsonProperty
  public long lastSeen;

  public DeviceInfo() {}

  public int getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public long getCreated() {
    return created;
  }

  public long getLastSeen() {
    return lastSeen;
  }
}
