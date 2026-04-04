
package com.difft.android.websocket.api;


import com.google.protobuf.ByteString;

import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import com.difft.android.websocket.internal.configuration.ServiceConfig;
import com.difft.android.websocket.internal.crypto.PrimaryProvisioningCipher;
import org.whispersystems.signalservice.internal.push.ProvisioningProtos;
import com.difft.android.websocket.internal.push.PushServiceSocket;

import java.io.IOException;

import okhttp3.Response;

/**
 * The main interface for creating, registering, and
 * managing a Signal Service account.
 *
 * @author Moxie Marlinspike
 */
public class SignalServiceAccountManager {

    private final PushServiceSocket pushServiceSocket;


    public SignalServiceAccountManager(ServiceConfig config, boolean automaticNetworkRetry) {
        this.pushServiceSocket = new PushServiceSocket(config, automaticNetworkRetry);
    }

    public String getNewDeviceVerificationCode() throws IOException {
        return this.pushServiceSocket.getNewDeviceVerificationCode();
    }

    public void addDevice(String deviceIdentifier,
                          ECPublicKey deviceKey,
                          IdentityKeyPair aciIdentityKeyPair,
                          String code,
                          String id)
            throws InvalidKeyException, IOException {

        PrimaryProvisioningCipher cipher = new PrimaryProvisioningCipher(deviceKey);
        ProvisioningProtos.ProvisionMessage.Builder message = ProvisioningProtos.ProvisionMessage.newBuilder()
                .setAciIdentityKeyPrivate(ByteString.copyFrom(aciIdentityKeyPair.getPrivateKey().serialize()))
                .setNumber(id)
                .setProvisioningCode(code);

        byte[] ciphertext = cipher.encrypt(message.build());
        this.pushServiceSocket.sendProvisioningMessage(deviceIdentifier, ciphertext);
    }

//  public List<DeviceInfo> getDevices() throws IOException {
//    return this.pushServiceSocket.getDevices();
//  }

    public Response getDevices() throws IOException {
        return this.pushServiceSocket.getDevicesResponse();
    }
}
