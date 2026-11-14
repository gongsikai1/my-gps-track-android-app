package com.track.recorder;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 导出 CSV。Android 10+ 走 MediaStore 直接落进「下载/TrackRecorder」，
 * 无需存储权限，用户可以在文件管理器里直接找到，也能一键分享。
 */
public class CsvExporter {

    public static class Result {
        public String fileName;
        public String message;
        public Uri uri;
        public long count;
    }

    public static Result export(Context ctx) throws IOException {
        TrackDb db = TrackDb.get(ctx);
        List<TrackPoint> pts = db.allPoints();
        String fileName = "track_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()) + ".csv";
        String content = buildCsv(pts);

        Result r = new Result();
        r.fileName = fileName;
        r.count = pts.size();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TrackRecorder");
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("无法在系统下载目录创建文件");
            try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IOException("无法写入导出文件");
                os.write(content.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            r.uri = uri;
            r.message = "已导出 " + pts.size() + " 个点到 下载/TrackRecorder/" + fileName;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TrackRecorder");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建导出目录");
            File f = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            r.message = "已导出 " + pts.size() + " 个点到 " + f.getAbsolutePath();
        }
        return r;
    }

    private static String buildCsv(List<TrackPoint> pts) {
        StringBuilder sb = new StringBuilder(pts.size() * 90 + 200);
        sb.append('\uFEFF');   // BOM，保证 Excel 打开中文表头不乱码
        sb.append("序号,时间,时间戳(毫秒),纬度,经度,精度(米),海拔(米),速度(米/秒),来源,电量(%)\r\n");
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        int idx = 1;
        for (TrackPoint p : pts) {
            sb.append(idx++).append(',')
                    .append(fmt.format(new Date(p.ts))).append(',')
                    .append(p.ts).append(',')
                    .append(String.format(Locale.US, "%.7f", p.lat)).append(',')
                    .append(String.format(Locale.US, "%.7f", p.lon)).append(',')
                    .append(String.format(Locale.US, "%.1f", p.acc)).append(',')
                    .append(String.format(Locale.US, "%.1f", p.alt)).append(',')
                    .append(String.format(Locale.US, "%.2f", p.spd)).append(',')
                    .append(p.provider).append(',')
                    .append(p.batt)
                    .append("\r\n");
        }
        return sb.toString();
    }
}
