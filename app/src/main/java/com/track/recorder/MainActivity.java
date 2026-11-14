package com.track.recorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_FG = 101;
    private static final int REQ_BG = 102;
    private static final int REQ_NOTIF = 103;
    private static final int REQ_STORAGE = 104;

    private TextView tvPoints, tvDist, tvDuration, tvAcc, chipStatus, tvHint, tvDbInfo, tvLast;
    private TextView btnStart, btnStop, btnExport, btnKeep, btnClear;
    private TextView tvCoord, tvAddress, btnMap, btnCopy;
    private TrackMapView mapView;

    // 当前位置（WGS84 原始坐标）
    private double curLat, curLon;
    private boolean hasCur;
    private float curAcc = -1f;
    private long curTs;
    private long lastGeocodeTs;
    private double lastGeocodeLat, lastGeocodeLon;
    private boolean geocoding;

    private Prefs prefs;
    private TrackDb db;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private long count;
    private double dist;
    private long startTs;
    private float acc = -1f;
    private boolean running;
    private boolean pendingStart = false;

    private BroadcastReceiver updateReceiver;
    private boolean receiverRegistered = false;

    private final Runnable secondTicker = new Runnable() {
        @Override
        public void run() {
            if (startTs > 0 && running) {
                tvDuration.setText(GeoUtil.formatDuration(System.currentTimeMillis() - startTs));
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        db = TrackDb.get(this);

        tvPoints = findViewById(R.id.tvPoints);
        tvDist = findViewById(R.id.tvDist);
        tvDuration = findViewById(R.id.tvDuration);
        tvAcc = findViewById(R.id.tvAcc);
        chipStatus = findViewById(R.id.chipStatus);
        tvHint = findViewById(R.id.tvHint);
        tvDbInfo = findViewById(R.id.tvDbInfo);
        tvLast = findViewById(R.id.tvLast);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnExport = findViewById(R.id.btnExport);
        btnKeep = findViewById(R.id.btnKeep);
        btnClear = findViewById(R.id.btnClear);
        tvCoord = findViewById(R.id.tvCoord);
        tvAddress = findViewById(R.id.tvAddress);
        btnMap = findViewById(R.id.btnMap);
        btnCopy = findViewById(R.id.btnCopy);
        mapView = findViewById(R.id.mapView);

        btnStart.setOnClickListener(v -> onStartClicked());
        btnStop.setOnClickListener(v -> onStopClicked());
        btnExport.setOnClickListener(v -> onExportClicked());
        btnKeep.setOnClickListener(v -> showKeepAliveDialog());
        btnClear.setOnClickListener(v -> onClearClicked());
        btnMap.setOnClickListener(v -> {
            try {
                startActivity(new Intent(MainActivity.this, MapActivity.class));
            } catch (Throwable e) {
                Toast.makeText(this, "地图启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        btnMap.setOnLongClickListener(v -> {
            openInMap();
            return true;
        });
        btnCopy.setOnClickListener(v -> copyCoord());

        startTs = prefs.getSessionStart();
        running = prefs.isTracking();

        // 如果上次正在记录但服务被杀，进入应用时尝试恢复
        if (running && hasForegroundLocation()) {
            TrackService.start(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerUpdateReceiver();
        ui.removeCallbacks(secondTicker);
        ui.post(secondTicker);
        refreshUi();
        loadFromDb();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(secondTicker);
        if (receiverRegistered && updateReceiver != null) {
            try {
                unregisterReceiver(updateReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
    }

    // ------------------------------------------------------------------ 广播

    private void registerUpdateReceiver() {
        if (receiverRegistered) return;
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                count = intent.getLongExtra(TrackService.EXTRA_COUNT, count);
                dist = intent.getDoubleExtra(TrackService.EXTRA_DIST, dist);
                long s = intent.getLongExtra(TrackService.EXTRA_START, startTs);
                if (s > 0) startTs = s;
                acc = intent.getFloatExtra(TrackService.EXTRA_ACC, acc);
                double lat = intent.getDoubleExtra(TrackService.EXTRA_LAT, 0);
                double lon = intent.getDoubleExtra(TrackService.EXTRA_LON, 0);
                long lastTs = intent.getLongExtra(TrackService.EXTRA_LAST_TS, 0);
                if (lat != 0 && lon != 0) {
                    setCurrent(lat, lon, acc, lastTs);
                }
                running = intent.getBooleanExtra(TrackService.EXTRA_RUNNING, running);
                String msg = intent.getStringExtra(TrackService.EXTRA_MSG);
                if (msg != null) tvLast.setText("状态：" + msg);
                refreshUi();
                loadMap();
            }
        };
        IntentFilter filter = new IntentFilter(TrackService.ACTION_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }
        receiverRegistered = true;
    }

    // ------------------------------------------------------------------ 数据刷新

    private void loadFromDb() {
        new Thread(() -> {
            final TrackDb.Stats st = db.statsSince(startTs > 0 ? startTs : db.minTs());
            final long total = db.countAll();
            final List<TrackPoint> pts = db.recentPoints(startTs > 0 ? startTs : db.minTs(), 3000);
            ui.post(() -> {
                if (!running || count == 0) {
                    count = st.count;
                    dist = st.distance;
                }
                tvDbInfo.setText("数据库累计 " + total + " 个点 · 每 10 秒记录 1 个点");
                if (!pts.isEmpty()) {
                    mapView.setPoints(pts);
                    TrackPoint lp = pts.get(pts.size() - 1);
                    setCurrent(lp.lat, lp.lon, lp.acc, lp.ts);
                }
                refreshUi();
            });
        }).start();
    }

    private void loadMap() {
        final long since = startTs > 0 ? startTs : db.minTs();
        new Thread(() -> {
            final List<TrackPoint> pts = db.recentPoints(since, 3000);
            ui.post(() -> {
                if (!pts.isEmpty()) mapView.setPoints(pts);
            });
        }).start();
    }

    private void refreshUi() {
        tvPoints.setText(String.valueOf(count));
        tvDist.setText(GeoUtil.formatDistance(dist));
        long dur = startTs > 0 && running ? System.currentTimeMillis() - startTs
                : (startTs > 0 ? Math.max(0, System.currentTimeMillis() - startTs) : 0);
        tvDuration.setText(GeoUtil.formatDuration(dur));
        tvAcc.setText(acc > 0 ? String.format("%.0f m", acc) : "--");

        if (running) {
            chipStatus.setText("记录中");
            chipStatus.setBackgroundResource(R.drawable.bg_chip_on);
            chipStatus.setTextColor(0xFF2FE0A8);
            tvLast.setTextColor(0xFF2FE0A8);
        } else {
            chipStatus.setText("已停止");
            chipStatus.setBackgroundResource(R.drawable.bg_chip_off);
            chipStatus.setTextColor(0xFF7C8CA0);
            tvLast.setTextColor(0xFF57687C);
            if (tvLast.getText().length() == 0) tvLast.setText("状态：待启动");
        }
        btnStart.setAlpha(running ? 0.45f : 1f);
        btnStop.setAlpha(running ? 1f : 0.45f);

        tvHint.setText(permissionSummary());
    }

    private String permissionSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("定位：").append(hasForegroundLocation() ? "已授权" : "未授权");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sb.append(" · 后台定位：").append(hasBackgroundLocation() ? "已授权" : "未授权");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sb.append(" · 通知：").append(hasNotificationPermission() ? "已授权" : "未授权");
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        sb.append(" · 电池优化：").append(pm.isIgnoringBatteryOptimizations(getPackageName()) ? "已忽略" : "未忽略");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 启动流程

    private void onStartClicked() {
        if (!hasForegroundLocation()) {
            pendingStart = true;
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_FG);
            return;
        }
        continuePermissionChain();
    }

    private void continuePermissionChain() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要「始终允许」定位")
                    .setMessage("要实现 7×24 小时后台记录，必须在接下来的系统设置里把定位权限选择为「始终允许」。\n\n"
                            + "下一步会跳转到权限页，请选择「始终允许」。")
                    .setPositiveButton("去设置", (d, w) ->
                            requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BG))
                    .setNegativeButton("先跳过", (d, w) -> startTrackingNow())
                    .show();
            return;
        }
        startTrackingNow();
    }

    private void startTrackingNow() {
        pendingStart = false;
        running = true;
        startTs = System.currentTimeMillis();
        prefs.setSessionStart(startTs);
        count = 0;
        dist = 0;
        mapView.setPoints(null);
        TrackService.start(this);
        refreshUi();
        loadMap();
        maybeAskBattery();
    }

    private void maybeAskBattery() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        new AlertDialog.Builder(this)
                .setTitle("建议关闭电池优化")
                .setMessage("系统为了省电会限制后台定位。关闭「电池优化」后，App 才能在息屏、锁屏后持续每 10 秒记录一个点。")
                .setPositiveButton("去关闭", (d, w) -> requestIgnoreBattery())
                .setNegativeButton("以后再说", null)
                .show();
    }

    private void onStopClicked() {
        running = false;
        TrackService.stop(this);
        prefs.setTracking(false);
        refreshUi();
        Toast.makeText(this, "已停止记录，历史数据仍保留", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ 导出 / 清空

    private void onExportClicked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        doExport();
    }

    private void doExport() {
        Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                final CsvExporter.Result r = CsvExporter.export(MainActivity.this);
                ui.post(() -> showExportDone(r));
            } catch (final Exception e) {
                ui.post(() -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("导出失败")
                        .setMessage(String.valueOf(e.getMessage()))
                        .setPositiveButton("知道了", null)
                        .show());
            }
        }).start();
    }

    private void showExportDone(CsvExporter.Result r) {
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("导出完成")
                .setMessage(r.message)
                .setPositiveButton("关闭", null);
        if (r.uri != null) {
            b.setNeutralButton("分享文件", (d, w) -> shareCsv(r));
        }
        b.show();
    }

    private void shareCsv(CsvExporter.Result r) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/csv");
            send.putExtra(Intent.EXTRA_STREAM, r.uri);
            send.putExtra(Intent.EXTRA_SUBJECT, r.fileName);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "分享轨迹 CSV"));
        } catch (Exception e) {
            Toast.makeText(this, "分享失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void onClearClicked() {
        new AlertDialog.Builder(this)
                .setTitle("清空所有轨迹数据？")
                .setMessage("本地数据库中的全部轨迹点会被删除，且无法恢复。建议先导出 CSV 备份。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认清空", (d, w) -> {
                    boolean wasRunning = prefs.isTracking();
                    if (wasRunning) TrackService.stop(this);
                    db.clearAll();
                    count = 0;
                    dist = 0;
                    startTs = 0;
                    prefs.setSessionStart(0);
                    prefs.setTracking(false);
                    running = false;
                    mapView.setPoints(null);
                    refreshUi();
                    tvDbInfo.setText("数据库累计 0 个点 · 每 10 秒记录 1 个点");
                    tvLast.setText("状态：待启动");
                    if (wasRunning) {
                        ui.postDelayed(() -> {
                            startTs = System.currentTimeMillis();
                            prefs.setSessionStart(startTs);
                            running = true;
                            TrackService.start(MainActivity.this);
                            refreshUi();
                        }, 800);
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ 保活

    private void showKeepAliveDialog() {
        String[] items = new String[]{
                "① 关闭电池优化（必做，否则息屏会被限制）",
                "② 打开应用详情，设置「自启动 / 后台运行」",
                "③ 查看 7×24 保活设置说明"
        };
        new AlertDialog.Builder(this)
                .setTitle("后台保活设置")
                .setItems(items, (d, which) -> {
                    if (which == 0) requestIgnoreBattery();
                    else if (which == 1) openAppDetail();
                    else showKeepAliveHelp();
                })
                .show();
    }

    private void requestIgnoreBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "请手动在系统设置中关闭电池优化", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openAppDetail() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开应用详情页", Toast.LENGTH_SHORT).show();
        }
    }

    private void showKeepAliveHelp() {
        new AlertDialog.Builder(this)
                .setTitle("如何做到 7×24 小时不断")
                .setMessage("1. 权限：定位选择「始终允许」，并在通知栏常驻通知可见。\n"
                        + "2. 电池：系统设置里把本应用设为「不限制 / 无限制」，关闭智能省电。\n"
                        + "3. 自启动：在系统「自启动管理」中允许本应用自启动（小米/华为/OPPO/vivo 等机型都需手动开）。\n"
                        + "4. 最近任务：部分机型从最近任务划掉会杀服务，本应用已做了自动复活与开机自启。\n"
                        + "5. 建议插着充电或随身充电宝，7×24 的 GPS 连续定位耗电较大。"
                        + "\n\n少数厂商 ROM 会强制清理后台，属于系统限制，极端情况下仍可能中断。")
                .setPositiveButton("知道了", null)
                .show();
    }

    // ------------------------------------------------------------------ 当前位置 / 看地图

    private void setCurrent(double lat, double lon, float accuracy, long ts) {
        curLat = lat;
        curLon = lon;
        curAcc = accuracy;
        curTs = ts > 0 ? ts : System.currentTimeMillis();
        hasCur = true;
        updateCoordUi();
        maybeReverseGeocode();
    }

    private void updateCoordUi() {
        if (!hasCur) {
            tvCoord.setText("当前位置：等待首个定位点…");
            return;
        }
        String time = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date(curTs));
        tvCoord.setText(String.format(Locale.US,
                "当前位置：%.6f, %.6f　·　精度 ±%.0fm　·　%s（WGS84）",
                curLat, curLon, curAcc > 0 ? curAcc : 0f, time));
    }

    /** 把坐标交给已安装的高德 / 百度 / 腾讯地图（自动换算成对应坐标系），没有就退到系统地图 */
    private void openInMap() {
        if (!hasCur) {
            Toast.makeText(this, "还没有定位点，先点「开始记录」", Toast.LENGTH_SHORT).show();
            return;
        }
        String label = "轨迹记录当前位置";

        // 高德：GCJ02
        double[] gcj = GeoUtil.wgs84ToGcj02(curLat, curLon);
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("androidamap://viewMap"
                    + "?sourceApplication=TrackRecorder"
                    + "&poiname=" + Uri.encode(label)
                    + "&lat=" + String.format(Locale.US, "%.6f", gcj[0])
                    + "&lon=" + String.format(Locale.US, "%.6f", gcj[1])
                    + "&dev=0"));
            i.setPackage("com.autonavi.minimap");
            startActivity(i);
            return;
        } catch (Exception ignored) {
        }

        // 百度：BD09
        double[] bd = GeoUtil.wgs84ToBd09(curLat, curLon);
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("baidumap://map/marker"
                    + "?location=" + String.format(Locale.US, "%.6f", bd[0]) + "," + String.format(Locale.US, "%.6f", bd[1])
                    + "&title=" + Uri.encode(label)
                    + "&content=" + Uri.encode(label)
                    + "&coord_type=bd09ll"
                    + "&src=android.track.recorder"));
            i.setPackage("com.baidu.BaiduMap");
            startActivity(i);
            return;
        } catch (Exception ignored) {
        }

        // 腾讯：GCJ02
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("qqmap://map/marker"
                    + "?marker=coord:" + String.format(Locale.US, "%.6f", gcj[0]) + "," + String.format(Locale.US, "%.6f", gcj[1])
                    + ";title:" + Uri.encode(label) + ";addr:" + Uri.encode(label)));
            i.setPackage("com.tencent.map");
            startActivity(i);
            return;
        } catch (Exception ignored) {
        }

        // 系统通用 geo:
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + curLat + "," + curLon
                    + "?q=" + curLat + "," + curLon + "(" + Uri.encode(label) + ")"));
            startActivity(i);
            return;
        } catch (Exception ignored) {
        }

        Toast.makeText(this, "没找到可用的地图 App，已把坐标复制到剪贴板", Toast.LENGTH_LONG).show();
        copyCoord();
    }

    private void copyCoord() {
        if (!hasCur) {
            Toast.makeText(this, "还没有定位点", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            String text = String.format(Locale.US, "%.6f,%.6f", curLat, curLon);
            cm.setPrimaryClip(ClipData.newPlainText("轨迹坐标", text));
            Toast.makeText(this, "已复制：" + text + "（可直接粘贴到地图搜索框）", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 用系统 Geocoder 反查地址，60 秒最多一次，失败静默忽略（部分机型无此服务） */
    private void maybeReverseGeocode() {
        if (!hasCur || geocoding) return;
        long now = System.currentTimeMillis();
        if (now - lastGeocodeTs < 60_000L) return;
        if (Math.abs(curLat - lastGeocodeLat) < 0.0005 && Math.abs(curLon - lastGeocodeLon) < 0.0005
                && lastGeocodeTs > 0) {
            return;
        }
        if (!Geocoder.isPresent()) {
            tvAddress.setText("");
            return;
        }
        lastGeocodeTs = now;
        lastGeocodeLat = curLat;
        lastGeocodeLon = curLon;
        geocoding = true;
        final double lat = curLat, lon = curLon;
        new Thread(() -> {
            String text = "";
            try {
                Geocoder g = new Geocoder(MainActivity.this, Locale.CHINA);
                List<Address> list = g.getFromLocation(lat, lon, 1);
                if (list != null && !list.isEmpty()) {
                    Address a = list.get(0);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i <= a.getMaxAddressLineIndex(); i++) {
                        String line = a.getAddressLine(i);
                        if (line != null && !line.trim().isEmpty()) {
                            if (sb.length() > 0) sb.append(' ');
                            sb.append(line.trim());
                        }
                    }
                    if (sb.length() == 0 && a.getLocality() != null) {
                        sb.append(a.getLocality());
                        if (a.getThoroughfare() != null) sb.append(' ').append(a.getThoroughfare());
                    }
                    if (sb.length() > 0) text = "地址解析：" + sb;
                }
            } catch (Exception ignored) {
            }
            final String ft = text;
            ui.post(() -> {
                geocoding = false;
                if (!ft.isEmpty()) tvAddress.setText(ft);
                else tvAddress.setText("地址解析不可用，点「在地图查看我的位置」最直观");
            });
        }).start();
    }

    // ------------------------------------------------------------------ 权限工具

    private boolean hasForegroundLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = false;
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_GRANTED) {
                granted = true;
                break;
            }
        }
        switch (requestCode) {
            case REQ_FG:
                if (hasForegroundLocation()) {
                    continuePermissionChain();
                } else {
                    pendingStart = false;
                    Toast.makeText(this, "没有定位权限无法记录轨迹", Toast.LENGTH_LONG).show();
                }
                break;
            case REQ_NOTIF:
                continuePermissionChain();
                break;
            case REQ_BG:
                if (!hasBackgroundLocation()) {
                    Toast.makeText(this, "未获得后台定位权限，息屏后可能中断记录", Toast.LENGTH_LONG).show();
                }
                startTrackingNow();
                break;
            case REQ_STORAGE:
                if (granted) doExport();
                else Toast.makeText(this, "没有存储权限无法导出", Toast.LENGTH_SHORT).show();
                break;
        }
        refreshUi();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        refreshUi();
        loadFromDb();
    }
}
