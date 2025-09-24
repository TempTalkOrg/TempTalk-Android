package org.thoughtcrime.securesms.util.task;

import android.content.Context;
import android.os.AsyncTask;

import androidx.annotation.CallSuper;

import com.kongzue.dialogx.dialogs.WaitDialog;

import java.lang.ref.WeakReference;

public abstract class ProgressDialogAsyncTask<Params, Progress, Result> extends AsyncTask<Params, Progress, Result> {

    private final WeakReference<Context> contextReference;
    private WaitDialog progress;
    private final String message;

    public ProgressDialogAsyncTask(Context context, String message) {
        super();
        this.contextReference = new WeakReference<>(context);
        this.message = message;
    }

    @Override
    protected void onPreExecute() {
//        final Context context = contextReference.get();
//        if (context != null) progress = WaitDialog.show(message);
    }

    @CallSuper
    @Override
    protected void onPostExecute(Result result) {
        if (progress != null) progress.doDismiss();
    }

    protected Context getContext() {
        return contextReference.get();
    }
}

