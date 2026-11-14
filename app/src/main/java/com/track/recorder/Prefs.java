package com.track.recorder;

import android.content.Context;
import android.content.SharedPreferences;

/** 简单配置存储 */
public class Prefs {
    private static final String NAME = "track_prefs";
    private static final String K_AUTO_START = "auto_start";      // 开机 / 重启后自动恢复记录
    private static final String K_TRACKING = "tracking";          // 当前是否应在记录（进程被杀后据此恢复）
    private static final String K_SESSION_START = "session_start";// 本次记录开始时间
    private static final String K_TOTAL_DIST = "total_dist";      // 累计里程（米）
    private static final String K_LAST_BATT = "last_batt";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public boolean isAutoStart() {
        return sp.getBoolean(K_AUTO_START, true);
    }

    public void setAutoStart(boolean v) {
        sp.edit().putBoolean(K_AUTO_START, v).apply();
    }

    public boolean isTracking() {
        return sp.getBoolean(K_TRACKING, false);
    }

    public void setTracking(boolean v) {
        sp.edit().putBoolean(K_TRACKING, v).apply();
    }

    public long getSessionStart() {
        return sp.getLong(K_SESSION_START, 0);
    }

    public void setSessionStart(long v) {
        sp.edit().putLong(K_SESSION_START, v).apply();
    }

    public void setLastBatt(int v) {
        sp.edit().putInt(K_LAST_BATT, v).apply();
    }

    public int getLastBatt() {
        return sp.getInt(K_LAST_BATT, -1);
    }
}
