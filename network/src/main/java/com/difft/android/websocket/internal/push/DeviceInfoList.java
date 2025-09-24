package com.difft.android.websocket.internal.push;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.difft.android.websocket.api.messages.multidevice.DeviceInfo;

import java.util.List;

public class DeviceInfoList {

  @JsonProperty
  public List<DeviceInfo> devices;

  public DeviceInfoList() {}

  public List<DeviceInfo> getDevices() {
    return devices;
  }
}
