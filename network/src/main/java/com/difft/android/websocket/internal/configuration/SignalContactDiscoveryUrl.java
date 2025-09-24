package com.difft.android.websocket.internal.configuration;


import com.difft.android.websocket.api.push.TrustStore;

import java.util.Map;

import okhttp3.ConnectionSpec;

public class SignalContactDiscoveryUrl extends SignalUrl {

  public SignalContactDiscoveryUrl(String url, TrustStore trustStore) {
    super(url, trustStore);
  }

  public SignalContactDiscoveryUrl(String url, Map<String, String> hostHeader, TrustStore trustStore, ConnectionSpec connectionSpec) {
    super(url, hostHeader, trustStore, connectionSpec);
  }
}
