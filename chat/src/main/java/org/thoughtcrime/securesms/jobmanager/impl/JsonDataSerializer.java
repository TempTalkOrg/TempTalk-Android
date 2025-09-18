package org.thoughtcrime.securesms.jobmanager.impl;

import androidx.annotation.NonNull;

import com.difft.android.base.log.lumberjack.L;

import util.logging.Log;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.util.JsonUtils;

import java.io.IOException;

public class JsonDataSerializer implements Data.Serializer {

    private static final String TAG = L.INSTANCE.tag(JsonDataSerializer.class);

    @Override
    public @NonNull String serialize(@NonNull Data data) {
        try {
            return JsonUtils.toJson(data);
        } catch (IOException e) {
            L.e(() -> "Failed to serialize to JSON." + e);
            throw new AssertionError(e);
        }
    }

    @Override
    public @NonNull Data deserialize(@NonNull String serialized) {
        try {
            return JsonUtils.fromJson(serialized, Data.class);
        } catch (IOException e) {
            L.e(() -> "Failed to deserialize JSON." + e);
            throw new AssertionError(e);
        }
    }
}
