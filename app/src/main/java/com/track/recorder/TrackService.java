package com.track.recorder;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * 轨迹记录前台服务。
 *
 * 关键设计：
 * 1. startForeground + foregroundServiceType=location：系统认为这是用户可见的定位任务，
 *    不会像普通后台进程那样在息屏后被冻结 / 回收。
 * 2. PARTIAL_WAKE_LOCK：息屏后保持 CPU 唤醒，保证 10 秒定时器不断。
 * 3. 同时订阅 GPS 与 NETWORK 两个 provider，每 10 秒取一个新定位点写入 SQLite。
 * 4. START_STICKY + 开机广播 + AlarmManager 复活：被 ROM 杀掉后尽量自动拉起。
 */
public class TrackService extends Service implements LocationListener {

    private static final String TAG = "TrackService";

    public static final String ACTION_START = "com.track.recorder.action.START";
    public static final String ACTION_STOP = "com.track.recorder.action.STOP";
    public static final String ACTION_TICK = "com.track.recorder.action.TICK";   // 心跳，强制补一个点
    public static final String ACTION_REVIVE = "com.track.recorder.action.REVIVE";

    // 界面刷新广播
    public static final String ACTION_UPDATE = "com.track.recorder.action.UPDATE";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_DIST = "dist";
    public static final String EXTRA_START = "startTs";
    public static final String EXTRA_ACC = "acc";
    public static final String EXTRA_LAST_TS = "lastTs";
    public static final String EXTRA_PROVIDER = "provider";
    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LON = "lon";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_MSG = "msg";

    /** 采样间隔：10 秒一个点 */
    public static final long INTERVAL_MS = 10_000L;
    /** 最小落库间隔，防止系统加塞的快速回调抖出密集点 */
    private static final long MIN_GAP_MS = 8_000L;
    /** 定位数据超过这个时间就不再落库，避免重复记录陈旧坐标 */
    private static final long STALE_FIX_MS = 90_000L;

    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL_ID = "track_channel";

    private LocationManager locationManager;
    private PowerManager.WakeLock wakeLock;
    private AlarmManager alarmManager;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TrackDb db;
    private Prefs prefs;
    private NotificationManager notificationManager;

    private Location bestFix;
    private long bestFixWallTs;

    private long lastStoredTs;
    private long sessionStart;
    private long count;
    private double distance;
    private float lastAcc = -1f;
    private String lastProvider = "";
    private boolean running;
    private String lastMsg = "等待定位…";

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            tryStorePoint(false);
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        db = TrackDb.get(this);
        prefs = new Prefs(this);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        createChannel();

