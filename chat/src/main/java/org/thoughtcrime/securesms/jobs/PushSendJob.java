package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;

import com.difft.android.base.log.lumberjack.L;

import org.thoughtcrime.securesms.jobmanager.impl.BackoffUtil;

import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException;
import com.difft.android.websocket.api.push.exceptions.ProofRequiredException;
import com.difft.android.websocket.api.push.exceptions.ServerRejectedException;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public abstract class PushSendJob extends SendJob {

    private static final String TAG = L.INSTANCE.tag(PushSendJob.class);

    protected PushSendJob(Parameters parameters) {
        super(parameters);
    }

    @Override
    protected final void onSend() throws Exception {
        onPushSend();
    }

    @Override
    public void onRetry() {
        L.i(() -> "onRetry()");

        if (getRunAttempt() > 1) {
            L.i(() -> "Scheduling service outage detection job.");
        }
    }

    @Override
    public boolean onShouldRetry(@NonNull Exception exception) {
        if (exception instanceof ServerRejectedException) {
            return false;
        }

        return exception instanceof IOException;
    }

    @Override
    public long getNextRunAttemptBackoff(int pastAttemptCount, @NonNull Exception exception) {
        if (exception instanceof ProofRequiredException) {
            long backoff = ((ProofRequiredException) exception).getRetryAfterSeconds();
            warn(TAG, "[Proof Required] Retry-After is " + backoff + " seconds.");
            if (backoff >= 0) {
                return TimeUnit.SECONDS.toMillis(backoff);
            }
        } else if (exception instanceof NonSuccessfulResponseCodeException) {
            if (((NonSuccessfulResponseCodeException) exception).is5xx()) {
                return BackoffUtil.exponentialBackoff(pastAttemptCount, 6000);
            }
        }

        return super.getNextRunAttemptBackoff(pastAttemptCount, exception);
    }

    protected abstract void onPushSend() throws Exception;
}
