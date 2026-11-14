# 轨迹记录 · TrackRecorder

[中文](#中文) | [English](#english)

一个原生 Android 轨迹记录 App：**10 秒一个定位点**、**7×24 小时后台常驻**、本地落库、CSV 导出，内置高德真实街道底图。

- 包名：`com.track.recorder`
- 版本：`2.0`（versionCode 2）
- 最低系统：Android 8.0（API 26），targetSdk 34
- 依赖体量：无第三方框架（纯 Android Framework + 高德 3D 地图 SDK 10.0.600）

---

## 中文

### 功能

| 能力 | 说明 |
| --- | --- |
| 固定采样 | 每 10 秒写入一个轨迹点（GPS + 网络定位双 provider，8 秒去抖） |
| 7×24 后台 | 前台服务 + `foregroundServiceType=location` + `PARTIAL_WAKE_LOCK`，息屏继续采样 |
| 断点续采 | `START_STICKY`、进程被重启后按 `Prefs.tracking` 自动恢复会话 |
| 开机自启 | 监听 `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`，权限允许时自动拉起 |
| 划掉复活 | 从最近任务划掉后，`AlarmManager` 30 秒后重新拉起服务 |
| 本地存储 | SQLite 单表逐点落盘，服务被杀不丢历史 |
| CSV 导出 | Android 10+ 走 MediaStore 写入「下载/TrackRecorder」，可直接分享 |
| 内置地图 | 高德 3D 地图底图（标准图/卫星图），轨迹绿色折线叠加、当前位置标记、5 秒刷新、镜头跟随 |
| 自绘兜底图 | 不联网也能看轨迹形状（Canvas 折线 + 起终点标记） |
| 坐标跳转 | 一键把当前位置交给高德/百度/腾讯 App 打开，自动做 WGS84 → GCJ02 / BD09 转换 |
| 地址反查 | 系统 `Geocoder` 反查街道地址（机型支持时显示） |

### 界面

主界面：状态标签（记录中/已停止）、本次点数、里程、时长、最新精度、自绘轨迹画布、开始/停止、打开地图、复制坐标、导出 CSV、保活设置、清空数据。
地图页：高德底图 + 轨迹折线 + 当前位置，支持「回到当前位置」「卫星图」「用高德App打开」。

### 关键实现

- **采样节奏**：`LocationManager.requestLocationUpdates(provider, 10_000, 0f, …)` 同时订阅 `gps` 与 `network`；落库前有 8 秒最小间隔去抖，另有 10 秒心跳在回调延迟时用 90 秒内的新鲜定位补点，避免断点。
- **不丢会话**：`Prefs` 记录 `tracking` 与会话起始时间；统计（点数/里程）在进程重建时从 SQLite 重算，不依赖内存变量。
- **里程计算**：相邻点 haversine 累加，单点跳变 > 2 000 m 视为漂移丢弃。
- **坐标系**：数据库与 CSV 一律存 **WGS84**；只在展示给国内地图时转换成 GCJ02（高德/腾讯）或 BD09（百度），转换算法在 `GeoUtil` 里手写实现，不依赖任何在线服务。
- **地图合规**：`App.onCreate` 里先调 `MapsInitializer.updatePrivacyShow/updatePrivacyAgree`，否则高德 MapView 会白屏。

### 权限

| 权限 | 用途 |
| --- | --- |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 定位 |
| `ACCESS_BACKGROUND_LOCATION` | 息屏、后台持续定位（必须选「始终允许」） |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` | 前台定位服务 |
| `POST_NOTIFICATIONS` | 常驻通知（Android 13+） |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 |
| `WAKE_LOCK` | 息屏保持采样 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 申请免电池优化 |
| `WRITE_EXTERNAL_STORAGE`（仅 ≤ API 28） | 旧系统导出 CSV |
| `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | 高德底图与网络定位 |

### 构建

前置：JDK 17、Android SDK Platform 34 + Build-Tools 34.0.0。

```bash
# 1. 拉取高德 3D 地图 SDK（原生库 33 MB + 地图样式资源，未入库）
./scripts/fetch_amap_sdk.sh

# 2. 配置 SDK 路径与高德 Key
cat > local.properties <<'EOF'
sdk.dir=/path/to/Android/sdk
amap.key=你的高德Android平台Key
EOF

# 3. 可选：配置签名（没有则打出未签名包）
cat > keystore.properties <<'EOF'
storeFile=../keystore/release.jks
storePassword=***
keyAlias=***
keyPassword=***
EOF

# 4. 打包
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

高德 Key 也可以走环境变量：`AMAP_KEY=xxx ./gradlew assembleRelease`。

**申请 Key**：高德开放平台 → 应用管理 → 创建应用 → 添加 Key，平台选 **Android**，填 `PackageName = com.track.recorder` 和你自己签名证书的 SHA1。

```bash
keytool -list -v -keystore release.jks -alias yourAlias | grep SHA1
```

Key 与「包名 + 签名 SHA1」强绑定：换签名就要重新申请，否则地图会白屏（高德错误码 32）。

### 保活设置（很重要）

代码能做的都做了（前台服务 + 唤醒锁 + 开机自启 + 划掉复活），但国内 ROM 还需要手动放行：

1. 定位权限选 **始终允许**
2. 系统设置里关掉本应用的**电池优化 / 智能省电**
3. 「自启动管理」允许本应用自启动
4. 允许后台弹出界面、后台运行不限制（小米/华为/OPPO/vivo 各有入口）

连续 GPS 定位耗电较大，长时间记录建议插电或带充电宝。

### 数据与隐私

- 所有轨迹点只存在本机 SQLite（`/data/data/com.track.recorder/databases/track.db`），不联网上传。
- 地图页联网仅用于加载高德瓦片；高德 SDK 会收集设备信息（已在启动时做过隐私合规声明）。
- 导出文件落在公共「下载/TrackRecorder」目录，卸载 App 不会删除已导出的 CSV。

### 目录结构

```
TrackRecorder/
├── app/src/main/java/com/track/recorder/
│   ├── TrackService.java     # 前台定位服务：10 秒采样、通知、唤醒锁、复活
│   ├── MainActivity.java     # 主界面：权限链、统计、导出、保活入口
│   ├── MapActivity.java      # 高德地图页：底图 + 轨迹折线 + 当前位置
│   ├── TrackMapView.java     # 自绘轨迹画布（无地图依赖）
│   ├── TrackDb.java          # SQLite 读写与统计
│   ├── TrackPoint.java       # 数据模型
│   ├── GeoUtil.java          # haversine、WGS84→GCJ02→BD09
│   ├── CsvExporter.java      # CSV 导出（MediaStore / 旧版文件）
│   ├── Prefs.java            # 会话状态
│   ├── BootReceiver.java     # 开机 / 更新 / 闹钟复活
│   └── App.java              # 高德隐私合规初始化
├── app/src/main/res/         # 布局、配色、图标
└── scripts/fetch_amap_sdk.sh # 拉取高德 SDK 二进制
```

### 已知限制

- 系统在信号极差时可能长时间拿不到定位，此时不会伪造点，只是停止落库并提示「信号弱」。
- 部分 ROM 的强制清理仍可能杀掉服务；复活机制有 30 秒左右延迟。
- 高德 SDK 未纳入仓库（33 MB 原生库），首次构建需联网执行脚本；商用请遵守高德开放平台服务条款。

---

## English

A native Android GPS track recorder: **one location point every 10 seconds**, **24/7 background operation**, local SQLite storage, CSV export, and an embedded AMap (AutoNavi) street basemap.

- Package: `com.track.recorder`
- Version: `2.0` (versionCode 2)
- Min SDK 26 (Android 8.0), targetSdk 34
- No third-party frameworks — plain Android Framework plus AMap 3D Map SDK 10.0.600

### Features

| Capability | Detail |
| --- | --- |
| Fixed cadence | One point every 10 s, subscribing to both `gps` and `network` providers with an 8 s de-bounce |
| 24/7 background | Foreground service with `foregroundServiceType=location` + `PARTIAL_WAKE_LOCK`; keeps sampling with the screen off |
| Crash/restart recovery | `START_STICKY` plus persisted `tracking` flag; session statistics are recomputed from SQLite |
| Boot start | `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` receivers restart tracking |
| Swipe-away recovery | `AlarmManager` revives the service ~30 s after the task is removed from Recents |
| Local storage | SQLite, one row per fix, written immediately |
| CSV export | Android 10+ writes to `Download/TrackRecorder` via MediaStore and can be shared instantly |
| Built-in map | AMap 3D map (normal / satellite), green polyline overlay, current-position marker, 5 s refresh, camera follows |
| Offline canvas | A dependency-free Canvas view draws the trail even with no network |
| External map jump | Hands the current point to AMap / Baidu / Tencent apps with proper WGS84 → GCJ02 / BD09 conversion |
| Reverse geocode | System `Geocoder` when the ROM supports it |

### Implementation notes

- **Cadence**: `requestLocationUpdates(provider, 10_000, 0f, …)` on both providers; a 10 s heartbeat stores a fresh (< 90 s) fix when callbacks are delayed, so the series stays dense.
- **Mileage**: haversine accumulation between consecutive points; single hops over 2 000 m are treated as drift and dropped.
- **Coordinate systems**: WGS84 everywhere in storage and CSV; conversion to GCJ02/BD09 only for display, implemented locally in `GeoUtil`.
- **Map compliance**: `MapsInitializer.updatePrivacyShow/updatePrivacyAgree` is called in `Application.onCreate`, otherwise the MapView renders blank.

### Permissions

Location (fine/coarse/background), foreground service + location type, notifications, boot completed, wake lock, ignore-battery-optimizations, legacy storage (API ≤ 28), and network access for map tiles.

### Build

Prerequisites: JDK 17, Android SDK Platform 34, Build-Tools 34.0.0.

```bash
# 1. Fetch the AMap SDK binaries (33 MB native libs are not committed)
./scripts/fetch_amap_sdk.sh

# 2. Configure SDK path and your AMap key
cat > local.properties <<'EOF'
sdk.dir=/path/to/Android/sdk
amap.key=YOUR_AMAP_ANDROID_KEY
EOF

# 3. Optional signing
cat > keystore.properties <<'EOF'
storeFile=../keystore/release.jks
storePassword=***
keyAlias=***
keyPassword=***
EOF

# 4. Build
./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

You can also pass the key through the environment: `AMAP_KEY=xxx ./gradlew assembleRelease`.

**Registering the key**: AMap Open Platform → create an app → add a Key, platform **Android**, with `PackageName = com.track.recorder` and the SHA1 of *your* signing certificate:

```bash
keytool -list -v -keystore release.jks -alias yourAlias | grep SHA1
```

The key is bound to package name + SHA1; changing the signing key requires a new key, otherwise the map shows a blank screen (AMap error code 32).

### Keeping it alive on Chinese ROMs

1. Location permission → **Always allow**
2. Disable battery optimization / smart power saving for this app
3. Enable auto-start in the vendor's "auto-start manager"
4. Allow background activity and unrestricted background running

Continuous GPS is power hungry — plug in for long sessions.

### Privacy

Track points never leave the device (SQLite only). The map screen only fetches AMap tiles; the AMap SDK collects device info and the privacy-compliant init is performed at startup.

### Known limitations

- With a very poor signal no fix is produced; the app reports "weak signal" rather than fabricating points.
- Aggressive ROM cleanup can still kill the service; the revival path needs ~30 s.
- The AMap native libraries are fetched by script instead of being committed; commercial use must follow AMap's terms of service.
