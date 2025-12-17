package org.thoughtcrime.securesms.jobs;

import android.app.Application;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.jobmanager.Constraint;
import org.thoughtcrime.securesms.jobmanager.ConstraintObserver;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.ChargingConstraint;
import org.thoughtcrime.securesms.jobmanager.impl.ChargingConstraintObserver;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraintObserver;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JobManagerFactories {

    public static Map<String, Job.Factory> getJobFactories(@NonNull Application application) {
        return new HashMap<>() {{
            put(TestJob.KEY, new TestJob.Factory());
            put(PushTextSendJob.KEY, new PushTextSendJob.Factory());
            put(DownloadAttachmentJob.KEY, new DownloadAttachmentJob.Factory());
            put(PushReadReceiptSendJob.KEY, new PushReadReceiptSendJob.Factory());
        }};
    }

    public static Map<String, Constraint.Factory> getConstraintFactories(@NonNull Application application) {
        return new HashMap<>() {{
            put(ChargingConstraint.KEY, new ChargingConstraint.Factory());
            put(NetworkConstraint.KEY, new NetworkConstraint.Factory(application));
        }};
    }

    public static List<ConstraintObserver> getConstraintObservers(@NonNull Application application) {
        return Arrays.asList(new ChargingConstraintObserver(application),
                new NetworkConstraintObserver(application));
    }

}
