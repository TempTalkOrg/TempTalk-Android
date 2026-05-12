package com.difft.android.websocket.api.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * SSLSocketFactory wrapper that restricts enabled protocols to TLS 1.2 and 1.3.
 * <p>
 * Filters the desired protocols against the device's actual supported protocols
 * at runtime, so it works safely across all API levels (TLS 1.3 is available
 * from Android 10 / API 29; older devices fall back to TLS 1.2 only).
 *
 * @see SSLSocketFactory
 */
public class TlsSocketFactory extends SSLSocketFactory {
  private static final String[] TLS_V12_V13_ONLY = {"TLSv1.3", "TLSv1.2"};

  private final SSLSocketFactory delegate;

  public TlsSocketFactory(SSLSocketFactory base) {
    this.delegate = base;
  }

  @Override
  public String[] getDefaultCipherSuites() {
    return delegate.getDefaultCipherSuites();
  }

  @Override
  public String[] getSupportedCipherSuites() {
    return delegate.getSupportedCipherSuites();
  }

  @Override
  public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
    return patch(delegate.createSocket(s, host, port, autoClose));
  }

  @Override
  public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
    return patch(delegate.createSocket(host, port));
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException, UnknownHostException {
    return patch(delegate.createSocket(host, port, localHost, localPort));
  }

  @Override
  public Socket createSocket(InetAddress host, int port) throws IOException {
    return patch(delegate.createSocket(host, port));
  }

  @Override
  public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
    return patch(delegate.createSocket(address, port, localAddress, localPort));
  }

  private Socket patch(Socket s) {
    if (s instanceof SSLSocket) {
      SSLSocket sslSocket = (SSLSocket) s;
      Set<String> supported = new HashSet<>(Arrays.asList(sslSocket.getSupportedProtocols()));
      List<String> enabled = new ArrayList<>();
      for (String protocol : TLS_V12_V13_ONLY) {
        if (supported.contains(protocol)) {
          enabled.add(protocol);
        }
      }
      if (!enabled.isEmpty()) {
        sslSocket.setEnabledProtocols(enabled.toArray(new String[0]));
      }
    }
    return s;
  }
}
