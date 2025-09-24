package com.difft.android.websocket.api;

public class ContentTooLargeException extends IllegalStateException {
  public ContentTooLargeException(long size) {
    super("Too large! Size: " + size + " bytes");
  }
}
