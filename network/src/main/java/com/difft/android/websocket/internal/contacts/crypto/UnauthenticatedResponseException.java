package com.difft.android.websocket.internal.contacts.crypto;


public class UnauthenticatedResponseException extends Exception {
  public UnauthenticatedResponseException(Exception e) {
    super(e);
  }
  public UnauthenticatedResponseException(String s) {
    super(s);
  }
}
