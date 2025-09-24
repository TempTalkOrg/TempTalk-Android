package com.difft.android.websocket.internal.push;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class NewSendMessageResponse {

  @JsonProperty
  private int ver;

  @JsonProperty
  private int status;

  @JsonProperty
  private String reason;

  @JsonProperty
  private Data data;

  public NewSendMessageResponse() {}

  public NewSendMessageResponse(int ver, int status, String reason, Data data) {
    this.ver = ver;
    this.status = status;
    this.reason = reason;
    this.data = data;
  }

  public int getVer() {
    return ver;
  }

  public void setVer(int ver) {
    this.ver = ver;
  }

  public int getStatus() {
    return status;
  }

  public void setStatus(int status) {
    this.status = status;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public Data getData() {
    return data;
  }

  public void setData(Data data) {
    this.data = data;
  }

  public static class Data {

    @JsonProperty
    private boolean needsSync;

    @JsonProperty
    private long sequenceId;

    @JsonProperty
    private long systemShowTimestamp;

    @JsonProperty
    private long notifySequenceId;

    @JsonProperty
    private List<User> missing;

    @JsonProperty
    private List<User> extra;

    @JsonProperty
    private List<User> stale;

    @JsonProperty
    private List<UnavailableUser> unavailableUsers;

    // getters and setters
    public boolean isNeedsSync() {
      return needsSync;
    }

    public void setNeedsSync(boolean needsSync) {
      this.needsSync = needsSync;
    }

    public long getSequenceId() {
      return sequenceId;
    }

    public void setSequenceId(long sequenceId) {
      this.sequenceId = sequenceId;
    }

    public long getSystemShowTimestamp() {
      return systemShowTimestamp;
    }

    public void setSystemShowTimestamp(long systemShowTimestamp) {
      this.systemShowTimestamp = systemShowTimestamp;
    }

    public long getNotifySequenceId() {
      return notifySequenceId;
    }

    public void setNotifySequenceId(long notifySequenceId) {
      this.notifySequenceId = notifySequenceId;
    }

    public List<User> getMissing() {
      return missing;
    }

    public void setMissing(List<User> missing) {
      this.missing = missing;
    }

    public List<User> getExtra() {
      return extra;
    }

    public void setExtra(List<User> extra) {
      this.extra = extra;
    }

    public List<User> getStale() {
      return stale;
    }

    public void setStale(List<User> stale) {
      this.stale = stale;
    }

    public List<UnavailableUser> getUnavailableUsers() {
      return unavailableUsers;
    }

    public void setUnavailableUsers(List<UnavailableUser> unavailableUsers) {
      this.unavailableUsers = unavailableUsers;
    }
  }

  public static class User {

    @JsonProperty
    private String uid;

    @JsonProperty
    private String identityKey;

    @JsonProperty
    private int registrationId;

    // getters and setters
    public String getUid() {
      return uid;
    }

    public void setUid(String uid) {
      this.uid = uid;
    }

    public String getIdentityKey() {
      return identityKey;
    }

    public void setIdentityKey(String identityKey) {
      this.identityKey = identityKey;
    }

    public int getRegistrationId() {
      return registrationId;
    }

    public void setRegistrationId(int registrationId) {
      this.registrationId = registrationId;
    }
  }

  public static class UnavailableUser {

    @JsonProperty
    private String uid;

    @JsonProperty
    private String reason;

    // getters and setters
    public String getUid() {
      return uid;
    }

    public void setUid(String uid) {
      this.uid = uid;
    }

    public String getReason() {
      return reason;
    }

    public void setReason(String reason) {
      this.reason = reason;
    }
  }
}