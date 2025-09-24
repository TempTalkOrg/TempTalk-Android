package com.difft.android.websocket.internal.configuration;


import com.difft.android.websocket.api.push.TrustStore;

import java.util.Map;

import okhttp3.ConnectionSpec;

public class SignalCdsiUrl extends SignalUrl {

  public SignalCdsiUrl(String url, TrustStore trustStore) {
    super(url, trustStore);
  }

  public SignalCdsiUrl(String url, Map<String, String> hostHeader, TrustStore trustStore, ConnectionSpec connectionSpec) {
    super(url, hostHeader, trustStore, connectionSpec);
  }
}
