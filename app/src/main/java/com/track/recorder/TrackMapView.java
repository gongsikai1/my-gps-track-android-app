package com.track.recorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量轨迹绘制视图：不依赖任何地图 SDK / Google 服务，
 * 直接把记录的经纬度按等比缩放到画布上画成折线，国内机型、无网环境都能看。
 */
public class TrackMapView extends View {

    private final List<TrackPoint> points = new ArrayList<>();

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pathPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint startPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TrackMapView(Context context) {
        super(context);
        init();
    }

    public TrackMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TrackMapView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float d = getResources().getDisplayMetrics().density;
        gridPaint.setColor(0xFF1B2835);
        gridPaint.setStrokeWidth(d * 0.8f);

        pathPaint.setColor(0xFF2FE0A8);
        pathPaint.setStrokeWidth(d * 2.2f);
        pathPaint.setStyle(Paint.Style.STROKE);
        pathPaint.setStrokeCap(Paint.Cap.ROUND);
        pathPaint.setStrokeJoin(Paint.Join.ROUND);

        glowPaint.setColor(0x332FE0A8);
        glowPaint.setStrokeWidth(d * 7f);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);

        startPaint.setColor(0xFF7C8CA0);
        startPaint.setStyle(Paint.Style.STROKE);
        startPaint.setStrokeWidth(d * 1.6f);

        nowPaint.setColor(0xFF2FE0A8);
        nowPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(0xFF57687C);
        textPaint.setTextSize(d * 11.5f);
        textPaint.setAntiAlias(true);
    }

    public void setPoints(List<TrackPoint> pts) {
        points.clear();
        if (pts != null) points.addAll(pts);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        drawGrid(canvas, w, h);

        if (points.isEmpty()) {
            String tip = "暂无轨迹数据";
            float tw = textPaint.measureText(tip);
            canvas.drawText(tip, (w - tw) / 2f, h / 2f, textPaint);
            return;
        }

        // 经纬度 -> 米（等距圆柱投影，小范围内足够准确）
        double minLat = points.get(0).lat, maxLat = minLat;
        double minLon = points.get(0).lon, maxLon = minLon;
        for (TrackPoint p : points) {
            if (p.lat < minLat) minLat = p.lat;
            if (p.lat > maxLat) maxLat = p.lat;
            if (p.lon < minLon) minLon = p.lon;
            if (p.lon > maxLon) maxLon = p.lon;
        }
        double midLat = (minLat + maxLat) / 2.0;
        double cosLat = Math.cos(Math.toRadians(midLat));
        double mPerDegLat = 110540.0;
        double mPerDegLon = 111320.0 * Math.max(cosLat, 0.0001);

        double spanXm = (maxLon - minLon) * mPerDegLon;
        double spanYm = (maxLat - minLat) * mPerDegLat;
        // 最小可视范围 120 米，避免原地不动时坐标被放大成噪音
        double minSpan = 120.0;
        float pad = getResources().getDisplayMetrics().density * 26f;

        double availW = Math.max(w - pad * 2, 1);
        double availH = Math.max(h - pad * 2, 1);

        double scale;
        if (spanXm < 1e-6 && spanYm < 1e-6) {
            scale = Math.min(availW, availH) / minSpan;
        } else {
            scale = Math.min(availW / Math.max(spanXm, minSpan), availH / Math.max(spanYm, minSpan));
        }

        double cxM = ((minLon + maxLon) / 2.0) * mPerDegLon;
        double cyM = ((minLat + maxLat) / 2.0) * mPerDegLat;
        double cx = w / 2.0;
        double cy = h / 2.0;

        Path path = new Path();
        Path glow = new Path();
        for (int i = 0; i < points.size(); i++) {
            TrackPoint p = points.get(i);
            float x = (float) (cx + (p.lon * mPerDegLon - cxM) * scale);
            float y = (float) (cy - (p.lat * mPerDegLat - cyM) * scale);
            if (i == 0) {
                path.moveTo(x, y);
                glow.moveTo(x, y);
            } else {
                path.lineTo(x, y);
                glow.lineTo(x, y);
            }
        }

        if (points.size() > 1) {
            canvas.drawPath(glow, glowPaint);
            canvas.drawPath(path, pathPaint);
        }

        // 起点
        TrackPoint first = points.get(0);
        float sx = (float) (cx + (first.lon * mPerDegLon - cxM) * scale);
        float sy = (float) (cy - (first.lat * mPerDegLat - cyM) * scale);
        canvas.drawCircle(sx, sy, getResources().getDisplayMetrics().density * 5f, startPaint);

        // 当前点
        TrackPoint last = points.get(points.size() - 1);
        float lx = (float) (cx + (last.lon * mPerDegLon - cxM) * scale);
        float ly = (float) (cy - (last.lat * mPerDegLat - cyM) * scale);
        canvas.drawCircle(lx, ly, getResources().getDisplayMetrics().density * 4.2f, nowPaint);

        String info = "起 " + String.format("%.5f, %.5f", first.lat, first.lon)
                + "   ·   现 " + String.format("%.5f, %.5f", last.lat, last.lon);
        canvas.drawText(info, pad, h - pad * 0.45f, textPaint);
    }

    private void drawGrid(Canvas canvas, float w, float h) {
        float step = Math.max(w, h) / 6f;
        for (float x = step; x < w; x += step) canvas.drawLine(x, 0, x, h, gridPaint);
        for (float y = step; y < h; y += step) canvas.drawLine(0, y, w, y, gridPaint);
    }
}
