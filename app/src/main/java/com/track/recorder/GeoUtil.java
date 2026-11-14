package com.track.recorder;

/** 地理计算工具 */
public class GeoUtil {
    private static final double EARTH_R = 6371000.0;

    /** 两点间球面距离（米） */
    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);
        double dLat = radLat2 - radLat1;
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(radLat1) * Math.cos(radLat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_R * c;
    }

    // ------------------------------------------------------------------
    // 坐标系转换：GPS 原始坐标是 WGS84，国内地图 App（高德/腾讯/百度）用的是
    // GCJ02 / BD09 加密坐标，直接把 WGS84 丢给它们会有几百米偏移，这里做转换。
    // 注意：数据库里存的始终是 WGS84 原始坐标，导出 CSV 也是 WGS84。
    // ------------------------------------------------------------------

    private static final double PI = 3.14159265358979324;
    private static final double AXIS = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private static boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }

    /** WGS84 -> GCJ02（高德 / 腾讯 / 谷歌中国 使用） */
    public static double[] wgs84ToGcj02(double lat, double lon) {
        if (outOfChina(lat, lon)) return new double[]{lat, lon};
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((AXIS * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLon = (dLon * 180.0) / (AXIS / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{lat + dLat, lon + dLon};
    }

    /** WGS84 -> BD09（百度地图使用） */
    public static double[] wgs84ToBd09(double lat, double lon) {
        double[] gcj = wgs84ToGcj02(lat, lon);
        double x = gcj[1], y = gcj[0];
        double z = Math.sqrt(x * x + y * y) + 0.00002 * Math.sin(y * PI * 3000.0 / 180.0);
        double theta = Math.atan2(y, x) + 0.000003 * Math.cos(x * PI * 3000.0 / 180.0);
        return new double[]{z * Math.sin(theta) + 0.006, z * Math.cos(theta) + 0.0065};
    }

    /** 把毫秒数格式化为 "x小时y分" / "y分z秒" */
    public static String formatDuration(long millis) {
        if (millis < 0) millis = 0;
        long totalSec = millis / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) {
            return h + " 小时 " + m + " 分";
        }
        if (m > 0) {
            return m + " 分 " + s + " 秒";
        }
        return s + " 秒";
    }

    /** 距离显示：小于 1km 用米 */
    public static String formatDistance(double meters) {
        if (meters < 1000) {
            return String.format("%.0f m", meters);
        }
        return String.format("%.2f km", meters / 1000.0);
    }
}
