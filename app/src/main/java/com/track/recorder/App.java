package com.track.recorder;

import android.app.Application;
import android.util.Log;

import com.amap.api.maps.MapsInitializer;

/**
 * 高德地图 SDK 要求在初始化地图之前完成隐私合规声明，否则地图会白屏。
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            MapsInitializer.updatePrivacyShow(this, true, true);
            MapsInitializer.updatePrivacyAgree(this, true);
        } catch (Throwable e) {
            Log.w("App", "amap privacy init failed", e);
        }
    }
}
