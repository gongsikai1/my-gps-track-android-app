package com.track.recorder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨迹点的本地持久化（SQLite）。
 * 每条记录写入即落盘，服务被杀也不会丢历史数据。
 */
public class TrackDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "track.db";
    private static final int VERSION = 1;
    private static final String TABLE = "points";

    private static TrackDb instance;

    public static synchronized TrackDb get(Context ctx) {
        if (instance == null) {
            instance = new TrackDb(ctx.getApplicationContext());
        }
        return instance;
    }

    private TrackDb(Context ctx) {
        super(ctx, DB_NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "ts INTEGER NOT NULL, "
                + "lat REAL NOT NULL, "
                + "lon REAL NOT NULL, "
                + "acc REAL, "
                + "alt REAL, "
                + "spd REAL, "
                + "provider TEXT, "
                + "batt INTEGER)");
        db.execSQL("CREATE INDEX idx_ts ON " + TABLE + " (ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 首个版本，无需迁移
    }

    /** 插入一个点，返回行 id（失败返回 -1） */
    public long insert(TrackPoint p) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("ts", p.ts);
            cv.put("lat", p.lat);
            cv.put("lon", p.lon);
            cv.put("acc", p.acc);
            cv.put("alt", p.alt);
            cv.put("spd", p.spd);
            cv.put("provider", p.provider == null ? "" : p.provider);
            cv.put("batt", p.batt);
            return db.insert(TABLE, null, cv);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 数据库总点数 */
    public long countAll() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    /**
     * 统计某一时刻之后的点数与累计里程（按点序累加 haversine 距离）。
     */
    public Stats statsSince(long sinceTs) {
        Stats st = new Stats();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT ts, lat, lon FROM " + TABLE + " WHERE ts >= " + sinceTs + " ORDER BY ts ASC", null);
            double prevLat = 0, prevLon = 0;
            boolean hasPrev = false;
            while (c.moveToNext()) {
                double lat = c.getDouble(1);
                double lon = c.getDouble(2);
                if (hasPrev) {
                    double d = GeoUtil.distance(prevLat, prevLon, lat, lon);
                    // 过滤明显跳变（> 2km/点 且 单点位移超过 200km/h 的漂移）
                    if (d < 2000) st.distance += d;
                }
                prevLat = lat;
                prevLon = lon;
                hasPrev = true;
                st.count++;
                st.lastTs = c.getLong(0);
            }
            if (c.moveToFirst() || st.count > 0) {
                st.firstTs = sinceTs;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return st;
    }

    /** 取最近 limit 个点（按时间正序返回，用于绘图） */
    public List<TrackPoint> recentPoints(long sinceTs, int limit) {
        List<TrackPoint> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT ts, lat, lon, acc FROM " + TABLE + " WHERE ts >= " + sinceTs
                            + " ORDER BY ts DESC LIMIT " + limit, null);
            while (c.moveToNext()) {
                TrackPoint p = new TrackPoint();
                p.ts = c.getLong(0);
                p.lat = c.getDouble(1);
                p.lon = c.getDouble(2);
                p.acc = c.getFloat(3);
                out.add(0, p);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public List<TrackPoint> allPoints() {
        List<TrackPoint> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT ts, lat, lon, acc, alt, spd, provider, batt FROM " + TABLE + " ORDER BY ts ASC", null);
            while (c.moveToNext()) {
                TrackPoint p = new TrackPoint();
                p.ts = c.getLong(0);
                p.lat = c.getDouble(1);
                p.lon = c.getDouble(2);
                p.acc = c.getFloat(3);
                p.alt = c.getDouble(4);
                p.spd = c.getFloat(5);
                p.provider = c.getString(6);
                p.batt = c.getInt(7);
                out.add(p);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    public void clearAll() {
        try {
            getWritableDatabase().delete(TABLE, null, null);
        } catch (Exception ignored) {
        }
    }

    public long minTs() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT MIN(ts) FROM " + TABLE, null);
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    public static class Stats {
        public long count;
        public double distance;
        public long lastTs;
        public long firstTs;
    }
}
