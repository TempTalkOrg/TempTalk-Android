/*
 * Copyright (C) 2014-2017 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */

package com.difft.android.websocket.internal.push;

import com.difft.android.base.log.lumberjack.L;
import difft.android.messageserialization.For;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;

import org.signal.libsignal.protocol.util.Pair;
import com.difft.android.websocket.api.messages.multidevice.DeviceInfo;
import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException;
import com.difft.android.websocket.api.push.exceptions.DeprecatedVersionException;
import com.difft.android.websocket.api.push.exceptions.ExpectationFailedException;
import com.difft.android.websocket.api.push.exceptions.MalformedResponseException;
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException;
import com.difft.android.websocket.api.push.exceptions.NotFoundException;
import com.difft.android.websocket.api.push.exceptions.ProofRequiredException;
import com.difft.android.websocket.api.push.exceptions.PushNetworkException;
import com.difft.android.websocket.api.push.exceptions.RateLimitException;
import com.difft.android.websocket.api.push.exceptions.RemoteAttestationResponseExpiredException;
import com.difft.android.websocket.api.push.exceptions.ServerRejectedException;
import com.difft.android.websocket.api.push.exceptions.UnregisteredUserException;
import com.difft.android.websocket.api.util.Tls12SocketFactory;
import com.difft.android.websocket.api.util.TlsProxySocketFactory;
import com.difft.android.websocket.internal.TrustAllSSLSocketFactory;
import com.difft.android.websocket.internal.configuration.SignalProxy;
import com.difft.android.websocket.internal.configuration.SignalServiceConfiguration;
import com.difft.android.websocket.internal.configuration.SignalUrl;
import com.difft.android.websocket.internal.push.exceptions.AccountOfflineException;
import com.difft.android.websocket.internal.push.exceptions.MismatchedDevicesException;
import com.difft.android.websocket.internal.push.exceptions.StaleDevicesException;
import com.difft.android.websocket.internal.util.BlacklistingTrustManager;
import com.difft.android.websocket.internal.util.JsonUtil;
import com.difft.android.websocket.internal.util.Util;
import com.difft.android.websocket.util.Base64;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.Dns;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * @author Moxie Marlinspike
 */
public class PushServiceSocket {
    private static final String PROVISIONING_CODE_PATH = "/v1/devices/provisioning/code";
    private static final String PROVISIONING_MESSAGE_PATH = "/v1/provisioning/%s";
    private static final String DEVICE_PATH = "/v1/devices/%s";
    private static final Map<String, String> NO_HEADERS = Collections.emptyMap();
    private static final ResponseCodeHandler NO_HANDLER = new EmptyResponseCodeHandler();

    private long soTimeoutMillis = TimeUnit.SECONDS.toMillis(15);
    private final Set<Call> connections = new HashSet<>();

    private final ServiceConnectionHolder[] serviceClients;
    private final ConnectionHolder[] contactDiscoveryClients;
    private final ConnectionHolder[] keyBackupServiceClients;

    private final SecureRandom random;
    private final boolean automaticNetworkRetry;

    public PushServiceSocket(SignalServiceConfiguration configuration, boolean automaticNetworkRetry) {
        this.automaticNetworkRetry = automaticNetworkRetry;
        this.serviceClients = createServiceConnectionHolders(configuration.getSignalServiceUrls(), configuration.getNetworkInterceptors(), configuration.getDns(), configuration.getSignalProxy());
        this.contactDiscoveryClients = createConnectionHolders(configuration.getSignalContactDiscoveryUrls(), configuration.getNetworkInterceptors(), configuration.getDns(), configuration.getSignalProxy());
        this.keyBackupServiceClients = createConnectionHolders(configuration.getSignalKeyBackupServiceUrls(), configuration.getNetworkInterceptors(), configuration.getDns(), configuration.getSignalProxy());
        this.random = new SecureRandom();
    }

    public String getNewDeviceVerificationCode() throws IOException {
        String responseText = makeServiceRequest(PROVISIONING_CODE_PATH, "GET", null);
        return JsonUtil.fromJson(responseText, DeviceCode.class).getVerificationCode();
    }


    public List<DeviceInfo> getDevices() throws IOException {
        String responseText = makeServiceRequest(String.format(DEVICE_PATH, ""), "GET", null);
        return JsonUtil.fromJson(responseText, DeviceInfoList.class).getDevices();
    }

