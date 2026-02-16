package org.thoughtcrime.securesms.crypto;


import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.difft.android.base.log.lumberjack.L;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import org.thoughtcrime.securesms.util.JsonUtils;

import java.io.IOException;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class KeyStoreHelper {

    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "SignalSecret";

    @RequiresApi(Build.VERSION_CODES.M)
    public static SealedData seal(@NonNull byte[] input) {
        SecretKey secretKey = getOrCreateKeyStoreEntry();

        try {
            if (null != secretKey) {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, secretKey);

                byte[] iv = cipher.getIV();
                byte[] data = cipher.doFinal(input);

                return new SealedData(iv, data);
            } else {
                return null;
            }
        } catch (Exception e) {
//            throw new AssertionError(e);
            L.w(e, () -> "[KeyStoreHelper] seal error: ");
        }
        return null;
    }

    @RequiresApi(Build.VERSION_CODES.M)
    public static byte[] unseal(@NonNull SealedData sealedData) {
        SecretKey secretKey = getKeyStoreEntry();
        try {
            if (secretKey != null) {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, sealedData.iv));
                return cipher.doFinal(sealedData.data);
            } else {
                return null;
            }
        } catch (Exception e) {
            L.w(e, () -> "[KeyStoreHelper] unseal error: ");
            //      throw new AssertionError(e);
        }
        return null;
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private static SecretKey getOrCreateKeyStoreEntry() {
        if (hasKeyStoreEntry()) return getKeyStoreEntry();
        else return createKeyStoreEntry();
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private static SecretKey createKeyStoreEntry() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
            KeyGenParameterSpec keyGenParameterSpec = new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build();

            keyGenerator.init(keyGenParameterSpec);

            return keyGenerator.generateKey();
        } catch (Exception e) {
            L.w(e, () -> "[KeyStoreHelper] createKeyStoreEntry error: ");
//            throw new AssertionError(e);
        }
        return null;
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private static SecretKey getKeyStoreEntry() {
        KeyStore keyStore = getKeyStore();

        try {
            // Attempt 1
            return getSecretKey(keyStore);
        } catch (Exception e) {
            L.w(e, () -> "[KeyStoreHelper] getKeyStoreEntry attempt1 error: ");
            try {
                // Attempt 2
                return getSecretKey(keyStore);
            } catch (Exception e2) {
                L.w(e2, () -> "[KeyStoreHelper] getKeyStoreEntry attempt2 error: ");
//                throw new AssertionError(e2);
            }
        }
        return null;
    }

    private static SecretKey getSecretKey(KeyStore keyStore) throws UnrecoverableKeyException {
        try {
            if (null != keyStore) {
                KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
                return entry.getSecretKey();
//                return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
            }
            return null;
        }
//        catch (UnrecoverableKeyException e) {
//            L.w(e::toString);
////            throw e;
//        }
        catch (Exception e) {
            L.w(e, () -> "[KeyStoreHelper] getSecretKey error: ");
//            throw new AssertionError(e);
        }

        return null;
    }

    private static KeyStore getKeyStore() {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            return keyStore;
        } catch (Exception e) {
            L.w(e, () -> "[KeyStoreHelper] getKeyStore error: ");
//            throw new AssertionError(e);
        }
        return null;
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private static boolean hasKeyStoreEntry() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEY_STORE);
            ks.load(null);

            return ks.containsAlias(KEY_ALIAS) && ks.entryInstanceOf(KEY_ALIAS, KeyStore.SecretKeyEntry.class);
        } catch (Exception e) {
//            throw new AssertionError(e);
            L.w(e, () -> "[KeyStoreHelper] hasKeyStoreEntry error: ");
        }
        return false;
    }

    public static class SealedData {

        @SuppressWarnings("unused")
        private static final String TAG = L.INSTANCE.tag(SealedData.class);

        @JsonProperty
        @JsonSerialize(using = ByteArraySerializer.class)
        @JsonDeserialize(using = ByteArrayDeserializer.class)
        private byte[] iv;

        @JsonProperty
        @JsonSerialize(using = ByteArraySerializer.class)
        @JsonDeserialize(using = ByteArrayDeserializer.class)
        private byte[] data;

        SealedData(@NonNull byte[] iv, @NonNull byte[] data) {
            this.iv = iv;
            this.data = data;
        }

        @SuppressWarnings("unused")
        public SealedData() {
        }

        public String serialize() {
            try {
                return JsonUtils.toJson(this);
            } catch (IOException e) {
//                throw new AssertionError(e);
                L.w(e, () -> "[KeyStoreHelper.SealedData] serialize error: ");
            }
            return null;
        }

        public static SealedData fromString(@NonNull String value) {
            try {
                return JsonUtils.fromJson(value, SealedData.class);
            } catch (IOException e) {
                L.w(e, () -> "[KeyStoreHelper.SealedData] fromString error: ");
//                throw new AssertionError(e);
            }
            return null;
        }

        private static class ByteArraySerializer extends JsonSerializer<byte[]> {
            @Override
            public void serialize(byte[] value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(Base64.encodeToString(value, Base64.NO_WRAP | Base64.NO_PADDING));
            }
        }

        private static class ByteArrayDeserializer extends JsonDeserializer<byte[]> {

            @Override
            public byte[] deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return Base64.decode(p.getValueAsString(), Base64.NO_WRAP | Base64.NO_PADDING);
            }
        }

    }

}
