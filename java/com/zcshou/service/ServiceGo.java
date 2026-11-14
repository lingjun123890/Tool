package com.zcshou.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.elvishew.xlog.XLog;
import com.zcshou.gogogo.MainActivity;
import com.zcshou.gogogo.R;
import com.zcshou.joystick.JoyStick;

import java.util.List;

public class ServiceGo extends Service {
    // 定位相关变量
    public static final double DEFAULT_LAT = 36.667662;
    public static final double DEFAULT_LNG = 117.027707;
    public static final double DEFAULT_ALT = 55.0D;
    public static final float DEFAULT_BEA = 0.0F;
    private double mCurLat = DEFAULT_LAT;
    private double mCurLng = DEFAULT_LNG;
    private double mCurAlt = DEFAULT_ALT;
    private float mCurBea = DEFAULT_BEA;
    private double mSpeed = 1.2;        /* 默认的速度，单位 m/s */
    private static final int HANDLER_MSG_ID = 0;
    private static final String SERVICE_GO_HANDLER_NAME = "ServiceGoLocation";
    private LocationManager mLocManager;
    private HandlerThread mLocHandlerThread;
    private Handler mLocHandler;
    private boolean isStop = false;
    // 通知栏消息
    private static final int SERVICE_GO_NOTE_ID = 1;
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW = "ShowJoyStick";
    private static final String SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE = "HideJoyStick";
    private static final String SERVICE_GO_NOTE_CHANNEL_ID = "SERVICE_GO_NOTE";
    private static final String SERVICE_GO_NOTE_CHANNEL_NAME = "SERVICE_GO_NOTE";
    private NoteActionReceiver mActReceiver;
    // 摇杆相关
    private JoyStick mJoyStick;
    // 路线模拟相关
    private volatile boolean isRouteMode = false;
    private volatile List<double[]> mRoutePoints = null;     /* WGS84 坐标 {经度, 纬度} */
    private int mRouteSegIdx = 0;
    private int mRouteTickCnt = 0;                   /* 仅用于按秒打印诊断日志 */

    /* ============ 跑步跑走交替配速引擎 ============ */
    private volatile boolean mRunWalkEnabled = false;   /* 跑步模式下跑走交替开关 */
    private volatile boolean mWalkPhase = false;        /* 当前是否处于行走段 */
    private volatile double mWalkDist = 100.0;          /* 每次行走的距离，单位 m */
    private volatile double mWalkSpeed = 1.2;           /* 行走段速度，单位 m/s */
    private volatile double mPhaseDistLeft = 0.0;       /* 当前阶段剩余距离，单位 m */
    private volatile double mPaceSegLeft = 0.0;         /* 跑步段内距下次配速重随的剩余距离，单位 m */
    /* 跑步速度随机范围（m/s，默认对应配速 5'30"-7'30"/km），启动引擎时从设置读取 */
    private volatile double mRunSpeedLow = 2.22;
    private volatile double mRunSpeedHigh = 3.03;
    /* 跑步段距离随机范围（米），启动引擎时从设置读取 */
    private volatile double mRunSegMin = 300.0;
    private volatile double mRunSegMax = 800.0;
    /* 跑步段内每 50 米重随一次配速，模拟真实配速波动 */
    private static final double RUN_PACE_WAVE_DIST = 50.0;

    private final ServiceGoBinder mBinder = new ServiceGoBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mLocManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        removeTestProviderNetwork();
        addTestProviderNetwork();

        removeTestProviderGPS();
        addTestProviderGPS();

        initGoLocation();

        initNotification();

        initJoyStick();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mCurLng = intent.getDoubleExtra(MainActivity.LNG_MSG_ID, DEFAULT_LNG);
        mCurLat = intent.getDoubleExtra(MainActivity.LAT_MSG_ID, DEFAULT_LAT);
        mCurAlt = intent.getDoubleExtra(MainActivity.ALT_MSG_ID, DEFAULT_ALT);

        mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        isStop = true;
        mLocHandler.removeMessages(HANDLER_MSG_ID);
        mLocHandlerThread.quit();

