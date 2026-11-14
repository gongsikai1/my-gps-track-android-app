package com.track.recorder;

/** 一个轨迹点 */
public class TrackPoint {
    public long ts;          // 采集时间（毫秒）
    public double lat;
    public double lon;
    public float acc;        // 精度（米）
    public double alt;       // 海拔（米）
    public float spd;        // 速度（米/秒）
    public String provider;  // gps / network / fused
    public int batt;         // 采集时电量百分比，-1 表示未知

    public TrackPoint() {
    }

    public TrackPoint(long ts, double lat, double lon, float acc, double alt, float spd, String provider, int batt) {
        this.ts = ts;
        this.lat = lat;
        this.lon = lon;
        this.acc = acc;
        this.alt = alt;
        this.spd = spd;
        this.provider = provider;
        this.batt = batt;
    }
}
