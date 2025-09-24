package org.thoughtcrime.securesms.jobmanager;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;
import com.difft.android.base.log.lumberjack.L;

import util.concurrent.TTExecutors;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;

import java.util.List;
import java.util.Locale;

@RequiresApi(26)
public final class JobSchedulerScheduler implements Scheduler {

    private static final String TAG = L.INSTANCE.tag(JobSchedulerScheduler.class);

    private final Application application;

    JobSchedulerScheduler(@NonNull Application application) {
        this.application = application;
    }

    @RequiresApi(26)
    @Override
    public void schedule(long delay, @NonNull List<Constraint> constraints) {
        TTExecutors.BOUNDED.execute(() -> {
            JobScheduler jobScheduler = application.getSystemService(JobScheduler.class);

            String constraintNames = constraints.isEmpty() ? ""
                    : Stream.of(constraints)
                    .map(Constraint::getJobSchedulerKeyPart)
                    .withoutNulls()
                    .sorted()
                    .collect(Collectors.joining("-"));

            int jobId = constraintNames.hashCode();

            if (jobScheduler.getPendingJob(jobId) != null) {
                return;
            }
            L.i(() -> String.format(Locale.US, "JobScheduler enqueue of %s (%d)", constraintNames, jobId));

            JobInfo.Builder jobInfoBuilder = new JobInfo.Builder(jobId, new ComponentName(application, SystemService.class))
                    .setMinimumLatency(delay)
                    .setPersisted(true);

            for (Constraint constraint : constraints) {
                constraint.applyToJobInfo(jobInfoBuilder);
            }

            jobScheduler.schedule(jobInfoBuilder.build());
        });
    }

    @SuppressLint("SpecifyJobSchedulerIdRange")
    @RequiresApi(api = 26)
    public static class SystemService extends JobService {

        @Override
        public boolean onStartJob(JobParameters params) {
            JobManager jobManager = ApplicationDependencies.getJobManager();

            L.i(() -> "Waking due to job: " + params.getJobId());

            jobManager.addOnEmptyQueueListener(new JobManager.EmptyQueueListener() {
                @Override
                public void onQueueEmpty() {
                    jobManager.removeOnEmptyQueueListener(this);
                    jobFinished(params, false);
                }
            });

            jobManager.wakeUp();

            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return false;
        }
    }
}