    public Response getDevicesResponse() throws PushNetworkException, NonSuccessfulResponseCodeException, MalformedResponseException {
        return makeServiceRequest(String.format(DEVICE_PATH, ""), "GET", jsonRequestBody(null), NO_HEADERS, NO_HANDLER);
    }

    public void sendProvisioningMessage(String destination, byte[] body) throws IOException {
        makeServiceRequest(String.format(PROVISIONING_MESSAGE_PATH, destination), "PUT",
                JsonUtil.toJson(new ProvisioningMessage(Base64.encodeBytes(body))));
    }
    public NewSendMessageResponse sendMessageNew(NewOutgoingPushMessage bundle, For recipient)
            throws IOException {
        try {
            String basePath;
            if (recipient instanceof For.Group) {
                basePath = "/v3/messages/group/%s";
            } else {
                basePath = "/v3/messages/%s";
            }
            String responseText = makeServiceRequest(String.format(basePath, recipient.getId()), "PUT", JsonUtil.toJson(bundle), NO_HEADERS, NO_HANDLER);

            return JsonUtil.fromJson(responseText, NewSendMessageResponse.class);
        } catch (NotFoundException nfe) {
            throw new UnregisteredUserException(recipient.getId(), nfe);
        }
    }

    private String makeServiceRequest(String urlFragment, String method, String jsonBody)
            throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException {
        return makeServiceRequest(urlFragment, method, jsonBody, NO_HEADERS, NO_HANDLER);
    }

    private String makeServiceRequest(String urlFragment, String method, String jsonBody, Map<String, String> headers, ResponseCodeHandler responseCodeHandler)
            throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException {
        ResponseBody responseBody = makeServiceBodyRequest(urlFragment, method, jsonRequestBody(jsonBody), headers, responseCodeHandler);
        try {
            return responseBody.string();
        } catch (IOException e) {
            throw new PushNetworkException(e);
        }
    }

    private static RequestBody jsonRequestBody(String jsonBody) {
        return jsonBody != null
                ? RequestBody.create(MediaType.parse("application/json"), jsonBody)
                : null;
    }

    private ResponseBody makeServiceBodyRequest(String urlFragment,
                                                String method,
                                                RequestBody body,
                                                Map<String, String> headers,
                                                ResponseCodeHandler responseCodeHandler)
            throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException {
        return makeServiceRequest(urlFragment, method, body, headers, responseCodeHandler).body();
    }

    private Response makeServiceRequest(String urlFragment,
                                        String method,
                                        RequestBody body,
                                        Map<String, String> headers,
                                        ResponseCodeHandler responseCodeHandler)
            throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException {
        Response response = getServiceConnection(urlFragment, method, body, headers);

        ResponseBody responseBody = response.body();
        try {
            responseCodeHandler.handle(response.code(), responseBody);

            return validateServiceResponse(response);
        } catch (NonSuccessfulResponseCodeException | PushNetworkException | MalformedResponseException e) {
            if (responseBody != null) {
                responseBody.close();
            }
            throw e;
        }
    }

