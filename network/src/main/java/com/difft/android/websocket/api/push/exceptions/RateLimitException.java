

package com.difft.android.websocket.api.push.exceptions;


import java.util.Optional;

public class RateLimitException extends NonSuccessfulResponseCodeException {
  private final Optional<Long> retryAfterMilliseconds;

  public RateLimitException(int status, String message) {
    this(status, message, Optional.empty());
  }

  public RateLimitException(int status, String message, Optional<Long> retryAfterMilliseconds) {
    super(status, message);
    this.retryAfterMilliseconds = retryAfterMilliseconds;
  }

  public Optional<Long> getRetryAfterMilliseconds() {
    return retryAfterMilliseconds;
  }

  @Override
  public String toString() {
    return "RateLimitException{" +
            "retryAfterMilliseconds=" + retryAfterMilliseconds +
            '}';
  }
}