        mJoyStick.destroy();

        removeTestProviderNetwork();
        removeTestProviderGPS();

        unregisterReceiver(mActReceiver);
        stopForeground(STOP_FOREGROUND_REMOVE);

        super.onDestroy();
    }

    private void initNotification() {
        mActReceiver = new NoteActionReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        filter.addAction(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        registerReceiver(mActReceiver, filter);

        NotificationChannel mChannel = new NotificationChannel(SERVICE_GO_NOTE_CHANNEL_ID, SERVICE_GO_NOTE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (notificationManager != null) {
            notificationManager.createNotificationChannel(mChannel);
        }

        //准备intent
        Intent clickIntent = new Intent(this, MainActivity.class);
        PendingIntent clickPI = PendingIntent.getActivity(this, 1, clickIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent showIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW);
        PendingIntent showPendingPI = PendingIntent.getBroadcast(this, 0, showIntent, PendingIntent.FLAG_IMMUTABLE);
        Intent hideIntent = new Intent(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE);
        PendingIntent hidePendingPI = PendingIntent.getBroadcast(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, SERVICE_GO_NOTE_CHANNEL_ID)
                .setChannelId(SERVICE_GO_NOTE_CHANNEL_ID)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(getResources().getString(R.string.app_service_tips))
                .setContentIntent(clickPI)
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_show), showPendingPI))
                .addAction(new NotificationCompat.Action(null, getResources().getString(R.string.note_hide), hidePendingPI))
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();

        startForeground(SERVICE_GO_NOTE_ID, notification);
    }

    private void initJoyStick() {
        mJoyStick = new JoyStick(this);
        mJoyStick.setListener(new JoyStick.JoyStickClickListener() {
            @Override
            public void onMoveInfo(double speed, double disLng, double disLat, double angle) {
                mSpeed = speed;
                // 根据当前的经纬度和距离，计算下一个经纬度
                // Latitude: 1 deg = 110.574 km // 纬度的每度的距离大约为 110.574km
                // Longitude: 1 deg = 111.320*cos(latitude) km  // 经度的每度的距离从0km到111km不等
                // 具体见：http://wp.mlab.tw/?p=2200
                mCurLng += disLng / (111.320 * Math.cos(Math.abs(mCurLat) * Math.PI / 180));
                mCurLat += disLat / 110.574;
                mCurBea = (float) angle;
            }

            @Override
            public void onPositionInfo(double lng, double lat, double alt) {
                mCurLng = lng;
                mCurLat = lat;
                mCurAlt = alt;
            }

            @Override
            public void onRouteStart(double speed, List<double[]> points) {
                mSpeed = speed;
                mRoutePoints = points;
                mRouteTickCnt = 0;

                /* 第一个选点作为路线起点：位置直接放到首点，从第二个点开始行进。
                   这样开始瞬间蓝点就位于用户选定的起点，并立即沿路线移动 */
                if (points != null && points.size() >= 2) {
                    mCurLng = points.get(0)[0];
                    mCurLat = points.get(0)[1];
                    mRouteSegIdx = 1;
                } else {
                    mRouteSegIdx = 0;
                }

                isRouteMode = true;
                XLog.d("SERVICEGO ROUTE: start, points=" + (points == null ? 0 : points.size())
                        + ", speed=" + speed + "m/s, start WGS[" + mCurLng + "," + mCurLat + "]");
            }

            @Override
            public void onRouteRunConfig(boolean isRunMode, double walkDistM, double walkSpeedMps) {
                mRunWalkEnabled = isRunMode && isRouteMode;
                if (mRunWalkEnabled) {
                    loadRunWalkSettings();

                    /* 步行间隔：优先用路线窗输入框的值，为空/非法时回退到设置页默认值 */
                    mWalkDist = walkDistM > 0 ? walkDistM : mWalkDist;
                    mWalkDist = Math.max(mWalkDist, 10.0);
                    mWalkSpeed = walkSpeedMps > 0 ? walkSpeedMps : 1.2;
                    startRunPhase();
                    XLog.d("SERVICEGO RUN/WALK: enabled, walkDist=" + mWalkDist + "m, walkSpeed=" + mWalkSpeed + "m/s"
                            + ", runSpeed=" + mRunSpeedLow + "-" + mRunSpeedHigh + "m/s, runSeg=" + mRunSegMin + "-" + mRunSegMax + "m");
                } else {
                    XLog.d("SERVICEGO RUN/WALK: disabled");
                }
            }

            @Override
            public void onRouteStop() {
                isRouteMode = false;
                mRoutePoints = null;
                mRunWalkEnabled = false;
            }

            @Override
            public void onSpeedChange(double speed) {
                mSpeed = speed;
                XLog.d("SERVICEGO ROUTE: speed changed to " + speed + "m/s");
            }
        });
        mJoyStick.show();
    }

    private void initGoLocation() {
        // 创建 HandlerThread 实例，第一个参数是线程的名字
        mLocHandlerThread = new HandlerThread(SERVICE_GO_HANDLER_NAME, Process.THREAD_PRIORITY_FOREGROUND);
        // 启动 HandlerThread 线程
        mLocHandlerThread.start();
        // Handler 对象与 HandlerThread 的 Looper 对象的绑定
        mLocHandler = new Handler(mLocHandlerThread.getLooper()) {
            // 这里的Handler对象可以看作是绑定在HandlerThread子线程中，所以handlerMessage里的操作是在子线程中运行的
            @Override
            public void handleMessage(@NonNull Message msg) {
                try {
                    Thread.sleep(100);

                    if (!isStop) {
                        advanceRoute();
                        setLocationNetwork();
                        setLocationGPS();

                        sendEmptyMessage(HANDLER_MSG_ID);
                    }
                } catch (InterruptedException e) {
                    XLog.e("SERVICEGO: ERROR - handleMessage");
                    Thread.currentThread().interrupt();
                }
            }
        };

        mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
    }

    /* 沿路线推进虚拟位置，每个周期（100ms）移动 mSpeed * 0.1s 对应的距离 */
    private void advanceRoute() {
        if (!isRouteMode || mRoutePoints == null || mRoutePoints.isEmpty()) {
            return;
        }

        double step = mSpeed * 0.1;     /* 每个 tick 移动的距离，单位 m */

        if (step <= 0) {
            XLog.w("SERVICEGO ROUTE: speed<=0, cannot move (speed=" + mSpeed + ")");
            return;
        }

        /* 推进跑走交替配速状态机：跑步模式下按随机配速调速并自动切换行走段（本 tick 用旧速度走完，下一 tick 生效） */
        updateRunWalkPace(step);

        /* 朝向当前目标途经点 */
        double[] target = mRoutePoints.get(Math.min(mRouteSegIdx, mRoutePoints.size() - 1));
        if (target[0] != mCurLng || target[1] != mCurLat) {
            double dx = (target[0] - mCurLng) * 111320.0 * Math.cos(Math.abs(mCurLat) * Math.PI / 180);
            double dy = (target[1] - mCurLat) * 110574.0;
            mCurBea = (float) ((Math.toDegrees(Math.atan2(dx, dy)) + 360.0) % 360.0);
        }

        while (step > 0 && mRouteSegIdx < mRoutePoints.size()) {
            target = mRoutePoints.get(mRouteSegIdx);
            double dist = calcDistance(mCurLng, mCurLat, target[0], target[1]);

            if (dist <= step) {
                /* 本周期足够走到该途经点，继续走向下一个点 */
                mCurLng = target[0];
                mCurLat = target[1];
                step -= dist;
                mRouteSegIdx++;
            } else {
                /* 沿线段方向前进 step 米 */
                mCurLng += (target[0] - mCurLng) * step / dist;
                mCurLat += (target[1] - mCurLat) * step / dist;
                step = 0;
            }
        }

        /* 每秒打印一次当前坐标，便于在 logcat 中确认位置确实在推进 */
        if (mRouteTickCnt % 10 == 0) {
            XLog.d("SERVICEGO ROUTE: seg=" + mRouteSegIdx + "/" + mRoutePoints.size()
                    + " WGS[" + mCurLng + "," + mCurLat + "] bea=" + mCurBea);
        }
        mRouteTickCnt++;

        /* 把最新位置实时回传给摇杆路线窗，刷新地图上的定位点 */
        mJoyStick.updateRoutePosition(mCurLng, mCurLat);

        if (mRouteSegIdx >= mRoutePoints.size()) {
            isRouteMode = false;
            mRoutePoints = null;
            mRunWalkEnabled = false;
            XLog.d("SERVICEGO ROUTE: finished at WGS[" + mCurLng + "," + mCurLat + "]");
            mJoyStick.onRouteFinished();
        }
    }

    /* 从设置页读取跑步跑走交替参数（速度范围/跑步段范围/默认步行间隔） */
    private void loadRunWalkSettings() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        mRunSpeedLow = parseSettingDouble(sp, "setting_run_speed_low", mRunSpeedLow);
        mRunSpeedHigh = parseSettingDouble(sp, "setting_run_speed_high", mRunSpeedHigh);
        mRunSegMin = parseSettingDouble(sp, "setting_run_seg_min", mRunSegMin);
        mRunSegMax = parseSettingDouble(sp, "setting_run_seg_max", mRunSegMax);
        mWalkDist = parseSettingDouble(sp, "setting_run_walk_dist", mWalkDist);

        /* 校正：上限不低于下限，避免用户填反 */
        if (mRunSpeedHigh < mRunSpeedLow) {
            double tmp = mRunSpeedLow;
            mRunSpeedLow = mRunSpeedHigh;
            mRunSpeedHigh = tmp;
        }
        if (mRunSegMax < mRunSegMin) {
            double tmp = mRunSegMin;
            mRunSegMin = mRunSegMax;
            mRunSegMax = tmp;
        }
    }

    /* 读取字符串型设置并转为 double，非法时返回默认值 */
    private static double parseSettingDouble(SharedPreferences sp, String key, double defVal) {
        try {
            String val = sp.getString(key, null);
            if (val != null && !val.trim().isEmpty()) {
                double v = Double.parseDouble(val.trim());
                if (v > 0) {
                    return v;
                }
            }
        } catch (NumberFormatException ignored) {
        }
        return defVal;
    }

    /* 跑步段内随机一个速度：范围取设置页的跑步最低/最高速度 */
    private double randRunSpeed() {
        return mRunSpeedLow + Math.random() * (mRunSpeedHigh - mRunSpeedLow);
    }

    /* 进入跑步段：距离随机取设置范围内的值，速度按随机配速 */
    private void startRunPhase() {
        mWalkPhase = false;
        mPhaseDistLeft = mRunSegMin + Math.random() * (mRunSegMax - mRunSegMin);
        mPaceSegLeft = RUN_PACE_WAVE_DIST;
        mSpeed = randRunSpeed();
        XLog.d("SERVICEGO RUN/WALK: run phase, dist=" + String.format("%.0f", mPhaseDistLeft)
                + "m, speed=" + String.format("%.2f", mSpeed) + "m/s");
    }

    /* 进入行走段：距离为用户设定值，速度取步行档位 */
    private void startWalkPhase() {
        mWalkPhase = true;
        mPhaseDistLeft = mWalkDist;
        mSpeed = mWalkSpeed;
        XLog.d("SERVICEGO RUN/WALK: walk phase, dist=" + String.format("%.0f", mWalkDist) + "m");
    }

    /* 每个 tick 推进跑走交替状态机（step 为本 tick 实际移动距离，单位 m） */
    private void updateRunWalkPace(double step) {
        if (!mRunWalkEnabled || !isRouteMode) {
            return;
        }

        mPhaseDistLeft -= step;
        if (mWalkPhase) {
            /* 行走段结束 → 切回跑步段 */
            if (mPhaseDistLeft <= 0) {
                startRunPhase();
            }
        } else {
            /* 跑步段内每 50 米重随一次配速，模拟真实波动 */
            mPaceSegLeft -= step;
            if (mPaceSegLeft <= 0) {
                mPaceSegLeft += RUN_PACE_WAVE_DIST;
                mSpeed = randRunSpeed();
            }
            /* 跑步段结束 → 切换为行走段 */
            if (mPhaseDistLeft <= 0) {
                startWalkPhase();
            }
        }
    }

    /* 简易平面距离计算，单位 m，小范围（路线模拟场景）内精度足够 */
    private static double calcDistance(double lng1, double lat1, double lng2, double lat2) {
        double x = (lng2 - lng1) * 111320.0 * Math.cos(Math.abs(lat1) * Math.PI / 180);
        double y = (lat2 - lat1) * 110574.0;
        return Math.sqrt(x * x + y * y);
    }

    private void removeTestProviderGPS() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.GPS_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderGPS");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderGPS() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实GPS参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
            } else {
                mLocManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false,
                        false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderGPS");
        }
    }

    private void setLocationGPS() {
        try {
            // 尽可能模拟真实的 GPS 数据
            Location loc = new Location(LocationManager.GPS_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_FINE);    // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mCurBea);                       // 方向（度）
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            Bundle bundle = new Bundle();
            bundle.putInt("satellites", 7);
            loc.setExtras(bundle);

            mLocManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationGPS");
        }
    }

    private void removeTestProviderNetwork() {
        try {
            if (mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, false);
                mLocManager.removeTestProvider(LocationManager.NETWORK_PROVIDER);
            }
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - removeTestProviderNetwork");
        }
    }

    // 注意下面临时添加 @SuppressLint("wrongconstant") 以处理 addTestProvider 参数值的 lint 错误
    @SuppressLint("wrongconstant")
    private void addTestProviderNetwork() {
        try {
            // 注意，由于 android api 问题，下面的参数会提示错误(以下参数是通过相关API获取的真实NETWORK参数，不是随便写的)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE);
            } else {
                mLocManager.addTestProvider(LocationManager.NETWORK_PROVIDER, true, false,
                        true, true, true, true,
                        true, Criteria.POWER_LOW, Criteria.ACCURACY_COARSE);
            }
            if (!mLocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocManager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
            }
        } catch (SecurityException e) {
            XLog.e("SERVICEGO: ERROR - addTestProviderNetwork");
        }
    }

    private void setLocationNetwork() {
        try {
            // 尽可能模拟真实的 NETWORK 数据
            Location loc = new Location(LocationManager.NETWORK_PROVIDER);
            loc.setAccuracy(Criteria.ACCURACY_COARSE);  // 设定此位置的估计水平精度，以米为单位。
            loc.setAltitude(mCurAlt);                     // 设置高度，在 WGS 84 参考坐标系中的米
            loc.setBearing(mCurBea);                       // 方向（度）
            loc.setLatitude(mCurLat);                   // 纬度（度）
            loc.setLongitude(mCurLng);                  // 经度（度）
            loc.setTime(System.currentTimeMillis());    // 本地时间
            loc.setSpeed((float) mSpeed);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

            mLocManager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, loc);
        } catch (Exception e) {
            XLog.e("SERVICEGO: ERROR - setLocationNetwork");
        }
    }

    public class NoteActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_SHOW)) {
                    mJoyStick.show();
                }

                if (action.equals(SERVICE_GO_NOTE_ACTION_JOYSTICK_HIDE)) {
                    mJoyStick.hide();
                }
            }
        }
    }

    public class ServiceGoBinder extends Binder {
        public void setPosition(double lng, double lat, double alt) {
            mLocHandler.removeMessages(HANDLER_MSG_ID);
            mCurLng = lng;
            mCurLat = lat;
            mCurAlt = alt;
            mLocHandler.sendEmptyMessage(HANDLER_MSG_ID);
            mJoyStick.setCurrentPosition(mCurLng, mCurLat, mCurAlt);
        }
    }
}