    private Response validateServiceResponse(Response response)
            throws NonSuccessfulResponseCodeException, PushNetworkException, MalformedResponseException {
        int responseCode = response.code();
        String responseMessage = response.message();
        switch (responseCode) {
            case 413:
            case 429: {
                long retryAfterLong = Util.parseLong(response.header("Retry-After"), -1);
                Optional<Long> retryAfter = retryAfterLong != -1 ? Optional.of(TimeUnit.SECONDS.toMillis(retryAfterLong)) : Optional.empty();
                throw new RateLimitException(responseCode, "Rate limit exceeded: " + responseCode, retryAfter);
            }
            case 401:
            case 403:
                throw new AuthorizationFailedException(responseCode, "Authorization failed!");
            case 404:
                SocketResponse socketResponse = readBodyJson(response.body(), SocketResponse.class);
                if (socketResponse != null && (socketResponse.getStatus() == 10105 || socketResponse.getStatus() == 10110)) {
                    throw new AccountOfflineException(socketResponse.getStatus(), socketResponse.getReason());
                } else {
                    throw new NotFoundException("At least one unregistered user in message send.");
                }
            case 409:
                MismatchedDevices mismatchedDevices = readResponseJson(response, MismatchedDevices.class);

                throw new MismatchedDevicesException(mismatchedDevices);
            case 410:
                StaleDevices staleDevices = readResponseJson(response, StaleDevices.class);

                throw new StaleDevicesException(staleDevices);
            case 411:
                DeviceLimit deviceLimit = readResponseJson(response, DeviceLimit.class);

                throw new DeviceLimitExceededException(deviceLimit);
            case 417:
                throw new ExpectationFailedException();
            case 423:
                RegistrationLockFailure accountLockFailure = readResponseJson(response, RegistrationLockFailure.class);
                AuthCredentials credentials = accountLockFailure.backupCredentials;
                String basicStorageCredentials = credentials != null ? credentials.asBasic() : null;

                throw new LockedException(accountLockFailure.length,
                        accountLockFailure.timeRemaining,
                        basicStorageCredentials);
            case 428:
                ProofRequiredResponse proofRequiredResponse = readResponseJson(response, ProofRequiredResponse.class);
                String retryAfterRaw = response.header("Retry-After");
                long retryAfter = Util.parseInt(retryAfterRaw, -1);

                throw new ProofRequiredException(proofRequiredResponse, retryAfter);

            case 499:
                throw new DeprecatedVersionException();

            case 508:
                throw new ServerRejectedException();
        }

        if (responseCode != 200 && responseCode != 202 && responseCode != 204) {
            throw new NonSuccessfulResponseCodeException(responseCode, "Bad response: " + responseCode + " " + responseMessage);
        }

        return response;
    }

    private Response getServiceConnection(String urlFragment,
                                          String method,
                                          RequestBody body,
                                          Map<String, String> headers)
            throws PushNetworkException {
        try {
            OkHttpClient okHttpClient = buildOkHttpClient();
            Call call = okHttpClient.newCall(buildServiceRequest(urlFragment, method, body, headers));

            synchronized (connections) {
                connections.add(call);
            }

            try {
                return call.execute();
            } finally {
                synchronized (connections) {
                    connections.remove(call);
                }
            }
        } catch (IOException e) {
            throw new PushNetworkException(e);
        }
    }

    private OkHttpClient buildOkHttpClient() {
        ServiceConnectionHolder connectionHolder = (ServiceConnectionHolder) getRandom(serviceClients, random);
        OkHttpClient baseClient = connectionHolder.getClient();
        Optional<Pair<SSLSocketFactory, X509TrustManager>> socketFactory = Optional.of(createTrustAllTlsSocketFactory());
        Pair<SSLSocketFactory, X509TrustManager> pair = socketFactory.get();
        return baseClient.newBuilder()
                .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(automaticNetworkRetry)
                .sslSocketFactory(new Tls12SocketFactory(pair.first()), pair.second())
                .build();
    }

