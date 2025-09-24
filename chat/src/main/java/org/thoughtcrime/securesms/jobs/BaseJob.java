package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.difft.android.base.log.lumberjack.L;

import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobLogger;
import org.thoughtcrime.securesms.jobmanager.JobManager.Chain;
import org.thoughtcrime.securesms.jobmanager.impl.BackoffUtil;

import java.util.concurrent.TimeUnit;

public abstract class BaseJob extends Job {

    private static final String TAG = L.INSTANCE.tag(BaseJob.class);

    private Data outputData;

    public BaseJob(@NonNull Parameters parameters) {
        super(parameters);
    }

    @Override
    public @NonNull Result run() {
        try {
            onRun();
            return Result.success(outputData);
        } catch (Exception e) {
            if (onShouldRetry(e)) {
                L.i(() -> JobLogger.format(this, "Encountered a retryable exception.") + e);
                return Result.retry(getNextRunAttemptBackoff(getRunAttempt() + 1, e));
            } else {
                L.w(() -> JobLogger.format(this, "Encountered a failing exception.") + e);
                return Result.failure();
            }
        }
    }

    /**
     * Should return how long you'd like to wait until the next retry, given the attempt count and
     * exception that caused the retry. The attempt count is the number of attempts that have been
     * made already, so this value will be at least 1.
     * <p>
     * There is a sane default implementation here that uses exponential backoff, but jobs can
     * override this behavior to define custom backoff behavior.
     */
    public long getNextRunAttemptBackoff(int pastAttemptCount, @NonNull Exception exception) {
        return BackoffUtil.exponentialBackoff(pastAttemptCount, TimeUnit.SECONDS.toMillis(60));
    }

    protected abstract void onRun() throws Exception;

    protected abstract boolean onShouldRetry(@NonNull Exception e);


    /**
     * If this job is part of a {@link Chain}, data set here will be passed as input data to the next
     * job(s) in the chain.
     */
    protected void setOutputData(@Nullable Data outputData) {
        this.outputData = outputData;
    }

    protected void log(@NonNull String tag, @NonNull String message) {
        L.i(() -> JobLogger.format(this, message));
    }

    protected void log(@NonNull String tag, @NonNull String extra, @NonNull String message) {
        L.i(() -> JobLogger.format(this, extra, message));
    }

    protected void warn(@NonNull String tag, @NonNull String message) {
        warn(tag, "", message, null);
    }

    protected void warn(@NonNull String tag, @NonNull Object extra, @NonNull String message) {
        warn(tag, extra.toString(), message, null);
    }

    protected void warn(@NonNull String tag, @Nullable Throwable t) {
        warn(tag, "", t);
    }

    protected void warn(@NonNull String tag, @NonNull String message, @Nullable Throwable t) {
        warn(tag, "", message, t);
    }

    protected void warn(@NonNull String tag, @NonNull String extra, @NonNull String message, @Nullable Throwable t) {
        L.w(() -> JobLogger.format(this, extra, message) + t);
    }
}
