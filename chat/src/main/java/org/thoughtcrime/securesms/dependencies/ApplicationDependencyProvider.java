package org.thoughtcrime.securesms.dependencies;

import android.app.Application;

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.database.JobDatabase;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobmanager.impl.FactoryJobPredicate;
import org.thoughtcrime.securesms.jobmanager.impl.JsonDataSerializer;
import org.thoughtcrime.securesms.jobs.DownloadAttachmentJob;
import org.thoughtcrime.securesms.jobs.FastJobStorage;
import org.thoughtcrime.securesms.jobs.JobManagerFactories;
import org.thoughtcrime.securesms.video.exo.SimpleExoPlayerPool;
import com.difft.android.websocket.api.SignalServiceAccountManager;
import com.difft.android.websocket.internal.configuration.SignalServiceConfiguration;

/**
 * Implementation of {@link ApplicationDependencies.Provider} that provides real app dependencies.
 */
public class ApplicationDependencyProvider implements ApplicationDependencies.Provider {

    private final Application context;

    public ApplicationDependencyProvider(@NonNull Application context) {
        this.context = context;
    }


    @NonNull
    @Override
    public JobManager provideJobManager() {
        JobManager.Configuration config = new JobManager.Configuration.Builder()
                .setDataSerializer(new JsonDataSerializer())
                .setJobFactories(JobManagerFactories.getJobFactories(context))
                .setConstraintFactories(JobManagerFactories.getConstraintFactories(context))
                .setConstraintObservers(JobManagerFactories.getConstraintObservers(context))
                .setJobStorage(new FastJobStorage(JobDatabase.getInstance(context)))
                //.setJobMigrator(new JobMigrator(TextSecurePreferences.getJobManagerVersion(context), JobManager.CURRENT_VERSION, JobManagerFactories.getJobMigrations(context)))
                .addReservedJobRunner(new FactoryJobPredicate(DownloadAttachmentJob.KEY))
                .build();
        return new JobManager(context, config);
    }

    @Override
    public @NonNull
    SignalServiceAccountManager provideSignalServiceAccountManager(@NonNull SignalServiceConfiguration signalServiceConfiguration) {
        return new SignalServiceAccountManager(signalServiceConfiguration, true);
    }


    @NonNull
    @Override
    public SimpleExoPlayerPool provideExoPlayerPool() {
        return new SimpleExoPlayerPool(context);
    }
}
