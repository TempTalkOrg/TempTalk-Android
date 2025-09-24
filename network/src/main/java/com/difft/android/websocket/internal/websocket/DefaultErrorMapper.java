package com.difft.android.websocket.internal.websocket;


import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException;
import com.difft.android.websocket.api.push.exceptions.CaptchaRequiredException;
import com.difft.android.websocket.api.push.exceptions.DeprecatedVersionException;
import com.difft.android.websocket.api.push.exceptions.ExpectationFailedException;
import com.difft.android.websocket.api.push.exceptions.MalformedResponseException;
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException;
import com.difft.android.websocket.api.push.exceptions.NotFoundException;
import com.difft.android.websocket.api.push.exceptions.ProofRequiredException;
import com.difft.android.websocket.api.push.exceptions.RateLimitException;
import com.difft.android.websocket.api.push.exceptions.ServerRejectedException;
import com.difft.android.websocket.internal.push.AuthCredentials;
import com.difft.android.websocket.internal.push.DeviceLimit;
import com.difft.android.websocket.internal.push.DeviceLimitExceededException;
import com.difft.android.websocket.internal.push.LockedException;
import com.difft.android.websocket.internal.push.MismatchedDevices;
import com.difft.android.websocket.internal.push.ProofRequiredResponse;
import com.difft.android.websocket.internal.push.PushServiceSocket;
import com.difft.android.websocket.internal.push.SocketResponse;
import com.difft.android.websocket.internal.push.StaleDevices;
import com.difft.android.websocket.internal.push.exceptions.AccountOfflineException;
import com.difft.android.websocket.internal.push.exceptions.MismatchedDevicesException;
import com.difft.android.websocket.internal.push.exceptions.StaleDevicesException;
import com.difft.android.websocket.internal.util.JsonUtil;
import com.difft.android.websocket.internal.util.Util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * A default implementation of a {@link ErrorMapper} that can parse most known application
 * errors.
 * <p>
 * Can be extended to add custom error mapping via {@link #extend()}.
 * <p>
 * While this call can be used directly, it is primarily intended to be used as part of
 * {@link DefaultResponseMapper}.
 */
public final class DefaultErrorMapper implements ErrorMapper {

  private static final DefaultErrorMapper INSTANCE = new DefaultErrorMapper();

  private final Map<Integer, ErrorMapper> customErrorMappers;

  public static DefaultErrorMapper getDefault() {
    return INSTANCE;
  }

  public static DefaultErrorMapper.Builder extend() {
    return new DefaultErrorMapper.Builder();
  }

  private DefaultErrorMapper() {
    this(Collections.emptyMap());
  }

  private DefaultErrorMapper(Map<Integer, ErrorMapper> customErrorMappers) {
    this.customErrorMappers = customErrorMappers;
  }

  public Throwable parseError(WebsocketResponse websocketResponse) {
    return parseError(websocketResponse.status, websocketResponse.body, websocketResponse::getHeader);
  }

  @Override
  public Throwable parseError(int status, String body, Function<String, String> getHeader) {
//    if (customErrorMappers.containsKey(status)) {
//      try {
//        return customErrorMappers.get(status).parseError(status, body, getHeader);
//      } catch (MalformedResponseException e) {
//        return e;
//      }
//    }

    switch (status) {
      case 401:
      case 403:
        return new AuthorizationFailedException(status, "Authorization failed!");
      case 402:
        return new CaptchaRequiredException();
      case 404:
          SocketResponse socketResponse;
          try {
              socketResponse = JsonUtil.fromJsonResponse(body, SocketResponse.class);
          } catch (Exception e) {
              return new NotFoundException("At least one unregistered user in message send.");
          }
          if (socketResponse != null && (socketResponse.getStatus() == 10105 || socketResponse.getStatus() == 10110)) {
              return new AccountOfflineException(socketResponse.status, socketResponse.reason);
          } else {
              return new NotFoundException("At least one unregistered user in message send.");
          }
      case 409:
        try {
          return new MismatchedDevicesException(JsonUtil.fromJsonResponse(body, MismatchedDevices.class));
        } catch (MalformedResponseException e) {
          return e;
        }
      case 410:
        try {
          return new StaleDevicesException(JsonUtil.fromJsonResponse(body, StaleDevices.class));
        } catch (MalformedResponseException e) {
          return e;
        }
      case 411:
        try {
          return new DeviceLimitExceededException(JsonUtil.fromJsonResponse(body, DeviceLimit.class));
        } catch (MalformedResponseException e) {
          return e;
        }
      case 413:
      case 429: {
        long           retryAfterLong = Util.parseLong(getHeader.apply("Retry-After"), -1);
        Optional<Long> retryAfter     = retryAfterLong != -1 ? Optional.of(TimeUnit.SECONDS.toMillis(retryAfterLong)) : Optional.empty();
        return new RateLimitException(status, "Rate limit exceeded: " + status, retryAfter);
      }
      case 417:
        return new ExpectationFailedException();
      case 423:
        PushServiceSocket.RegistrationLockFailure accountLockFailure;
        try {
          accountLockFailure = JsonUtil.fromJsonResponse(body, PushServiceSocket.RegistrationLockFailure.class);
        } catch (MalformedResponseException e) {
          return e;
        }

        AuthCredentials credentials = accountLockFailure.backupCredentials;
        String basicStorageCredentials = credentials != null ? credentials.asBasic() : null;

        return new LockedException(accountLockFailure.length,
                                   accountLockFailure.timeRemaining,
                                   basicStorageCredentials);
      case 428:
        ProofRequiredResponse proofRequiredResponse;
        try {
          proofRequiredResponse = JsonUtil.fromJsonResponse(body, ProofRequiredResponse.class);
        } catch (MalformedResponseException e) {
          return e;
        }
        String retryAfterRaw = getHeader.apply("Retry-After");
        long retryAfter = Util.parseInt(retryAfterRaw, -1);

        return new ProofRequiredException(proofRequiredResponse, retryAfter);
      case 499:
        return new DeprecatedVersionException();
      case 508:
        return new ServerRejectedException();
    }

    if (status != 200 && status != 202 && status != 204) {
      return new NonSuccessfulResponseCodeException(status, "Bad response: " + status);
    }

    return null;
  }

  public static class Builder {
    private final Map<Integer, ErrorMapper> customErrorMappers = new HashMap<>();

    public Builder withCustom(int status, ErrorMapper errorMapper) {
      customErrorMappers.put(status, errorMapper);
      return this;
    }

    public ErrorMapper build() {
      return new DefaultErrorMapper(customErrorMappers);
    }
  }
}
