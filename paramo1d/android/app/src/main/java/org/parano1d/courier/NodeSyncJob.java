package org.parano1d.courier;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import org.json.JSONObject;
import java.io.File;

/**
 * Like iOS 166 background node refresh (the iOS node-sync task): while the app is closed,
 * Android may run the on-device node when the phone is charging on Wi-Fi, so the verified chain and the
 * token/NFT State are already close to current when the app opens. Public chain data only: no keys,
 * nothing is signed or sent. The app schedules it while it has a wallet; turning it off cancels it.
 */
public final class NodeSyncJob extends JobService {
    private static final int JOB_ID = 166;
    private volatile Thread worker;

    static void schedule(Context context, boolean enabled) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        if (!enabled) { scheduler.cancel(JOB_ID); return; }
        if (scheduler.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, NodeSyncJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setRequiresCharging(true)
            .setPeriodic(3 * 60 * 60 * 1000L) // the system decides the exact time
            .build();
        scheduler.schedule(job);
    }

    @Override public boolean onStartJob(JobParameters params) {
        File artifacts = new File(getFilesDir(), "artifacts"), chain = new File(getFilesDir(), "chain");
        if (!new File(artifacts, "legacy").isFile()) return false; // verification parameters not installed yet
        worker = new Thread(null, () -> {
            boolean retry = false;
            try {
                JSONObject result = new JSONObject(NativeCore.call(new JSONObject().put("op", "sync_once")
                    .put("artifacts", artifacts.getAbsolutePath()).put("data_dir", chain.getAbsolutePath()).toString()));
                retry = !result.optBoolean("ok") && result.optBoolean("network_unavailable");
            } catch (Throwable ignored) { }
            jobFinished(params, retry);
        }, "parano1d-node-sync", 64L << 20);
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        // The node's own transfer is resumable; the system stops us when charging or Wi-Fi ends.
        return true;
    }
}
