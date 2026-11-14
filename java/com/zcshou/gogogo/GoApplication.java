package com.zcshou.gogogo;

import android.app.Application;

import com.amap.api.location.AMapLocationClient;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.services.core.ServiceSettings;

import com.elvishew.xlog.LogConfiguration;
import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.printer.ConsolePrinter;
import com.elvishew.xlog.printer.Printer;
import com.elvishew.xlog.printer.file.FilePrinter;
import com.elvishew.xlog.printer.file.backup.NeverBackupStrategy;
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy;
import com.elvishew.xlog.printer.file.naming.ChangelessFileNameGenerator;

import com.zcshou.utils.GoUtils;

import java.io.File;

public class GoApplication extends Application {
    public static final String APP_NAME = "GoGoGo";
    public static final String LOG_FILE_NAME = APP_NAME + ".log";
    private static final long MAX_TIME = 1000 * 60 * 60 * 24 * 3; // 3 days

    @Override
    public void onCreate() {
        super.onCreate();

        initXlog();

        // 高德 SDK 隐私合规接口，必须在初始化 SDK 之前调用
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
        // 高德定位需要单独设置隐私合规
        AMapLocationClient.updatePrivacyShow(this, true, true);
        AMapLocationClient.updatePrivacyAgree(this, true);
        // 高德搜索服务同样需要设置隐私合规
        ServiceSettings.updatePrivacyShow(this, true, true);
        ServiceSettings.updatePrivacyAgree(this, true);

        // 开源版不内置地图 Key：启动时读取用户此前在应用内填写的 Key，注入地图/定位/搜索三个 SDK
        String amapKey = GoUtils.getAmapApiKey(this);
        if (!amapKey.isEmpty()) {
            GoUtils.applyAmapApiKey(amapKey);
        }

        try {
            // 在使用 SDK 各组间之前初始化 context 信息，传入 ApplicationContext
            MapsInitializer.initialize(this);
        } catch (Exception e) {
            XLog.e("高德地图初始化失败：" + e.getMessage());
        }
    }

    /**
     * Initialize XLog.
     */
    private void initXlog() {
        File logPath = getExternalFilesDir("Logs");
        if (logPath != null) {
            LogConfiguration config = new LogConfiguration.Builder()
                    .logLevel(LogLevel.ALL)
                    .tag(APP_NAME)                                         // 指定 TAG，默认为 "X-LOG"
                    .enableThreadInfo()                                    // 允许打印线程信息，默认禁止
                    .enableStackTrace(2)                                   // 允许打印深度为 2 的调用栈信息，默认禁止
                    .enableBorder()                                        // 允许打印日志边框，默认禁止
                    .build();

            Printer consolePrinter = new ConsolePrinter();                  // 通过 System.out 打印日志到控制台的打印器
            Printer filePrinter = new FilePrinter                           // 打印日志到文件的打印器
                    .Builder(logPath.getPath())                             // 指定保存日志文件的路径
                    .fileNameGenerator(new ChangelessFileNameGenerator(LOG_FILE_NAME))         // 指定日志文件名生成器，默认为 ChangelessFileNameGenerator("log")
                    .backupStrategy(new NeverBackupStrategy())              // 指定日志文件备份策略，默认为 FileSizeBackupStrategy(1024 * 1024)
                    .cleanStrategy(new FileLastModifiedCleanStrategy(MAX_TIME))     // 指定日志文件清除策略，默认为 NeverCleanStrategy()
                    .build();
            XLog.init(config, consolePrinter, filePrinter);
        }
    }
}