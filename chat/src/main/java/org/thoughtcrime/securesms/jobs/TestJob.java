package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;

import com.difft.android.base.log.lumberjack.L;

import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;

import java.util.concurrent.TimeUnit;

public class TestJob extends BaseJob {
    public static final String KEY = "TestJob";

    public TestJob() {
        this(new Job.Parameters.Builder()
                .addConstraint(NetworkConstraint.KEY)
                .setLifespan(TimeUnit.DAYS.toMillis(1))
                .setMaxAttempts(Parameters.UNLIMITED)
                .build());
    }

    public TestJob(Parameters parameters) {
        super(parameters);
    }

    @NonNull
    @Override
    public Data serialize() {
        return new Data.Builder()
                .build();
    }

    @NonNull
    @Override
    public String getFactoryKey() {
        return KEY;
    }

    @Override
    public void onFailure() {

    }

    @Override
    protected void onRun() throws Exception {
        L.i(() -> "TestJob onRun() " + hashCode());
    }

    @Override
    protected boolean onShouldRetry(@NonNull Exception e) {
        return false;
    }

    public static final class Factory implements Job.Factory<TestJob> {
        @Override
        public @NonNull TestJob create(@NonNull Parameters parameters, @NonNull org.thoughtcrime.securesms.jobmanager.Data data) {
            return new TestJob(parameters);
        }
    }
}
