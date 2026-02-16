

package com.difft.android.websocket.internal.push;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class MismatchedDevices {
  @JsonProperty
  public List<Integer> missingDevices;

  @JsonProperty
  public List<Integer> extraDevices;

  public List<Integer> getMissingDevices() {
    return missingDevices;
  }

  public List<Integer> getExtraDevices() {
    return extraDevices;
  }
}