    private Pair<SSLSocketFactory, X509TrustManager> createTrustAllTlsSocketFactory() {
        try {
            SSLContext context = SSLContext.getInstance("TLSv1.2");
            TrustAllSSLSocketFactory.TrustAllManager trustAllManager =
                    new TrustAllSSLSocketFactory.TrustAllManager();
            TrustAllSSLSocketFactory.TrustAllManager[] trustAllManagers = {trustAllManager};
            context.init(null, trustAllManagers, null);
            return new Pair<>(context.getSocketFactory(), trustAllManager);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Request buildServiceRequest(String urlFragment,
                                        String method,
                                        RequestBody body,
                                        Map<String, String> headers) {

        ServiceConnectionHolder connectionHolder = (ServiceConnectionHolder) getRandom(serviceClients, random);

        L.d(() -> "Push service URL: " + connectionHolder.getUrl());
        L.d(() -> "Opening URL: " + String.format("%s%s", connectionHolder.getUrl(), urlFragment));

        Request.Builder request = new Request.Builder();
        String host = null;
        try {
            URL url = new URL(connectionHolder.getUrl().replace("wss", "https"));
            if (url.getPath().contains("/chat")) {
                host = url.getHost() + "/chat";
            } else {
                host = url.getHost();
            }
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        String url = host == null ? connectionHolder.getUrl() : "https://" + host;
        request.url(String.format("%s%s", url, urlFragment));
//    request.url("https://chativec.test.difft.org/v2/keys/+70986289617/1");
        request.method(method, body);

        if (connectionHolder.getHostHeader().isPresent()) {
            for (Map.Entry<String, String> header : connectionHolder.getHostHeader().get().entrySet()) {
                request.addHeader(header.getKey(), header.getValue());
            }
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request.addHeader(header.getKey(), header.getValue());
        }

        return request.build();
    }


    private ConnectionHolder[] clientsFor(ClientSet clientSet) {
        switch (clientSet) {
            case ContactDiscovery:
                return contactDiscoveryClients;
            case KeyBackup:
                return keyBackupServiceClients;
            default:
                throw new AssertionError("Unknown attestation purpose");
        }
    }

    Response makeRequest(ClientSet clientSet, String authorization, List<String> cookies, String path, String method, String body)
            throws PushNetworkException, NonSuccessfulResponseCodeException {
        ConnectionHolder connectionHolder = getRandom(clientsFor(clientSet), random);

        return makeRequest(connectionHolder, authorization, cookies, path, method, body);
    }

    private Response makeRequest(ConnectionHolder connectionHolder, String authorization, List<String> cookies, String path, String method, String body)
            throws PushNetworkException, NonSuccessfulResponseCodeException {
        OkHttpClient okHttpClient = connectionHolder.getClient()
                .newBuilder()
                .connectTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
                .build();

        Request.Builder request = new Request.Builder().url(connectionHolder.getUrl() + path);

        if (body != null) {
            request.method(method, RequestBody.create(MediaType.parse("application/json"), body));
        } else {
            request.method(method, null);
        }

        if (connectionHolder.getHostHeader().isPresent()) {
            for (Map.Entry<String, String> header : connectionHolder.getHostHeader().get().entrySet()) {
                request.addHeader(header.getKey(), header.getValue());
            }
        }

        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }

        if (cookies != null && !cookies.isEmpty()) {
            request.addHeader("Cookie", Util.join(cookies, "; "));
        }

        Call call = okHttpClient.newCall(request.build());

        synchronized (connections) {
            connections.add(call);
        }

        Response response;

        try {
            response = call.execute();

            if (response.isSuccessful()) {
                return response;
            }
        } catch (IOException e) {
            throw new PushNetworkException(e);
        } finally {
            synchronized (connections) {
                connections.remove(call);
            }
        }

        switch (response.code()) {
            case 401:
            case 403:
                throw new AuthorizationFailedException(response.code(), "Authorization failed!");
            case 409:
                throw new RemoteAttestationResponseExpiredException("Remote attestation response expired");
            case 429:
                throw new RateLimitException(response.code(), "Rate limit exceeded: " + response.code());
        }

        throw new NonSuccessfulResponseCodeException(response.code(), "Response: " + response);
    }


    private ServiceConnectionHolder[] createServiceConnectionHolders(SignalUrl[] urls,
                                                                     List<Interceptor> interceptors,
                                                                     Optional<Dns> dns,
                                                                     Optional<SignalProxy> proxy) {
        List<ServiceConnectionHolder> serviceConnectionHolders = new LinkedList<>();

        for (SignalUrl url : urls) {
            serviceConnectionHolders.add(new ServiceConnectionHolder(createConnectionClient(url, interceptors, dns, proxy),
                    url.getUrl(), url.getHostHeader()));
        }

        return serviceConnectionHolders.toArray(new ServiceConnectionHolder[0]);
    }

    private static ConnectionHolder[] createConnectionHolders(SignalUrl[] urls, List<Interceptor> interceptors, Optional<Dns> dns, Optional<SignalProxy> proxy) {
        List<ConnectionHolder> connectionHolders = new LinkedList<>();

        for (SignalUrl url : urls) {
            connectionHolders.add(new ConnectionHolder(createConnectionClient(url, interceptors, dns, proxy), url.getUrl(), url.getHostHeader()));
        }

        return connectionHolders.toArray(new ConnectionHolder[0]);
    }

    private static OkHttpClient createConnectionClient(SignalUrl url, List<Interceptor> interceptors, Optional<Dns> dns, Optional<SignalProxy> proxy) {
        try {
            TrustManager[] trustManagers = BlacklistingTrustManager.createFor(url.getTrustStore());

            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(null, trustManagers, null);

            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .sslSocketFactory(new Tls12SocketFactory(context.getSocketFactory()), (X509TrustManager) trustManagers[0])
                    .connectionSpecs(url.getConnectionSpecs().orElse(Util.immutableList(ConnectionSpec.RESTRICTED_TLS)))
                    .dns(dns.orElse(Dns.SYSTEM));

            if (proxy.isPresent()) {
                builder.socketFactory(new TlsProxySocketFactory(proxy.get().getHost(), proxy.get().getPort(), dns));
            }

            builder.sslSocketFactory(new Tls12SocketFactory(context.getSocketFactory()), (X509TrustManager) trustManagers[0])
                    .connectionSpecs(url.getConnectionSpecs().orElse(Util.immutableList(ConnectionSpec.RESTRICTED_TLS)))
                    .build();

            builder.connectionPool(new ConnectionPool(5, 45, TimeUnit.SECONDS));

            for (Interceptor interceptor : interceptors) {
                builder.addInterceptor(interceptor);
            }

            return builder.build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new AssertionError(e);
        }
    }

    private ConnectionHolder getRandom(ConnectionHolder[] connections, SecureRandom random) {
        return connections[random.nextInt(connections.length)];
    }

    /**
     * Converts {@link IOException} on body reading to {@link PushNetworkException}.
     */
    private static String readBodyString(ResponseBody body) throws PushNetworkException {
        if (body == null) {
            throw new PushNetworkException("No body!");
        }

        try {
            return body.string();
        } catch (IOException e) {
            throw new PushNetworkException(e);
        }
    }

    /**
     * Converts {@link IOException} on body reading to {@link PushNetworkException}.
     * {@link IOException} during json parsing is converted to a {@link MalformedResponseException}
     */
    private static <T> T readBodyJson(ResponseBody body, Class<T> clazz) throws PushNetworkException, MalformedResponseException {
        String json = readBodyString(body);
        try {
            return JsonUtil.fromJson(json, clazz);
        } catch (JsonProcessingException e) {
            L.w(e::toString);
            throw new MalformedResponseException("Unable to parse entity", e);
        } catch (IOException e) {
            throw new PushNetworkException(e);
        }
    }

    /**
     * Converts {@link IOException} on body reading to {@link PushNetworkException}.
     * {@link IOException} during json parsing is converted to a {@link NonSuccessfulResponseCodeException} with response code detail.
     */
    private static <T> T readResponseJson(Response response, Class<T> clazz)
            throws PushNetworkException, MalformedResponseException {
        return readBodyJson(response.body(), clazz);
    }

    public static class RegistrationLockFailure {
        @JsonProperty
        public int length;

        @JsonProperty
        public long timeRemaining;

        @JsonProperty
        public AuthCredentials backupCredentials;
    }

    private static class ConnectionHolder {

        private final OkHttpClient client;
        private final String url;
        private final Optional<Map<String, String>> hostHeader;

        private ConnectionHolder(OkHttpClient client, String url, Optional<Map<String, String>> hostHeader) {
            this.client = client;
            this.url = url;
            this.hostHeader = hostHeader;
        }

        OkHttpClient getClient() {
            return client;
        }

        public String getUrl() {
            return url;
        }

        Optional<Map<String, String>> getHostHeader() {
            return hostHeader;
        }
    }

    private static class ServiceConnectionHolder extends ConnectionHolder {

        private ServiceConnectionHolder(OkHttpClient identifiedClient, String url, Optional<Map<String, String>> hostHeader) {
            super(identifiedClient, url, hostHeader);
        }
    }

    public interface ResponseCodeHandler {
        void handle(int responseCode, ResponseBody body) throws NonSuccessfulResponseCodeException, PushNetworkException;

        default void handle(int responseCode, ResponseBody body, Function<String, String> getHeader) throws NonSuccessfulResponseCodeException, PushNetworkException {
            handle(responseCode, body);
        }
    }

    private static class EmptyResponseCodeHandler implements ResponseCodeHandler {
        @Override
        public void handle(int responseCode, ResponseBody body) {
        }
    }

    public enum ClientSet {ContactDiscovery, KeyBackup}
}
