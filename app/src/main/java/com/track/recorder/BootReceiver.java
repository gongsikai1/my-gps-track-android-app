package com.track.recorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机 / 应用更新 / 闹钟复活 时把记录任务拉起来。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "onReceive " + action);

        Prefs prefs = new Prefs(context);
        boolean revive = TrackService.ACTION_REVIVE.equals(action);
        if (!revive && !prefs.isAutoStart()) {
            return;
        }
        if (!prefs.isTracking() && !revive) {
            return;     // 用户没有主动开启过记录，不自动启动
        }

        try {
            Intent svc = new Intent(context, TrackService.class).setAction(TrackService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            Log.w(TAG, "restart service failed", e);
        }
    }
}
