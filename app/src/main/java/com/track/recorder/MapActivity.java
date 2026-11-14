package com.track.recorder;

import android.app.Activity;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.Polyline;
import com.amap.api.maps.model.PolylineOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 内置高德地图页：真实街道底图 + 轨迹折线 + 当前位置标记。
 * 坐标：数据库存 WGS84，展示给高德前统一转 GCJ02。
 */
public class MapActivity extends Activity {

    private MapView mapView;
    private AMap aMap;
    private Polyline polyline;
    private Marker marker;
    private TextView tvInfo;
    private TextView btnBack, btnCenter, btnSatellite, btnAmapApp;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean follow = true;
    private boolean firstCamera = false;
    private boolean satellite = false;
    private TrackDb db;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);
        db = TrackDb.get(this);

        mapView = findViewById(R.id.map);
        mapView.onCreate(savedInstanceState);
        aMap = mapView.getMap();

        tvInfo = findViewById(R.id.tvMapInfo);
        btnBack = findViewById(R.id.btnMapBack);
        btnCenter = findViewById(R.id.btnMapCenter);
        btnSatellite = findViewById(R.id.btnMapSatellite);
        btnAmapApp = findViewById(R.id.btnMapAmapApp);

        try {
            aMap.getUiSettings().setZoomControlsEnabled(false);
            aMap.getUiSettings().setScaleControlsEnabled(true);
            aMap.getUiSettings().setRotateGesturesEnabled(false);
            aMap.getUiSettings().setTiltGesturesEnabled(false);
            aMap.setMapType(AMap.MAP_TYPE_NORMAL);
        } catch (Throwable e) {
            tvInfo.setText("地图初始化失败：" + e.getMessage());
        }

        btnBack.setOnClickListener(v -> finish());
        btnCenter.setOnClickListener(v -> {
            follow = true;
            firstCamera = false;
            refresh();
        });
        btnSatellite.setOnClickListener(v -> {
            satellite = !satellite;
            aMap.setMapType(satellite ? AMap.MAP_TYPE_SATELLITE : AMap.MAP_TYPE_NORMAL);
            btnSatellite.setText(satellite ? "标准图" : "卫星图");
        });
        btnAmapApp.setOnClickListener(v -> openInAmapApp());
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
        mapView.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (mapView != null) mapView.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }

    // ------------------------------------------------------------------

    private void refresh() {
        Prefs p = new Prefs(this);
        long s = p.getSessionStart();
        if (s <= 0) s = db.minTs();
        final long since = s;
        new Thread(() -> {
            List<TrackPoint> pts = db.recentPoints(since, 1000);
            ui.post(() -> drawTrack(pts));
        }).start();
    }

    private void drawTrack(List<TrackPoint> pts) {
        if (aMap == null) return;

        List<LatLng> path = new ArrayList<>();
        List<TrackPoint> raw = new ArrayList<>();
        for (TrackPoint p : pts) {
            double[] gcj = GeoUtil.wgs84ToGcj02(p.lat, p.lon);
            path.add(new LatLng(gcj[0], gcj[1]));
            raw.add(p);
        }

        if (path.isEmpty()) {
            tvInfo.setText("还没有轨迹点，先回主界面点「开始记录」");
            centerFallback();
            return;
        }

        if (polyline != null) polyline.remove();
        if (marker != null) marker.remove();
        polyline = aMap.addPolyline(new PolylineOptions()
                .addAll(path)
                .width(14f)
                .color(0xFF2FE0A8)
                .geodesic(true));

        TrackPoint lastP = raw.get(raw.size() - 1);
        LatLng last = path.get(path.size() - 1);
        marker = aMap.addMarker(new MarkerOptions()
                .position(last)
                .anchor(0.5f, 0.5f)
                .title("当前位置"));

        tvInfo.setText(String.format(Locale.US, "%d 个点 · 精度 ±%.0fm · %.6f, %.6f",
                path.size(), lastP.acc > 0 ? lastP.acc : 0f, lastP.lat, lastP.lon));

        if (!firstCamera || follow) {
            firstCamera = true;
            aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(last, 17f), 400, null);
            follow = true;
        }
    }

    /** 没有任何轨迹时，用系统最后已知位置兜底，让地图不是一片空白 */
    private void centerFallback() {
        if (firstCamera) return;
        firstCamera = true;
        LatLng target = null;
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (l != null) {
                double[] gcj = GeoUtil.wgs84ToGcj02(l.getLatitude(), l.getLongitude());
                target = new LatLng(gcj[0], gcj[1]);
            }
        } catch (Exception ignored) {
        }
        if (target == null) target = new LatLng(25.4313, 119.0078);  // 莆田市区兜底
        aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(target, 16f));
    }

    /** 用高德 App 打开当前位置（本机装了高德就走 URI 唤起） */
    private void openInAmapApp() {
        List<TrackPoint> pts = db.recentPoints(db.minTs(), 1);
        if (pts.isEmpty()) {
            Toast.makeText(this, "还没有定位点", Toast.LENGTH_SHORT).show();
            return;
        }
        TrackPoint p = pts.get(pts.size() - 1);
        double[] gcj = GeoUtil.wgs84ToGcj02(p.lat, p.lon);
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("androidamap://viewMap"
                    + "?sourceApplication=TrackRecorder&poiname=" + Uri.encode("我的位置")
                    + "&lat=" + String.format(Locale.US, "%.6f", gcj[0])
                    + "&lon=" + String.format(Locale.US, "%.6f", gcj[1]) + "&dev=0"));
            i.setPackage("com.autonavi.minimap");
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "未安装高德地图 App", Toast.LENGTH_SHORT).show();
        }
    }
}
