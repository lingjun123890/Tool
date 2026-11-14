package com.zcshou.joystick;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.SearchView;

import androidx.preference.PreferenceManager;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.LocationSource;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.maps.model.MyLocationStyle;
import com.amap.api.maps.model.Polyline;
import com.amap.api.maps.model.PolylineOptions;
import com.amap.api.services.core.AMapException;
import com.amap.api.services.help.Inputtips;
import com.amap.api.services.help.InputtipsQuery;
import com.amap.api.services.help.Tip;
import com.zcshou.database.DataBaseHistoryLocation;
import com.zcshou.gogogo.HistoryActivity;
import com.zcshou.gogogo.MainActivity;
import com.zcshou.gogogo.R;
import com.zcshou.utils.GoUtils;
import com.zcshou.utils.MapUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JoyStick extends View {
    private static final int DivGo = 1000;    /* 移动的时间间隔，单位 ms */
    private static final int WINDOW_TYPE_JOYSTICK = 0;
    private static final int WINDOW_TYPE_MAP = 1;
    private static final int WINDOW_TYPE_HISTORY = 2;
    private static final int WINDOW_TYPE_ROUTE = 3;
    private static final int WINDOW_TYPE_ROUTE_MIN = 4;   /* 路线窗缩小为悬浮球 */

    private final Context mContext;
    private WindowManager.LayoutParams mWindowParamCurrent;
    private WindowManager mWindowManager;
    private int mCurWin = WINDOW_TYPE_JOYSTICK;
    private final LayoutInflater inflater;
    private boolean isWalk;
    private ImageButton btnWalk;
    private boolean isRun;
    private ImageButton btnRun;
    private boolean isBike;
    private ImageButton btnBike;
    private JoyStickClickListener mListener;

    // 移动
    private View mJoystickLayout;
    private GoUtils.TimeCount mTimer;
    private boolean isMove;
    private double mSpeed = 1.2;        /* 默认的速度，单位 m/s */
    private double mAltitude = 55.0;
    private double mAngle = 0;
    private double mR = 0;
    private double disLng = 0;
    private double disLat = 0;
    private final SharedPreferences sharedPreferences;
    /* 历史记录悬浮窗相关 */
    private FrameLayout mHistoryLayout;
    private final List<Map<String, Object>> mAllRecord = new ArrayList<> ();
    private TextView noRecordText;
    private ListView mRecordListView;
    /* 地图悬浮窗相关 */
    private FrameLayout mMapLayout;
    private MapView mMapView;
    private AMap mAMap;
    private LatLng mCurMapLngLat;
    private LatLng mMarkMapLngLat;
    private Marker mMapMarker;              /* 摇杆地图上当前标定位置的 Marker */
    private Inputtips mInputtips;
    private ListView mSearchList;
    private LinearLayout mSearchLayout;
    /* 定位源监听，用于把当前虚拟位置推送给地图定位图层显示蓝点 */
    private LocationSource.OnLocationChangedListener mMapLocListener;
    /* 路线模拟悬浮窗相关 */
    private FrameLayout mRouteLayout;
    /* 路线窗缩小后的悬浮球 */
    private FrameLayout mFloatBallLayout;
    private WindowManager.LayoutParams mFloatBallParam;
    private MapView mRouteMapView;
    private AMap mRouteAMap;
    private LocationSource.OnLocationChangedListener mRouteLocListener;
    private final List<LatLng> mRoutePoints = new ArrayList<>();
    /* 转角平滑：转角处用二次贝塞尔曲线过渡的切角距离（米），避免直角急转弯 */
    private static final double ROUTE_SMOOTH_DIST = 15.0;
    private Polyline mRoutePolyline;        /* 路线连线，增量复用避免闪烁 */
    private Marker mRouteStartMarker;       /* 路线起点标记，增量复用避免闪烁 */
    private long mLastRouteClickTime = 0;   /* 上次点击时间戳，防止 onMapClick/onPOIClick 双回调重复加点 */
    private ImageButton routeBtnWalk, routeBtnRun, routeBtnBike;
    private LinearLayout mRunConfigBar;         /* 跑步跑走交替设置行（仅跑步模式显示） */
    private EditText mRouteWalkDistInput;       /* 步行间隔输入框，单位米 */
    /* 画笔模式：手指在地图上划动直接绘制路线 */
    private View mRoutePenLayer;                /* 覆盖在地图上的透明触摸层 */
    private ImageButton mRoutePenBtn;           /* 画笔模式开关按钮 */
    private boolean isRoutePenMode;
    private static final double PEN_MIN_DIST = 8.0;   /* 画笔采样最小间距（米），避免点列过密 */
    private static final int PEN_MAX_POINTS = 2000;   /* 画笔最多采样点数，防止点列无限膨胀 */
    private boolean isRouteRunning;
    private boolean mRouteMapInited;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public JoyStick(Context context) {
        super(context);
        this.mContext = context;

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);

        initWindowManager();

        inflater = LayoutInflater.from(mContext);

        if (inflater != null) {
            initJoyStickView();

            initJoyStickMapView();

            initHistoryView();

            initRouteView();

            initFloatBall();
        }
    }

    public JoyStick(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.mContext = context;

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);

        initWindowManager();

        inflater = LayoutInflater.from(mContext);

        if (inflater != null) {
            initJoyStickView();

            initJoyStickMapView();

            initHistoryView();

            initRouteView();

            initFloatBall();
        }
    }

    public JoyStick(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.mContext = context;

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);

        initWindowManager();

        inflater = LayoutInflater.from(mContext);

        if (inflater != null) {
            initJoyStickView();

            initJoyStickMapView();

            initHistoryView();

            initRouteView();

            initFloatBall();
        }
    }

    public void setCurrentPosition(double lng, double lat, double alt) {
        double[] lngLat = MapUtils.wgs2gcj(lng, lat);
        mCurMapLngLat = new LatLng(lngLat[1], lngLat[0]);
        mAltitude = alt;

        resetMap();
    }

    public void show() {
        switch (mCurWin) {
            case WINDOW_TYPE_MAP:
                removeOtherWindows(null);
                if (mMapLayout.getParent() == null) {
                    resetMap();
                    ensureWindowInScreen();
                    mWindowManager.addView(mMapLayout, mWindowParamCurrent);
                    resumeMap();
                }
                break;
            case WINDOW_TYPE_HISTORY:
                removeOtherWindows(null);
                if (mHistoryLayout.getParent() == null) {
                    ensureWindowInScreen();
                    mWindowManager.addView(mHistoryLayout, mWindowParamCurrent);
                }
                break;
            case WINDOW_TYPE_ROUTE:
                removeOtherWindows(null);
                if (mRouteLayout.getParent() == null) {
                    ensureWindowInScreen();
                    mWindowManager.addView(mRouteLayout, mWindowParamCurrent);
                    resumeRouteMap();
                }
                break;
            case WINDOW_TYPE_ROUTE_MIN:
                /* 路线窗缩小为悬浮球：点击浮球恢复完整路线窗，模拟运行不受影响 */
                removeOtherWindows(mFloatBallLayout);
                if (mFloatBallLayout.getParent() == null) {
                    mWindowManager.addView(mFloatBallLayout, mFloatBallParam);
                }
                break;
            case WINDOW_TYPE_JOYSTICK:
                removeOtherWindows(null);
                if (mJoystickLayout.getParent() == null) {
                    mWindowManager.addView(mJoystickLayout, mWindowParamCurrent);
                }
                break;
        }
    }

    /* 移除除 keep 外的所有悬浮窗（keep 为 null 时全部移除），并暂停对应地图的渲染 */
    private void removeOtherWindows(View keep) {
        if (keep != mMapLayout && mMapLayout.getParent() != null) {
            mWindowManager.removeView(mMapLayout);
            pauseMap();
        }
        if (keep != mJoystickLayout && mJoystickLayout.getParent() != null) {
            mWindowManager.removeView(mJoystickLayout);
        }
        if (keep != mHistoryLayout && mHistoryLayout.getParent() != null) {
            mWindowManager.removeView(mHistoryLayout);
        }
        if (keep != mRouteLayout && mRouteLayout.getParent() != null) {
            mWindowManager.removeView(mRouteLayout);
            pauseRouteMap();
        }
        if (keep != mFloatBallLayout && mFloatBallLayout != null && mFloatBallLayout.getParent() != null) {
            mWindowManager.removeView(mFloatBallLayout);
        }
    }

    /* 防止悬浮窗超出屏幕：按布局固定 dp 尺寸收紧位置参数。
       注意不能用 measure() 实测，否则会触发高德地图 surface 重建，导致覆盖物（标记/路线）丢失 */
    private void ensureWindowInScreen() {
        if (mCurWin != WINDOW_TYPE_MAP && mCurWin != WINDOW_TYPE_HISTORY && mCurWin != WINDOW_TYPE_ROUTE) {
            return;
        }

        Point screenSize = new Point();
        mWindowManager.getDefaultDisplay().getSize(screenSize);
        float density = getResources().getDisplayMetrics().density;
        /* 三个悬浮窗布局均为 300dp × 500dp */
        int w = (int) (300 * density + 0.5f);
        int h = (int) (500 * density + 0.5f);

        if (mWindowParamCurrent.x + w > screenSize.x) {
            mWindowParamCurrent.x = Math.max(0, screenSize.x - w);
        }
        if (mWindowParamCurrent.y + h > screenSize.y) {
            mWindowParamCurrent.y = Math.max(0, screenSize.y - h);
        }
    }

    public void hide() {
        if (mMapLayout.getParent() != null) {
            mWindowManager.removeViewImmediate(mMapLayout);
            pauseMap();
        }

        if (mJoystickLayout.getParent() != null) {
            mWindowManager.removeViewImmediate(mJoystickLayout);
        }

        if (mHistoryLayout.getParent() != null) {
            mWindowManager.removeViewImmediate(mHistoryLayout);
        }

        if (mRouteLayout.getParent() != null) {
            mWindowManager.removeViewImmediate(mRouteLayout);
            pauseRouteMap();
        }

        if (mFloatBallLayout != null && mFloatBallLayout.getParent() != null) {
            mWindowManager.removeViewImmediate(mFloatBallLayout);
        }
    }

    public void destroy() {
        if (mMapLayout.getParent() != null) {
            mWindowManager.removeViewImmediate(mMapLayout);
        }

        if (mJoystickLayout.getParent() != null) {
            mWindowManager.removeViewImmediate(mJoystickLayout);
        }

        if (mHistoryLayout.getParent() != null) {
            mWindowManager.removeViewImmediate(mHistoryLayout);
        }

        if (mRouteLayout.getParent() != null) {
            mWindowManager.removeViewImmediate(mRouteLayout);
        }

        if (mFloatBallLayout != null && mFloatBallLayout.getParent() != null) {
            mWindowManager.removeViewImmediate(mFloatBallLayout);
        }

        if (mAMap != null) {
            mAMap.setMyLocationEnabled(false);
        }
        if (mMapView != null) {
            mMapView.onDestroy();
        }
        if (mRouteAMap != null) {
            mRouteAMap.setMyLocationEnabled(false);
        }
        if (mRouteMapView != null) {
            mRouteMapView.onDestroy();
        }
    }

    public void setListener(JoyStickClickListener mListener) {
        this.mListener = mListener;
    }

    private void initWindowManager() {
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mWindowParamCurrent = new WindowManager.LayoutParams();
        mWindowParamCurrent.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        mWindowParamCurrent.format = PixelFormat.RGBA_8888;
        mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE      // 不添加这个将导致游戏无法启动（MIUI12）,添加之后导致键盘无法显示
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        mWindowParamCurrent.gravity = Gravity.START | Gravity.TOP;
        mWindowParamCurrent.width = WindowManager.LayoutParams.WRAP_CONTENT;
        mWindowParamCurrent.height = WindowManager.LayoutParams.WRAP_CONTENT;
        mWindowParamCurrent.x = 300;
        mWindowParamCurrent.y = 300;
    }

    @SuppressLint("InflateParams")
    private void initJoyStickView() {
        /* 移动计时器 */
        mTimer = new GoUtils.TimeCount(DivGo, DivGo);
        mTimer.setListener(new GoUtils.TimeCount.TimeCountListener() {
            @Override
            public void onTick(long millisUntilFinished) {

            }

            @Override
            public void onFinish() {
                // 注意：这里的 x y 与 圆中角度的对应问题（以 X 轴正向为 0 度）且转换为 km
                disLng = mSpeed * (double)(DivGo / 1000) * mR * Math.cos(mAngle * 2 * Math.PI / 360) / 1000;// 注意安卓中的三角函数使用的是弧度
                disLat = mSpeed * (double)(DivGo / 1000) * mR * Math.sin(mAngle * 2 * Math.PI / 360) / 1000;// 注意安卓中的三角函数使用的是弧度
                mListener.onMoveInfo(mSpeed, disLng, disLat, 90.0F-mAngle);
                mTimer.start();
            }
        });
        // 获取参数区设置的速度
        try {
            mSpeed = Double.parseDouble(sharedPreferences.getString("setting_walk", getResources().getString(R.string.setting_walk_default)));
        } catch (NumberFormatException e) {  // GOOD: The exception is caught.
            mSpeed = 1.2;
        }
        mJoystickLayout = inflater.inflate(R.layout.joystick, null);

        /* 整个摇杆拖动事件处理 */
        mJoystickLayout.setOnTouchListener(new JoyStickOnTouchListener());

        /* 历史按钮点击事件处理 */
        ImageButton btnHistory = mJoystickLayout.findViewById(R.id.joystick_history);
        btnHistory.setOnClickListener(v -> {
            if (mHistoryLayout.getParent() == null) {
                mCurWin = WINDOW_TYPE_HISTORY;
                show();
            }
        });

        /* 路线按钮点击事件处理 */
        ImageButton btnRoute = mJoystickLayout.findViewById(R.id.joystick_route);
        btnRoute.setOnClickListener(v -> {
            if (mRouteLayout.getParent() == null) {
                /* 首次打开路线悬浮窗时，将地图定位到当前虚拟位置 */
                if (!mRouteMapInited && mCurMapLngLat != null) {
                    CameraPosition cameraPosition = new CameraPosition.Builder()
                            .target(mCurMapLngLat).zoom(16.0f).build();
                    mRouteAMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
                    mRouteMapInited = true;
                }

                mCurWin = WINDOW_TYPE_ROUTE;
                show();
            }
        });

        /* 步行按键的点击处理 */
        btnWalk = mJoystickLayout.findViewById(R.id.joystick_walk);
        btnWalk.setOnClickListener(v -> {
            if (!isWalk) {
                btnWalk.setColorFilter(getResources().getColor(R.color.colorAccent, mContext.getTheme()));
                isWalk = true;
                btnRun.setColorFilter(getResources().getColor(R.color.black, mContext.getTheme()));
                isRun = false;
                btnBike.setColorFilter(getResources().getColor(R.color.black, mContext.getTheme()));
                isBike = false;
                try {
                    mSpeed = Double.parseDouble(sharedPreferences.getString("setting_walk", getResources().getString(R.string.setting_walk_default)));
                } catch (NumberFormatException e) {  // GOOD: The exception is caught.
                    mSpeed = 1.2;
                }
                refreshSpeedButtons();
            }
        });
        /* 默认为步行 */
        isWalk = true;
        btnWalk.setColorFilter(getResources().getColor(R.color.colorAccent, mContext.getTheme()));
        /* 跑步按键的点击处理 */
        isRun = false;
        btnRun = mJoystickLayout.findViewById(R.id.joystick_run);
        btnRun.setOnClickListener(v -> {
            if (!isRun) {
                btnRun.setColorFilter(getResources().getColor(R.color.colorAccent, mContext.getTheme()));
                isRun = true;
                btnWalk.setColorFilter(getResources().getColor(R.color.black, mContext.getTheme()));
                isWalk = false;
                btnBike.setColorFilter(getResources().getColor(R.color.black, mContext.getTheme()));
                isBike = false;
                try {
                    mSpeed = Double.parseDouble(sharedPreferences.getString("setting_run", getResources().getString(R.string.setting_run_default)));
                } catch (NumberFormatException e) {  // GOOD: The exception is caught.
                    mSpeed = 3.6;
                }
                refreshSpeedButtons();
            }
        });
        /* 自行车按键的点击处理 */
        isBike = false;
        btnBike = mJoystickLayout.findViewById(R.id.joystick_bike);
        btnBike.setOnClickListener(v -> {
            if (!isBike) {
                btnBike.setColorFilter(getResources().getColor(R.color.colorAccent, mContext.getTheme()));
                isBike = true;
                btnWalk.setColorFilter(getResources().getColor(R.color.black, mContext.getTheme()));
                isWalk = false;
                btnRun.setColorFilter(getResources().getColor(R.color.black, mContext.getTheme()));
                isRun = false;
                try {
                    mSpeed = Double.parseDouble(sharedPreferences.getString("setting_bike", getResources().getString(R.string.setting_bike_default)));
                } catch (NumberFormatException e) {  // GOOD: The exception is caught.
                    mSpeed = 10.0;
                }
                refreshSpeedButtons();
            }
        });
        /* 方向键点击处理 */
        RockerView rckView = mJoystickLayout.findViewById(R.id.joystick_rocker);
        rckView.setListener(this::processDirection);

        /* 方向键点击处理 */
        ButtonView btnView = mJoystickLayout.findViewById(R.id.joystick_button);
        btnView.setListener(this::processDirection);

        /* 这里用来决定摇杆类型 */
        if (sharedPreferences.getString("setting_joystick_type", "0").equals("0")) {
            rckView.setVisibility(VISIBLE);
            btnView.setVisibility(GONE);
        } else {
            rckView.setVisibility(GONE);
            btnView.setVisibility(VISIBLE);
        }
    }

    private void processDirection(boolean auto, double angle, double r) {
        if (isRouteRunning) {
            return;
        }

        if (r <= 0) {
            mTimer.cancel();
            isMove = false;
        } else {
            mAngle = angle;
            mR = r;
            if (auto) {
                if (!isMove) {
                    mTimer.start();
                    isMove = true;
                }
            } else {
                mTimer.cancel();
                isMove = false;
                // 注意：这里的 x y 与 圆中角度的对应问题（以 X 轴正向为 0 度）且转换为 km
                disLng = mSpeed * (double)(DivGo / 1000) * mR * Math.cos(mAngle * 2 * Math.PI / 360) / 1000;// 注意安卓中的三角函数使用的是弧度
                disLat = mSpeed * (double)(DivGo / 1000) * mR * Math.sin(mAngle * 2 * Math.PI / 360) / 1000;// 注意安卓中的三角函数使用的是弧度
                mListener.onMoveInfo(mSpeed, disLng, disLat, 90.0F-mAngle);
            }
        }
    }

    private class JoyStickOnTouchListener implements OnTouchListener {
        private int x;
        private int y;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    x = (int) event.getRawX();
                    y = (int) event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    int nowX = (int) event.getRawX();
                    int nowY = (int) event.getRawY();
                    int movedX = nowX - x;
                    int movedY = nowY - y;
                    x = nowX;
                    y = nowY;

                    mWindowParamCurrent.x += movedX;
                    mWindowParamCurrent.y += movedY;
                    mWindowManager.updateViewLayout(view, mWindowParamCurrent);
                    break;
                case MotionEvent.ACTION_UP:
                    view.performClick();
                    break;
                default:
                    break;
            }
            return false;
        }
    }

    public interface JoyStickClickListener {
        void onMoveInfo(double speed, double disLng, double disLat, double angle);
        void onPositionInfo(double lng, double lat, double alt);
        void onRouteStart(double speed, List<double[]> points);
        void onRouteStop();
        /* 路线运行配置：跑步模式下启用跑走交替（isRunMode=false 时忽略后两个参数） */
        void onRouteRunConfig(boolean isRunMode, double walkDistM, double walkSpeedMps);
        /* 路线模拟运行中切换速度（步行/跑步/骑行）时实时同步给服务 */
        void onSpeedChange(double speed);
    }

    /* ---------------- 路线模拟悬浮窗相关 ---------------- */

    @SuppressLint({"InflateParams", "ClickableViewAccessibility"})
    private void initRouteView() {
        mRouteLayout = (FrameLayout)inflater.inflate(R.layout.joystick_route, null);
        mRouteLayout.setOnTouchListener(new JoyStickOnTouchListener());

        mRouteMapView = mRouteLayout.findViewById(R.id.map_route);
        mRouteMapView.onCreate(null);
        mRouteAMap = mRouteMapView.getMap();
        mRouteAMap.getUiSettings().setZoomControlsEnabled(false);
        mRouteAMap.setMapType(AMap.MAP_TYPE_NORMAL);
        // 配置定位图层，用于在路线模拟过程中显示实时移动的蓝色定位点
        mRouteAMap.setLocationSource(new LocationSource() {
            @Override
            public void activate(LocationSource.OnLocationChangedListener listener) {
                mRouteLocListener = listener;
            }

            @Override
            public void deactivate() {
                mRouteLocListener = null;
            }
        });
        MyLocationStyle routeLocStyle = new MyLocationStyle();
        routeLocStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_SHOW);
        routeLocStyle.showMyLocation(true);
        mRouteAMap.setMyLocationStyle(routeLocStyle);
        mRouteAMap.setMyLocationEnabled(true);
        // 单击地图或地图上的 POI 点，添加一个途经点
        mRouteAMap.setOnMapClickListener(this::addRoutePoint);
        mRouteAMap.setOnPOIClickListener(poi -> addRoutePoint(poi.getCoordinate()));

        ImageButton btnClose = mRouteLayout.findViewById(R.id.route_close);
        btnClose.setOnClickListener(v -> {
            if (isRouteRunning) {
                stopRoute(true);
            }

            mCurWin = WINDOW_TYPE_JOYSTICK;
            show();
        });

        ImageButton btnUndo = mRouteLayout.findViewById(R.id.route_undo);
        btnUndo.setOnClickListener(v -> removeLastRoutePoint());

        /* 画笔模式：开启后地图手势关闭，透明画布接管触摸，把手指轨迹转成路线点 */
        mRoutePenBtn = mRouteLayout.findViewById(R.id.route_pen);
        mRoutePenLayer = mRouteLayout.findViewById(R.id.route_pen_layer);
        mRoutePenBtn.setOnClickListener(v -> setPenMode(!isRoutePenMode));
        mRoutePenLayer.setOnTouchListener((v, event) -> handlePenTouch(v, event));

        /* 缩小按钮：把路线窗收起为悬浮球，模拟运行中也可缩小 */
        ImageButton btnMin = mRouteLayout.findViewById(R.id.route_min);
        btnMin.setOnClickListener(v -> {
            mCurWin = WINDOW_TYPE_ROUTE_MIN;
            show();
        });

        ImageButton btnStart = mRouteLayout.findViewById(R.id.route_start);
        btnStart.setOnClickListener(v -> {
            if (isRouteRunning) {
                stopRoute(true);
            } else {
                startRoute();
            }
        });

        /* 速度模式按钮（步行/跑步/骑行），与摇杆窗共用同一套状态和速度设置 */
        routeBtnWalk = mRouteLayout.findViewById(R.id.route_walk);
        routeBtnRun = mRouteLayout.findViewById(R.id.route_run);
        routeBtnBike = mRouteLayout.findViewById(R.id.route_bike);
        routeBtnWalk.setOnClickListener(v -> selectSpeedMode(0));
        routeBtnRun.setOnClickListener(v -> selectSpeedMode(1));
        routeBtnBike.setOnClickListener(v -> selectSpeedMode(2));
        mRunConfigBar = mRouteLayout.findViewById(R.id.route_run_config);
        mRouteWalkDistInput = mRouteLayout.findViewById(R.id.route_walk_dist);
        /* 步行间隔输入框默认值取设置页「跑后步行间隔」 */
        try {
            String defDist = sharedPreferences.getString("setting_run_walk_dist", "100");
            mRouteWalkDistInput.setHint(defDist);
        } catch (Exception ignored) {
        }
        refreshSpeedButtons();

        updateRouteStartBtn();
    }

    /* 0=步行 1=跑步 2=骑行：与摇杆窗三处状态保持一致，路线运行中切换则实时同步速度给服务 */
    private void selectSpeedMode(int mode) {
        isWalk = mode == 0;
        isRun = mode == 1;
        isBike = mode == 2;
        mSpeed = getSpeedByCurrentMode();
        refreshSpeedButtons();

        /* 跑走交替设置行仅在跑步模式下显示 */
        if (mRunConfigBar != null) {
            mRunConfigBar.setVisibility(isRun ? View.VISIBLE : View.GONE);
        }

        if (isRouteRunning) {
            mListener.onSpeedChange(mSpeed);
            /* 运行中切换到跑步模式时同步跑走交替配置 */
            mListener.onRouteRunConfig(isRun, parseWalkDist(), getWalkSpeed());
        }
    }

    /* 同步刷新摇杆窗与路线窗共六个速度按钮的高亮颜色 */
    private void refreshSpeedButtons() {
        int accent = getResources().getColor(R.color.colorAccent, mContext.getTheme());
        int normal = getResources().getColor(R.color.black, mContext.getTheme());
        btnWalk.setColorFilter(isWalk ? accent : normal);
        btnRun.setColorFilter(isRun ? accent : normal);
        btnBike.setColorFilter(isBike ? accent : normal);
        if (routeBtnWalk != null) routeBtnWalk.setColorFilter(isWalk ? accent : normal);
        if (routeBtnRun != null) routeBtnRun.setColorFilter(isRun ? accent : normal);
        if (routeBtnBike != null) routeBtnBike.setColorFilter(isBike ? accent : normal);
    }

    private void addRoutePoint(LatLng latLng) {
        if (isRouteRunning) {
            return;
        }

        /* 300ms 防抖：同一次点击可能触发 onMapClick 与 onPOIClick 双回调，避免重复加点 */
        long now = System.currentTimeMillis();
        if (now - mLastRouteClickTime < 300) {
            return;
        }
        mLastRouteClickTime = now;

        mRoutePoints.add(latLng);
        redrawRoute();
    }

    private void removeLastRoutePoint() {
        if (isRouteRunning || mRoutePoints.isEmpty()) {
            return;
        }

        mRoutePoints.remove(mRoutePoints.size() - 1);
        redrawRoute();
    }

    /* 画笔模式开关：开启后关闭地图手势，由透明画布接管触摸；路线运行中不允许开启 */
    private void setPenMode(boolean enabled) {
        if (enabled && isRouteRunning) {
            GoUtils.DisplayToast(mContext, getResources().getString(R.string.app_route_pen_running));
            return;
        }

        isRoutePenMode = enabled;
        if (mRoutePenLayer != null) {
            mRoutePenLayer.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
        if (mRouteAMap != null) {
            mRouteAMap.getUiSettings().setAllGesturesEnabled(!enabled);
        }
        if (mRoutePenBtn != null) {
            int color = enabled
                    ? getResources().getColor(R.color.colorAccent, mContext.getTheme())
                    : getResources().getColor(R.color.black, mContext.getTheme());
            mRoutePenBtn.setColorFilter(color);
        }
    }

    /* 画笔触摸：把手指位置实时换算成经纬度并追加到路线点列，抬手后按平滑点列重绘 */
    private boolean handlePenTouch(View view, MotionEvent event) {
        if (!isRoutePenMode || isRouteRunning) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                view.performClick();
                appendPenPoint(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                appendPenPoint(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                /* 拖动结束：按转角平滑后的点列重绘，保证画线与实际轨迹一致 */
                redrawRoute();
                return true;
            default:
                return true;
        }
    }

    /* 屏幕坐标转经纬度后追加路线点：与上一个点间距不足 PEN_MIN_DIST 米则跳过，控制点数量 */
    private void appendPenPoint(float x, float y) {
        if (mRouteAMap == null || mRoutePoints.size() >= PEN_MAX_POINTS) {
            return;
        }

        LatLng latLng = mRouteAMap.getProjection().fromScreenLocation(new Point((int) x, (int) y));
        if (latLng == null) {
            return;
        }

        if (!mRoutePoints.isEmpty()) {
            LatLng last = mRoutePoints.get(mRoutePoints.size() - 1);
            double cosLat = Math.cos(Math.toRadians(last.latitude));
            double dx = (latLng.longitude - last.longitude) * 111320.0 * cosLat;
            double dy = (latLng.latitude - last.latitude) * 110574.0;
            if (dx * dx + dy * dy < PEN_MIN_DIST * PEN_MIN_DIST) {
                return;
            }
        }

        mRoutePoints.add(latLng);
        previewPenStroke();
    }

    /* 画笔拖动中的即时预览：直接连原始采样点，保证跟手流畅（抬手后再做平滑重绘） */
    private void previewPenStroke() {
        if (!mRoutePoints.isEmpty()) {
            if (mRouteStartMarker == null) {
                mRouteStartMarker = mRouteAMap.addMarker(new MarkerOptions()
                        .position(mRoutePoints.get(0)).icon(MainActivity.getMapIndicator()));
            } else {
                mRouteStartMarker.setPosition(mRoutePoints.get(0));
            }
        }

        if (mRoutePoints.size() > 1) {
            if (mRoutePolyline == null) {
                mRoutePolyline = mRouteAMap.addPolyline(new PolylineOptions()
                        .addAll(mRoutePoints)
                        .width(8)
                        .color(0xB32196F3));
            } else {
                mRoutePolyline.setPoints(mRoutePoints);
            }
        }
    }

    /* 增量更新起点标记与路线连线：复用覆盖物对象，不整图 clear，避免标记闪烁或丢失 */
    private void redrawRoute() {
        if (!mRoutePoints.isEmpty()) {
            if (mRouteStartMarker == null) {
                mRouteStartMarker = mRouteAMap.addMarker(new MarkerOptions()
                        .position(mRoutePoints.get(0)).icon(MainActivity.getMapIndicator()));
            } else {
                mRouteStartMarker.setPosition(mRoutePoints.get(0));
            }
        } else if (mRouteStartMarker != null) {
            mRouteStartMarker.remove();
            mRouteStartMarker = null;
        }

        if (mRoutePoints.size() > 1) {
            /* 画线用平滑后的点列，与实际模拟轨迹一致（转角为圆滑曲线） */
            List<LatLng> smoothPts = new ArrayList<>();
            for (double[] p : buildSmoothedRoute()) {
                smoothPts.add(new LatLng(p[1], p[0]));
            }
            if (mRoutePolyline == null) {
                mRoutePolyline = mRouteAMap.addPolyline(new PolylineOptions()
                        .addAll(smoothPts)
                        .width(8)
                        .color(0xB32196F3));
            } else {
                mRoutePolyline.setPoints(smoothPts);
            }
        } else if (mRoutePolyline != null) {
            mRoutePolyline.remove();
            mRoutePolyline = null;
        }
    }

    /* 转角平滑：内部每个转角用二次贝塞尔曲线（控制点为转角）切角过渡，直线段保持不变。
       输入为地图选点（GCJ），输出平滑后的 GCJ {经度, 纬度} 点列（首尾点不变） */
    private List<double[]> buildSmoothedRoute() {
        int n = mRoutePoints.size();
        List<double[]> out = new ArrayList<>();
        if (n < 3) {
            for (LatLng p : mRoutePoints) {
                out.add(new double[]{p.longitude, p.latitude});
            }
            return out;
        }

        /* GCJ 点转本地米平面坐标（用首点纬度做经度修正），便于按米计算切角距离 */
        double cosLat = Math.cos(Math.toRadians(mRoutePoints.get(0).latitude));
        List<double[]> src = new ArrayList<>();
        for (LatLng p : mRoutePoints) {
            src.add(new double[]{p.longitude * 111320.0 * cosLat, p.latitude * 110574.0});
        }

        out.add(src.get(0));
        for (int i = 1; i < n - 1; i++) {
            double[] A = src.get(i - 1);
            double[] P = src.get(i);
            double[] B = src.get(i + 1);
            double dA = Math.hypot(P[0] - A[0], P[1] - A[1]);
            double dB = Math.hypot(B[0] - P[0], B[1] - P[1]);
            /* 切角距离不超过相邻线段长度的 45%，避免相邻转角的过渡段重叠 */
            double d = Math.min(ROUTE_SMOOTH_DIST, Math.min(dA, dB) * 0.45);
            if (d < 1.0) {
                out.add(P);
                continue;
            }
            double[] Ap = {P[0] + (A[0] - P[0]) / dA * d, P[1] + (A[1] - P[1]) / dA * d};
            double[] Bp = {P[0] + (B[0] - P[0]) / dB * d, P[1] + (B[1] - P[1]) / dB * d};
            out.add(Ap);
            /* 贝塞尔曲线采样：每约 2.5 米一个点，保证连续转角方向渐变 */
            int steps = Math.max(2, (int) (d / 2.5));
            for (int s = 1; s < steps; s++) {
                double t = s / (double) steps;
                double om = 1 - t;
                out.add(new double[]{
                        om * om * Ap[0] + 2 * om * t * P[0] + t * t * Bp[0],
                        om * om * Ap[1] + 2 * om * t * P[1] + t * t * Bp[1]});
            }
            out.add(Bp);
        }
        out.add(src.get(n - 1));

        /* 米平面转回 GCJ 经纬度 */
        List<double[]> result = new ArrayList<>();
        for (double[] p : out) {
            result.add(new double[]{p[0] / (111320.0 * cosLat), p[1] / 110574.0});
        }
        return result;
    }

    private void startRoute() {
        if (mRoutePoints.size() < 2) {
            GoUtils.DisplayToast(mContext, getResources().getString(R.string.app_route_need_points));
            return;
        }

        /* 路线速度严格绑定摇杆当前的步行/跑步/骑行模式，并重新读取设置中的最新速度值 */
        mSpeed = getSpeedByCurrentMode();

        /* 传给服务的是平滑后的路线点列（转角为圆滑曲线），坐标从 GCJ 转换为 WGS84 */
        List<double[]> points = new ArrayList<>();
        for (double[] p : buildSmoothedRoute()) {
            double[] lngLat = MapUtils.gcj2wgs(p[0], p[1]);
            points.add(new double[]{lngLat[0], lngLat[1]});
        }

        isRouteRunning = true;
        /* 开始模拟后退出画笔模式，恢复地图手势 */
        setPenMode(false);
        updateRouteStartBtn();

        /* 第一个选点即路线起点：开始时把地图居中到起点并放置蓝点，与服务端起始位置保持一致。
           仅居中这一次，运行中只刷新蓝点、不强制移动视图 */
        LatLng startPt = mRoutePoints.get(0);
        CameraPosition cameraPosition = new CameraPosition.Builder().target(startPt).zoom(18.0f).build();
        mRouteAMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));

        // 通过定位源推送起点位置，驱动蓝点显示
        pushRouteLocation(startPt);

        mListener.onRouteStart(mSpeed, points);

        /* 跑步模式启用跑走交替：配速引擎在 ServiceGo 后台按随机配速调速并自动切换行走段 */
        mListener.onRouteRunConfig(isRun, parseWalkDist(), getWalkSpeed());

        String mode = isBike ? getResources().getString(R.string.joystick_bike)
                : isRun ? getResources().getString(R.string.joystick_run)
                : getResources().getString(R.string.joystick_walk);
        GoUtils.DisplayToast(mContext, getResources().getString(R.string.app_route_started)
                + "（" + mode + " " + mSpeed + " m/s）");
    }

    /* 与摇杆步行/跑步/骑行按钮完全一致的速度读取逻辑 */
    private double getSpeedByCurrentMode() {
        try {
            if (isBike) {
                return Double.parseDouble(sharedPreferences.getString("setting_bike", getResources().getString(R.string.setting_bike_default)));
            } else if (isRun) {
                return Double.parseDouble(sharedPreferences.getString("setting_run", getResources().getString(R.string.setting_run_default)));
            } else {
                return Double.parseDouble(sharedPreferences.getString("setting_walk", getResources().getString(R.string.setting_walk_default)));
            }
        } catch (NumberFormatException e) {
            return mSpeed;
        }
    }

    /* 步行档位速度，用作跑走交替中行走段的速度 */
    private double getWalkSpeed() {
        try {
            return Double.parseDouble(sharedPreferences.getString("setting_walk", getResources().getString(R.string.setting_walk_default)));
        } catch (NumberFormatException e) {
            return 1.2;
        }
    }

    /* 读取步行间隔输入框（米），为空/非法时回退到设置页「跑后步行间隔」默认值 */
    private double parseWalkDist() {
        try {
            if (mRouteWalkDistInput != null) {
                String text = mRouteWalkDistInput.getText().toString().trim();
                if (!text.isEmpty()) {
                    double v = Double.parseDouble(text);
                    if (v > 0) {
                        return v;
                    }
                }
            }
            String defDist = sharedPreferences.getString("setting_run_walk_dist", "100");
            double v = Double.parseDouble(defDist.trim());
            if (v > 0) {
                return v;
            }
        } catch (NumberFormatException ignored) {
        }
        return 100.0;
    }

    /* ServiceGo 每个定位周期（100ms）把最新 WGS84 位置回传到此，刷新路线图上的实时定位点 */
    public void updateRoutePosition(double wgsLng, double wgsLat) {
        mMainHandler.post(() -> {
            /* 路线悬浮窗未显示时无需刷新，避免无意义的图层计算 */
            if (mRouteLayout.getParent() == null) {
                return;
            }

            double[] gcj = MapUtils.wgs2gcj(wgsLng, wgsLat);
            pushRouteLocation(new LatLng(gcj[1], gcj[0]));
        });
    }

    /* 通过定位源把指定坐标推送给地图定位图层，刷新蓝点位置 */
    private void pushRouteLocation(LatLng latLng) {
        if (mRouteLocListener == null || latLng == null) {
            return;
        }

        android.location.Location location = new android.location.Location("mock");
        location.setLatitude(latLng.latitude);
        location.setLongitude(latLng.longitude);
        location.setAccuracy(1.0f);
        location.setTime(System.currentTimeMillis());
        mRouteLocListener.onLocationChanged(location);
    }

    /* 通过定位源把当前虚拟位置推送给摇杆地图的定位图层 */
    private void pushMapLocation() {
        if (mMapLocListener == null || mCurMapLngLat == null) {
            return;
        }

        android.location.Location location = new android.location.Location("mock");
        location.setLatitude(mCurMapLngLat.latitude);
        location.setLongitude(mCurMapLngLat.longitude);
        location.setAccuracy(1.0f);
        location.setTime(System.currentTimeMillis());
        mMapLocListener.onLocationChanged(location);
    }

    /* 地图窗口恢复渲染 */
    private void resumeMap() {
        if (mMapView != null) {
            mMapView.onResume();
        }
    }

    /* 地图窗口暂停渲染 */
    private void pauseMap() {
        if (mMapView != null) {
            mMapView.onPause();
        }
    }

    /* 路线地图窗口恢复渲染 */
    private void resumeRouteMap() {
        if (mRouteMapView != null) {
            mRouteMapView.onResume();
        }
    }

    /* 路线地图窗口暂停渲染 */
    private void pauseRouteMap() {
        if (mRouteMapView != null) {
            mRouteMapView.onPause();
        }
    }

    private void stopRoute(boolean showTips) {
        if (!isRouteRunning) {
            return;
        }

        isRouteRunning = false;
        updateRouteStartBtn();
        mListener.onRouteStop();

        if (showTips) {
            GoUtils.DisplayToast(mContext, getResources().getString(R.string.app_route_stopped));
        }
    }

    /* 路线模拟正常走完时由 ServiceGo 回调（可能运行在服务线程，需切换到主线程更新界面） */
    public void onRouteFinished() {
        mMainHandler.post(() -> {
            isRouteRunning = false;
            updateRouteStartBtn();
            GoUtils.DisplayToast(mContext, getResources().getString(R.string.app_route_finished));
        });
    }

    private void updateRouteStartBtn() {
        ImageButton btnStart = mRouteLayout.findViewById(R.id.route_start);
        btnStart.setImageResource(isRouteRunning ? R.drawable.ic_stop : R.drawable.ic_play);
    }

    /* ---------------- 路线窗缩小悬浮球相关 ---------------- */

    @SuppressLint("ClickableViewAccessibility")
    private void initFloatBall() {
        /* 悬浮球：点击恢复完整路线窗，可拖动；路线模拟运行中缩小不会中断模拟 */
        int size = (int) (48 * getResources().getDisplayMetrics().density + 0.5f);

        mFloatBallLayout = new FrameLayout(mContext);
        ImageButton ball = new ImageButton(mContext);
        ball.setLayoutParams(new FrameLayout.LayoutParams(size, size));
        ball.setPadding(0, 0, 0, 0);
        ball.setBackgroundResource(R.drawable.circle_shape_bg);
        ball.setImageResource(R.drawable.ic_route);
        ball.setColorFilter(getResources().getColor(R.color.colorAccent, mContext.getTheme()));
        ball.setOnClickListener(v -> {
            mCurWin = WINDOW_TYPE_ROUTE;
            show();
        });
        mFloatBallLayout.addView(ball);
        mFloatBallLayout.setOnTouchListener(new FloatBallOnTouchListener());

        mFloatBallParam = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.RGBA_8888);
        mFloatBallParam.gravity = Gravity.START | Gravity.TOP;
        mFloatBallParam.x = 60;
        mFloatBallParam.y = 300;
    }

    /* 悬浮球拖动监听：与窗口拖动逻辑一致，但使用悬浮球独立的位置参数 */
    private class FloatBallOnTouchListener implements OnTouchListener {
        private int x;
        private int y;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    x = (int) event.getRawX();
                    y = (int) event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    int nowX = (int) event.getRawX();
                    int nowY = (int) event.getRawY();
                    int movedX = nowX - x;
                    int movedY = nowY - y;
                    x = nowX;
                    y = nowY;

                    mFloatBallParam.x += movedX;
                    mFloatBallParam.y += movedY;
                    mWindowManager.updateViewLayout(view, mFloatBallParam);
                    break;
                case MotionEvent.ACTION_UP:
                    view.performClick();
                    break;
                default:
                    break;
            }
            return false;
        }
    }


    @SuppressLint({"InflateParams", "ClickableViewAccessibility"})
    private void initJoyStickMapView() {
        mMapLayout = (FrameLayout)inflater.inflate(R.layout.joystick_map, null);
        mMapLayout.setOnTouchListener(new JoyStickOnTouchListener());

        mSearchList = mMapLayout.findViewById(R.id.map_search_list_view);
        mSearchLayout = mMapLayout.findViewById(R.id.map_search_linear);
        try {
            mInputtips = new Inputtips(mContext, (tips, rCode) -> {
                if (rCode != AMapException.CODE_AMAP_SUCCESS || tips == null || tips.isEmpty()) {
                    GoUtils.DisplayToast(mContext, getResources().getString(R.string.app_search_null));
                } else {
                    List<Map<String, Object>> data = new ArrayList<>();

                    for (Tip tip : tips) {
                        if (tip.getPoint() == null) {
                            continue;
                        }

                        Map<String, Object> poiItem = new HashMap<>();
                        poiItem.put(MainActivity.POI_NAME, tip.getName());
                        poiItem.put(MainActivity.POI_ADDRESS, tip.getDistrict());
                        poiItem.put(MainActivity.POI_LONGITUDE, "" + tip.getPoint().getLongitude());
                        poiItem.put(MainActivity.POI_LATITUDE, "" + tip.getPoint().getLatitude());
                        data.add(poiItem);
                    }

                    SimpleAdapter simAdapt = new SimpleAdapter(
                            mContext,
                            data,
                            R.layout.search_poi_item,
                            new String[] {MainActivity.POI_NAME, MainActivity.POI_ADDRESS, MainActivity.POI_LONGITUDE, MainActivity.POI_LATITUDE}, // 与下面数组元素要一一对应
                            new int[] {R.id.poi_name, R.id.poi_address, R.id.poi_longitude, R.id.poi_latitude});
                    mSearchList.setAdapter(simAdapt);
                    mSearchLayout.setVisibility(View.VISIBLE);
                }
            });
        } catch (AMapException e) {
            Log.e("JoyStick", "高德输入提示服务初始化失败：" + e.getMessage());
        }

        mSearchList.setOnItemClickListener((parent, view, position, id) -> {
            mSearchLayout.setVisibility(View.GONE);

            String lng = ((TextView) view.findViewById(R.id.poi_longitude)).getText().toString();
            String lat = ((TextView) view.findViewById(R.id.poi_latitude)).getText().toString();
            markMap(new LatLng(Double.parseDouble(lat), Double.parseDouble(lng)));
        });

        TextView tips = mMapLayout.findViewById(R.id.joystick_map_tips);
        SearchView mSearchView = mMapLayout.findViewById(R.id.joystick_map_searchView);
        mSearchView.setOnSearchClickListener(v -> {
            tips.setVisibility(GONE);

            // 特殊处理：这里让搜索框获取焦点，以显示输入法
            mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            mWindowManager.updateViewLayout(mMapLayout, mWindowParamCurrent);
        });
        mSearchView.setOnCloseListener(() -> {
            tips.setVisibility(VISIBLE);
            mSearchLayout.setVisibility(GONE);

            // 关闭时清除焦点
            mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            mWindowManager.updateViewLayout(mMapLayout, mWindowParamCurrent);

            return false;       /* 这里必须返回false，否则需要自行处理搜索框的折叠 */
        });
        mSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (newText != null && newText.length() > 0) {
                    try {
                        requestInputtips(newText);
                    } catch (Exception e) {
                        GoUtils.DisplayToast(mContext,getResources().getString(R.string.app_error_search));
                        e.printStackTrace();
                    }
                } else {
                    mSearchLayout.setVisibility(GONE);
                }

                return true;
            }
        });

        ImageButton btnGo = mMapLayout.findViewById(R.id.btnGo);
        btnGo.setOnClickListener(v -> {
            // 关闭时清除焦点
            mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            mWindowManager.updateViewLayout(mMapLayout, mWindowParamCurrent);

            tips.setVisibility(VISIBLE);
            mSearchView.clearFocus();
            mSearchView.onActionViewCollapsed();

            if (mMarkMapLngLat == null) {
                GoUtils.DisplayToast(mContext, getResources().getString(R.string.app_error_location));
            } else {
                if (mCurMapLngLat != mMarkMapLngLat) {
                    mCurMapLngLat = mMarkMapLngLat;
                    mMarkMapLngLat = null;

                    double[] lngLat = MapUtils.gcj2wgs(mCurMapLngLat.longitude, mCurMapLngLat.latitude);
                    mListener.onPositionInfo(lngLat[0], lngLat[1], mAltitude);

                    resetMap();

                    GoUtils.DisplayToast(mContext, getResources().getString(R.string.app_location_ok));
                }
            }
        });
        btnGo.setColorFilter(getResources().getColor(R.color.colorAccent, mContext.getTheme()));

        ImageButton btnClose = mMapLayout.findViewById(R.id.map_close);
        btnClose.setOnClickListener(v -> {
            // 关闭时清除焦点
            mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

            tips.setVisibility(VISIBLE);
            mSearchLayout.setVisibility(GONE);
            mSearchView.clearFocus();
            mSearchView.onActionViewCollapsed();

            mCurWin = WINDOW_TYPE_JOYSTICK;
            show();
        });

        ImageButton btnBack = mMapLayout.findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> resetMap());
        btnBack.setColorFilter(getResources().getColor(R.color.colorAccent, mContext.getTheme()));

        initMap();
    }

    // 发起输入提示（POI 搜索建议）请求
    private void requestInputtips(String keyword) {
        if (mInputtips == null || TextUtils.isEmpty(keyword)) {
            return;
        }
        mInputtips.setQuery(new InputtipsQuery(keyword, MainActivity.mCurrentCity));
        mInputtips.requestInputtipsAsyn();
    }

    private void initMap() {
        mMapView = mMapLayout.findViewById(R.id.map_joystick);
        mMapView.onCreate(null);
        mAMap = mMapView.getMap();
        mAMap.getUiSettings().setZoomControlsEnabled(false);
        mAMap.setMapType(AMap.MAP_TYPE_NORMAL);
        // 配置定位图层，用于显示当前虚拟位置的蓝点
        mAMap.setLocationSource(new LocationSource() {
            @Override
            public void activate(LocationSource.OnLocationChangedListener listener) {
                mMapLocListener = listener;
                pushMapLocation();
            }

            @Override
            public void deactivate() {
                mMapLocListener = null;
            }
        });
        MyLocationStyle myLocationStyle = new MyLocationStyle();
        myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_SHOW);
        myLocationStyle.showMyLocation(true);
        mAMap.setMyLocationStyle(myLocationStyle);
        mAMap.setMyLocationEnabled(true);

        mAMap.setOnMapTouchListener(event -> {

        });
        // 单击地图、单击 POI 点或长按地图，均为标定该位置
        mAMap.setOnMapClickListener(this::markMap);
        mAMap.setOnPOIClickListener(poi -> markMap(poi.getCoordinate()));
        mAMap.setOnMapLongClickListener(this::markMap);
    }

    private void resetMap() {
        mAMap.clear();
        mMapMarker = null;

        // 通过定位源推送当前虚拟位置，刷新蓝点
        pushMapLocation();

        if (mCurMapLngLat != null) {
            CameraPosition cameraPosition = new CameraPosition.Builder()
                    .target(mCurMapLngLat).zoom(18.0f).build();
            mAMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
        }
    }

    private void markMap(LatLng latLng) {
        mMarkMapLngLat = latLng;

        // 只移除旧标记，不清空全图，避免影响其它覆盖物与定位图层
        if (mMapMarker != null) {
            mMapMarker.remove();
        }
        mMapMarker = mAMap.addMarker(new MarkerOptions().position(latLng).icon(MainActivity.getMapIndicator()));

        CameraPosition cameraPosition = new CameraPosition.Builder().target(latLng).zoom(18.0f).build();
        mAMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
    }


    @SuppressLint({"InflateParams", "ClickableViewAccessibility"})
    private void initHistoryView() {
        mHistoryLayout = (FrameLayout)inflater.inflate(R.layout.joystick_history, null);
        mHistoryLayout.setOnTouchListener(new JoyStickOnTouchListener());

        TextView tips = mHistoryLayout.findViewById(R.id.joystick_his_tips);
        SearchView mSearchView = mHistoryLayout.findViewById(R.id.joystick_his_searchView);
        mSearchView.setOnSearchClickListener(v -> {
            tips.setVisibility(GONE);

            // 特殊处理：这里让搜索框获取焦点，以显示输入法
            mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            mWindowManager.updateViewLayout(mHistoryLayout, mWindowParamCurrent);
        });
        mSearchView.setOnCloseListener(() -> {
            tips.setVisibility(VISIBLE);

            // 关闭时清除焦点
            mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            mWindowManager.updateViewLayout(mHistoryLayout, mWindowParamCurrent);

            return false;       /* 这里必须返回false，否则需要自行处理搜索框的折叠 */
        });
        mSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {// 当点击搜索按钮时触发该方法
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {// 当搜索内容改变时触发该方法
                if (TextUtils.isEmpty(newText)) {
                    showHistory(mAllRecord);
                } else {
                    List<Map<String, Object>> searchRet = new ArrayList<>();
                    for (int i = 0; i < mAllRecord.size(); i++){
                        if (mAllRecord.get(i).toString().indexOf(newText) > 0){
                            searchRet.add(mAllRecord.get(i));
                        }
                    }

                    if (searchRet.size() > 0) {
                        showHistory(searchRet);
                    } else {
                        GoUtils.DisplayToast(mContext, getResources().getString(R.string.app_search_null));
                        showHistory(mAllRecord);
                    }
                }

                return false;
            }
        });

        noRecordText = mHistoryLayout.findViewById(R.id.joystick_his_record_no_textview);
        mRecordListView = mHistoryLayout.findViewById(R.id.joystick_his_record_list_view);
        mRecordListView.setOnItemClickListener((adapterView, view, i, l) -> {
            // 关闭时清除焦点
            mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            mWindowManager.updateViewLayout(mHistoryLayout, mWindowParamCurrent);

            mSearchView.clearFocus();
            mSearchView.onActionViewCollapsed();
            tips.setVisibility(VISIBLE);

            // wgs84坐标
            String wgs84LatLng = (String) ((TextView) view.findViewById(R.id.WGSLatLngText)).getText();
            wgs84LatLng = wgs84LatLng.substring(wgs84LatLng.indexOf('[') + 1, wgs84LatLng.indexOf(']'));
            String[] wgs84latLngStr = wgs84LatLng.split(" ");
            String wgs84Longitude = wgs84latLngStr[0].substring(wgs84latLngStr[0].indexOf(':') + 1);
            String wgs84Latitude = wgs84latLngStr[1].substring(wgs84latLngStr[1].indexOf(':') + 1);

            mListener.onPositionInfo(Double.parseDouble(wgs84Longitude), Double.parseDouble(wgs84Latitude), mAltitude);

            // 注意这里在选择位置之后需要刷新地图
            String bdLatLng = (String) ((TextView) view.findViewById(R.id.BDLatLngText)).getText();
            bdLatLng = bdLatLng.substring(bdLatLng.indexOf('[') + 1, bdLatLng.indexOf(']'));
            String[] bdLatLngStr = bdLatLng.split(" ");
            String bdLongitude = bdLatLngStr[0].substring(bdLatLngStr[0].indexOf(':') + 1);
            String bdLatitude = bdLatLngStr[1].substring(bdLatLngStr[1].indexOf(':') + 1);
            mCurMapLngLat = new LatLng(Double.parseDouble(bdLatitude), Double.parseDouble(bdLongitude));

            GoUtils.DisplayToast(mContext, getResources().getString(R.string.app_location_ok));
        });

        fetchAllRecord();

        showHistory(mAllRecord);

        ImageButton btnClose = mHistoryLayout.findViewById(R.id.joystick_his_close);
        btnClose.setOnClickListener(v -> {
            // 关闭时清除焦点
            mWindowParamCurrent.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

            mSearchView.clearFocus();
            mSearchView.onActionViewCollapsed();
            tips.setVisibility(VISIBLE);

            mCurWin = WINDOW_TYPE_JOYSTICK;
            show();
        });
    }

    private void fetchAllRecord() {
        SQLiteDatabase mHistoryLocationDB;

        try {
            DataBaseHistoryLocation hisLocDBHelper = new DataBaseHistoryLocation(mContext.getApplicationContext());
            mHistoryLocationDB = hisLocDBHelper.getWritableDatabase();

            Cursor cursor = mHistoryLocationDB.query(DataBaseHistoryLocation.TABLE_NAME, null,
                    DataBaseHistoryLocation.DB_COLUMN_ID + " > ?", new String[] {"0"},
                    null, null, DataBaseHistoryLocation.DB_COLUMN_TIMESTAMP + " DESC", null);

            while (cursor.moveToNext()) {
                Map<String, Object> item = new HashMap<>();
                int ID = cursor.getInt(0);
                String Location = cursor.getString(1);
                String Longitude = cursor.getString(2);
                String Latitude = cursor.getString(3);
                long TimeStamp = cursor.getInt(4);
                String BD09Longitude = cursor.getString(5);
                String BD09Latitude = cursor.getString(6);
                Log.d("TB", ID + "\t" + Location + "\t" + Longitude + "\t" + Latitude + "\t" + TimeStamp + "\t" + BD09Longitude + "\t" + BD09Latitude);
                BigDecimal bigDecimalLongitude = BigDecimal.valueOf(Double.parseDouble(Longitude));
                BigDecimal bigDecimalLatitude = BigDecimal.valueOf(Double.parseDouble(Latitude));
                BigDecimal bigDecimalBDLongitude = BigDecimal.valueOf(Double.parseDouble(BD09Longitude));
                BigDecimal bigDecimalBDLatitude = BigDecimal.valueOf(Double.parseDouble(BD09Latitude));
                double doubleLongitude = bigDecimalLongitude.setScale(11, RoundingMode.HALF_UP).doubleValue();
                double doubleLatitude = bigDecimalLatitude.setScale(11, RoundingMode.HALF_UP).doubleValue();
                double doubleBDLongitude = bigDecimalBDLongitude.setScale(11, RoundingMode.HALF_UP).doubleValue();
                double doubleBDLatitude = bigDecimalBDLatitude.setScale(11, RoundingMode.HALF_UP).doubleValue();
                item.put(HistoryActivity.KEY_ID, Integer.toString(ID));
                item.put(HistoryActivity.KEY_LOCATION, Location);
                item.put(HistoryActivity.KEY_TIME, GoUtils.timeStamp2Date(Long.toString(TimeStamp)));
                item.put(HistoryActivity.KEY_LNG_LAT_WGS, "[经度:" + doubleLongitude + " 纬度:" + doubleLatitude + "]");
                item.put(HistoryActivity.KEY_LNG_LAT_CUSTOM, "[经度:" + doubleBDLongitude + " 纬度:" + doubleBDLatitude + "]");
                mAllRecord.add(item);
            }
            cursor.close();
            mHistoryLocationDB.close();
        } catch (Exception e) {
            Log.e("JOYSTICK", "ERROR - fetchAllRecord");
        }
    }

    private void showHistory(List<Map<String, Object>> list) {
        if (list.size() == 0) {
            mRecordListView.setVisibility(View.GONE);
            noRecordText.setVisibility(View.VISIBLE);
        } else {
            noRecordText.setVisibility(View.GONE);
            mRecordListView.setVisibility(View.VISIBLE);

            try {
                SimpleAdapter simAdapt = new SimpleAdapter(
                        mContext,
                        list,
                        R.layout.history_item,
                        new String[]{HistoryActivity.KEY_ID, HistoryActivity.KEY_LOCATION, HistoryActivity.KEY_TIME, HistoryActivity.KEY_LNG_LAT_WGS, HistoryActivity.KEY_LNG_LAT_CUSTOM}, // 与下面数组元素要一一对应
                        new int[]{R.id.LocationID, R.id.LocationText, R.id.TimeText, R.id.WGSLatLngText, R.id.BDLatLngText});
                mRecordListView.setAdapter(simAdapt);
            } catch (Exception e) {
                Log.e("JOYSTICK", "ERROR - showHistory");
            }
        }
    }
}