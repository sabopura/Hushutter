package io.github.sabopura.hushutter;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.provider.Settings;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 端末の状態の確認と、アプリ内に保存する状態をまとめたもの。 */
final class Device {
    static final String CAMERA_PACKAGE = "com.sec.android.app.camera";
    static final String SHUTTER_KEY = "csc_pref_camera_forced_shuttersound_key";

    static final String PREF_PAIRED = "paired";
    static final String PREF_MUTED = "muted";
    static final String PREF_KEY_READABLE = "key_readable";
    static final String PREF_STATUS = "status";
    /** サービスが設定を書き換えている最中なら true */
    static final String PREF_WORKING = "working";
    /** ペアリングコードの入力待ちなら true */
    static final String PREF_PAIRING = "pairing";
    /** 設定アプリのペアリング画面が開いていて、コードを受け付けられるなら true */
    static final String PREF_PAIRING_READY = "pairing_ready";
    /** 直近の失敗の説明。成功したら空にする */
    static final String PREF_ERROR = "error";

    private static final String ADB_WIFI_ENABLED = "adb_wifi_enabled";

    private Device() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("state", Context.MODE_PRIVATE);
    }

    static void setStatus(Context context, String status) {
        prefs(context).edit().putString(PREF_STATUS, status).apply();
    }

    private static final long LOG_MAX_BYTES = 64 * 1024;

    /** 不具合の調査用に、処理の経過をアプリ内のファイルに記録する。 */
    static synchronized void log(Context context, String message) {
        File file = new File(context.getFilesDir(), "log.txt");
        if (file.length() > LOG_MAX_BYTES) {
            File old = new File(context.getFilesDir(), "log.old.txt");
            old.delete();
            file.renameTo(old);
        }
        String time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        try (FileWriter writer = new FileWriter(file, true)) {
            writer.write(time + " " + message + "\n");
        } catch (IOException ignored) {
        }
    }

    static String readLog(Context context) {
        StringBuilder log = new StringBuilder();
        for (String name : new String[]{"log.old.txt", "log.txt"}) {
            File file = new File(context.getFilesDir(), name);
            if (!file.exists()) continue;
            try {
                log.append(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }
        return log.toString();
    }

    static boolean isWifiConnected(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    static boolean isDeveloperOptionsEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
    }

    static boolean hasCameraApp(Context context) {
        try {
            context.getPackageManager().getPackageInfo(CAMERA_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** 初回のペアリング後に adb から付与される。これがあればワイヤレスデバッグを自分でオン/オフできる。 */
    static boolean canToggleWirelessDebugging(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isWirelessDebuggingOn(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), ADB_WIFI_ENABLED, 0) == 1;
        } catch (SecurityException e) {
            return false;
        }
    }

    static void setWirelessDebugging(Context context, boolean on) {
        Settings.Global.putInt(context.getContentResolver(), ADB_WIFI_ENABLED, on ? 1 : 0);
    }

    /** シャッター音キーの現在値。アプリから読めない端末では null。 */
    static String readShutterKey(Context context) {
        try {
            return Settings.System.getString(context.getContentResolver(), SHUTTER_KEY);
        } catch (SecurityException e) {
            return null;
        }
    }

    /**
     * いまシャッター音が鳴らない設定か。最後に設定した結果を使い、
     * 端末の設定値を読める場合は、OS アップデートなどで元に戻っていないかも確かめる。
     */
    static boolean isMuted(Context context) {
        if (!prefs(context).getBoolean(PREF_MUTED, false)) return false;
        String key = readShutterKey(context);
        return key == null || "0".equals(key);
    }

    static void openWifiSettings(Context context) {
        context.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** 開発者向けオプションを開き、ワイヤレスデバッグの項目を強調表示する。 */
    static void openWirelessDebuggingSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        Bundle args = new Bundle();
        args.putString(":settings:fragment_args_key", "toggle_adb_wireless");
        intent.putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
        intent.putExtra(":settings:show_fragment_args", args);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            context.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    static void openDeviceInfoSettings(Context context) {
        context.startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