        // 必须在 5 秒内进入前台，否则系统会抛 ANR / 直接杀服务
        startAsForeground(null);
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopTracking();
            return START_NOT_STICKY;
        }

        if (!prefs.isTracking()) {
            // 新的一次记录会话
            prefs.setTracking(true);
            sessionStart = System.currentTimeMillis();
            prefs.setSessionStart(sessionStart);
            count = 0;
            distance = 0;
            lastStoredTs = 0;
            hasLoadedStats = false;
        } else if (sessionStart == 0) {
            sessionStart = prefs.getSessionStart();
            if (sessionStart == 0) {
                sessionStart = System.currentTimeMillis();
                prefs.setSessionStart(sessionStart);
            }
        }

        startTracking();
        return START_STICKY;
    }

    private boolean hasLoadedStats;

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        stopLocationUpdates();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户从最近任务里划掉 App：如果还在记录，安排一次复活
        if (prefs.isTracking()) {
            scheduleRevive(30_000L);
        }
        super.onTaskRemoved(rootIntent);
    }

    // ------------------------------------------------------------------ 记录逻辑

    private void startTracking() {
        if (running) {
            updateNotification();
            broadcastUpdate();
            return;
        }
        running = true;

        // 恢复本次会话统计（进程被重启时）
        if (!hasLoadedStats) {
            TrackDb.Stats st = db.statsSince(sessionStart > 0 ? sessionStart : db.minTs());
            count = st.count;
            distance = st.distance;
            if (st.lastTs > 0) lastStoredTs = st.lastTs;
            hasLoadedStats = true;
        }

        requestLocationUpdates();
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, INTERVAL_MS);
        updateNotification();
        broadcastUpdate();
        Log.i(TAG, "tracking started, sessionStart=" + sessionStart);
    }

    private void stopTracking() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        stopLocationUpdates();
        prefs.setTracking(false);
        broadcastUpdate();
        releaseWakeLock();
        stopForeground(true);
        notificationManager.cancel(NOTIF_ID);
        stopSelf();
    }

    private void requestLocationUpdates() {
        if (!hasLocationPermission()) {
            lastMsg = "缺少定位权限，无法记录";
            updateNotification();
            return;
        }
        try {
            boolean gps = false;
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, this, Looper.getMainLooper());
                gps = true;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, INTERVAL_MS, 0f, this, Looper.getMainLooper());
            }
            if (!gps) {
                lastMsg = "GPS 未开启，正在用网络定位";
            }
            // 先用最后已知位置填一个点，避免开头 10 秒空窗
            Location last = locationManager.getLastKnownLocation(
                    gps ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER);
            if (last != null && System.currentTimeMillis() - last.getTime() < STALE_FIX_MS) {
                bestFix = last;
                bestFixWallTs = last.getTime();
            }
        } catch (SecurityException e) {
            lastMsg = "定位权限被拒绝";
            Log.w(TAG, "requestLocationUpdates SecurityException", e);
        } catch (Exception e) {
            Log.w(TAG, "requestLocationUpdates failed", e);
        }
    }

    private void stopLocationUpdates() {
        try {
            if (locationManager != null) locationManager.removeUpdates(this);
        } catch (Exception ignored) {
        }
    }

    /** 由定位回调或 10 秒心跳触发；满足间隔就落库 */
    private void tryStorePoint(boolean force) {
        long now = System.currentTimeMillis();
        if (bestFix == null) {
            lastMsg = "等待 GPS 定位…";
            updateNotification();
            return;
        }
        long fixTs = bestFixWallTs;
        if (!force) {
            if (fixTs <= lastStoredTs) {                 // 没有更新的定位，不重复落点
                return;
            }
            if (now - lastStoredTs < MIN_GAP_MS) {       // 距上一个点不足 8 秒
                return;
            }
            if (now - fixTs > STALE_FIX_MS) {            // 定位已经过期
                lastMsg = "信号弱，暂缓记录";
                updateNotification();
                return;
            }
        }

        int batt = batteryPercent();
        TrackPoint p = new TrackPoint(
                fixTs, bestFix.getLatitude(), bestFix.getLongitude(),
                bestFix.hasAccuracy() ? bestFix.getAccuracy() : -1f,
                bestFix.hasAltitude() ? bestFix.getAltitude() : 0,
                bestFix.hasSpeed() ? bestFix.getSpeed() : 0f,
                String.valueOf(bestFix.getProvider()),
                batt);

        if (bestFix.hasAccuracy()) lastAcc = bestFix.getAccuracy();
        lastProvider = p.provider;

        if (lastStoredTs > 0 && fixTs > lastStoredTs) {
            // 与上一个点算位移
            double d = GeoUtil.distance(lastLatValue, lastLonValue, p.lat, p.lon);
            if (d < 2000) distance += d;
        }
        lastLatValue = p.lat;
        lastLonValue = p.lon;
        lastStoredTs = Math.max(lastStoredTs, fixTs);
        count++;

        final TrackPoint fp = p;
        db.insert(fp);

        lastMsg = "已记录 " + count + " 个点";
        updateNotification();
        broadcastUpdate();
    }

    private double lastLatValue, lastLonValue;

    // ------------------------------------------------------------------ 定位回调

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) return;
        if (bestFix == null
                || location.getTime() >= bestFixTs()
                || (location.hasAccuracy() && bestFix.hasAccuracy()
                && location.getAccuracy() + 5 < bestFix.getAccuracy())) {
            bestFix = location;
            bestFixWallTs = location.getTime();
        }
        tryStorePoint(false);
    }

    private long bestFixTs() {
        return bestFix == null ? 0 : bestFix.getTime();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
        requestLocationUpdates();
    }

    @Override
    public void onProviderDisabled(String provider) {
        lastMsg = "GPS 已关闭，等待重新开启";
        updateNotification();
    }

    // ------------------------------------------------------------------ 前台通知

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "轨迹记录", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("轨迹记录服务的常驻通知");
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            notificationManager.createNotificationChannel(ch);
        }
    }

    private void startAsForeground(Notification n) {
        Notification notification = n != null ? n : buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } catch (Exception e) {
            // Android 12+ 后台启动前台服务可能被拒，退回普通 startForeground
            Log.w(TAG, "startForeground failed", e);
            try {
                startForeground(NOTIF_ID, notification);
            } catch (Exception ignored) {
            }
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, TrackService.class).setAction(ACTION_STOP);
        PendingIntent piStop = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setSmallIcon(R.drawable.ic_stat_track)
                .setContentTitle("轨迹记录中 · " + count + " 点")
                .setContentText(lastMsg + " · " + GeoUtil.formatDistance(distance))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(piOpen)
                .addAction(new Notification.Action.Builder(null, "停止记录", piStop).build());
        if (running) {
            b.setSubText("每 10 秒采样 · 后台常驻");
        }
        Notification n = b.build();
        n.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        return n;
    }

    private void updateNotification() {
        try {
            notificationManager.notify(NOTIF_ID, buildNotification());
        } catch (Exception ignored) {
        }
    }

    private void broadcastUpdate() {
        try {
            Intent i = new Intent(ACTION_UPDATE);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_COUNT, count);
            i.putExtra(EXTRA_DIST, distance);
            i.putExtra(EXTRA_START, sessionStart);
            i.putExtra(EXTRA_ACC, lastAcc);
            i.putExtra(EXTRA_LAST_TS, lastStoredTs);
            i.putExtra(EXTRA_PROVIDER, lastProvider);
            i.putExtra(EXTRA_LAT, lastLatValue);
            i.putExtra(EXTRA_LON, lastLonValue);
            i.putExtra(EXTRA_RUNNING, running);
            i.putExtra(EXTRA_MSG, lastMsg);
            sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ 保活相关

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackRecorder::collect");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "wakelock failed", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }

    private void scheduleRevive(long delayMs) {
        try {
            alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(this, BootReceiver.class).setAction(ACTION_REVIVE);
            PendingIntent pi = PendingIntent.getBroadcast(
                    this, 2, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            alarmManager.set(AlarmManager.RTC_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi);
        } catch (Exception e) {
            Log.w(TAG, "scheduleRevive failed", e);
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private int batteryPercent() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            int p = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (p >= 0) {
                prefs.setLastBatt(p);
                return p;
            }
        } catch (Exception ignored) {
        }
        return prefs.getLastBatt();
    }

    // ------------------------------------------------------------------ 静态入口

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, TrackService.class).setAction(ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "start service failed", e);
        }
    }

    public static void stop(Context ctx) {
        try {
            Intent i = new Intent(ctx, TrackService.class).setAction(ACTION_STOP);
            ctx.startService(i);
        } catch (Exception ignored) {
        }
    }
}
