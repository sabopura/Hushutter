package io.github.sabopura.hushutter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.muntashirakon.adb.AdbAuthenticationFailedException;
import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.android.AdbMdns;

/**
 * ワイヤレスデバッグ経由で自分自身に adb 接続し、シャッター音の設定を書き換える。
 * ペアリングコードは、設定アプリのペアリング画面を閉じずに入力できるよう通知から受け取る。
 */
public class MuteService extends Service {
    static final String ACTION_PAIR = "io.github.sabopura.hushutter.PAIR";
    static final String ACTION_MUTE = "io.github.sabopura.hushutter.MUTE";
    static final String ACTION_RESTORE = "io.github.sabopura.hushutter.RESTORE";
    private static final String ACTION_CODE = "io.github.sabopura.hushutter.CODE";
    private static final String ACTION_CANCEL = "io.github.sabopura.hushutter.CANCEL";

    private static final String CHANNEL_WORK = "work";
    static final String CHANNEL_RESULT = "result";
    private static final int ID_WORK = 1;
    static final int ID_RESULT = 2;
    private static final String KEY_CODE = "code";
    private static final String EXTRA_MUTE = "mute";

    private static final long DISCOVERY_TIMEOUT_MS = 30_000;
    /** 接続を拒否されたときに、ポートを探し直して再試行する時間 */
    private static final long CONNECT_RETRY_MS = 20_000;
    /** 1 回の処理（ペアリング・接続・設定の書き換え）全体の制限時間 */
    private static final long RUN_TIMEOUT_MS = 90_000;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService mScheduler = Executors.newSingleThreadScheduledExecutor();
    private NotificationManager mNotificationManager;
    private AdbMdns mPairingMdns;
    private volatile String mPairingHost;
    private volatile int mPairingPort = -1;
    private volatile boolean mBusy;
    /** ペアリング後に無音化するなら true、元に戻すなら false */
    private volatile boolean mMuteAfterPairing = true;
    private volatile AdbManager mAdb;
    private volatile Thread mWorker;
    private volatile String mStep = "";
    private volatile boolean mTimedOut;
    private volatile boolean mCancelled;

    /** このプロセスでサービスが動いているか。強制終了で記録だけ「処理中」のまま残っても、画面が止まらないようにする */
    private static volatile boolean sAlive;

    static boolean isAlive() {
        return sAlive;
    }

    static void start(Context context, String action) {
        context.startForegroundService(new Intent(context, MuteService.class).setAction(action));
    }

    static void startPairing(Context context, boolean muteAfterPairing) {
        context.startForegroundService(new Intent(context, MuteService.class)
                .setAction(ACTION_PAIR).putExtra(EXTRA_MUTE, muteAfterPairing));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sAlive = true;
        mNotificationManager = getSystemService(NotificationManager.class);
        createChannels(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (mBusy && !ACTION_CANCEL.equals(action)) {
            // 実行中に押されても二重に動かさない
            showForeground(progress(mStep + "…"));
            return START_NOT_STICKY;
        }
        if (ACTION_PAIR.equals(action)) {
            mMuteAfterPairing = intent.getBooleanExtra(EXTRA_MUTE, true);
            startPairing();
        } else if (ACTION_CODE.equals(action)) {
            onPairingCode(intent);
        } else if (ACTION_MUTE.equals(action) || ACTION_RESTORE.equals(action)) {
            boolean mute = ACTION_MUTE.equals(action);
            mBusy = true;
            mCancelled = false;
            setWorking(true);
            showForeground(progress(getString(mute ? R.string.progress_muting : R.string.progress_restoring)));
            mExecutor.execute(() -> guarded(() -> run(mute, false)));
        } else if (mBusy) {
            showForeground(progress(getString(R.string.progress_cancelling)));
            cancel();
        } else {
            showForeground(progress(getString(R.string.progress_closing)));
            finish(null, false);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        sAlive = false;
        stopPairingDiscovery();
        mExecutor.shutdownNow();
        mScheduler.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- ペアリング ----

    private void startPairing() {
        Device.setStatus(this, getString(R.string.status_pairing_wait));
        Device.prefs(this).edit()
                .putBoolean(Device.PREF_PAIRING, true)
                .putBoolean(Device.PREF_PAIRING_READY, mPairingPort > 0)
                .apply();
        showForeground(pairingNotification());
        if (mPairingMdns != null) return;
        mPairingMdns = new AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING, (host, port) -> {
            if (host != null && port > 0) {
                mPairingHost = host.getHostAddress();
                mPairingPort = port;
            } else {
                mPairingPort = -1;
            }
            Device.log(this, "ペアリング用ポート: " + mPairingHost + ":" + mPairingPort);
            Device.prefs(this).edit().putBoolean(Device.PREF_PAIRING_READY, mPairingPort > 0).apply();
            if (!mBusy) mNotificationManager.notify(ID_WORK, pairingNotification());
        });
        mPairingMdns.start();
    }

    private void stopPairingDiscovery() {
        if (mPairingMdns != null) {
            mPairingMdns.stop();
            mPairingMdns = null;
        }
    }

    private void onPairingCode(Intent intent) {
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        CharSequence input = results == null ? null : results.getCharSequence(KEY_CODE);
        String code = normalizeCode(input);
        String host = mPairingHost;
        int port = mPairingPort;
        Device.log(this, "コードを受信: " + code.length() + " 桁（入力 " + (input == null ? 0 : input.length()) + " 文字）");
        if (!code.matches("\\d{6}") || port <= 0) {
            String message = port <= 0
                    ? getString(R.string.error_pair_closed)
                    : getString(R.string.error_pair_digits);
            Device.setStatus(this, message);
            Device.prefs(this).edit().putString(Device.PREF_ERROR, message).apply();
            showForeground(pairingNotification());
            return;
        }
        mBusy = true;
        mCancelled = false;
        setWorking(true);
        showForeground(progress(getString(R.string.step_pairing) + "…"));
        mExecutor.execute(() -> guarded(() -> {
            step(getString(R.string.step_pairing));
            try {
                AdbManager.get(this).pair(host, port, code);
                Device.log(this, "ペアリング成功");
            } catch (Exception e) {
                if (mCancelled || mTimedOut) throw e;
                Device.log(this, "ペアリング失敗: " + Log.getStackTraceString(e));
                mBusy = false;
                String message = getString(R.string.error_pair_failed);
                Device.setStatus(this, message);
                Device.prefs(this).edit()
                        .putBoolean(Device.PREF_WORKING, false)
                        .putString(Device.PREF_ERROR, message)
                        .apply();
                mNotificationManager.notify(ID_WORK, pairingNotification());
                return;
            }
            stopPairingDiscovery();
            Device.prefs(this).edit().putBoolean(Device.PREF_PAIRED, true).apply();
            run(mMuteAfterPairing, true);
        }));
    }

    /** 日本語キーボードの全角数字や、空白・ハイフン入りの入力も受け付ける */
    private static String normalizeCode(CharSequence input) {
        if (input == null) return "";
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= '０' && c <= '９') c = (char) ('0' + (c - '０'));
            if (c >= '0' && c <= '9') digits.append(c);
        }
        return digits.toString();
    }

    // ---- 設定の書き換え ----

    private interface Task {
        void run() throws Exception;
    }

    /**
     * 処理を制限時間つきで実行する。adb の応答が返らず止まっても、時間が来たら打ち切って結果を知らせる。
     */
    private void guarded(Task task) {
        Thread worker = Thread.currentThread();
        mWorker = worker;
        mTimedOut = false;
        mStep = getString(R.string.step_preparing);
        ScheduledFuture<?> watchdog = mScheduler.schedule(() -> {
            mTimedOut = true;
            Device.log(this, "時間切れ（" + mStep + "）");
            worker.interrupt();
            disconnectQuietly();
        }, RUN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        try {
            task.run();
        } catch (Exception e) {
            Device.log(this, "失敗（" + mStep + "）: " + Log.getStackTraceString(e));
            if (mCancelled) {
                finish(getString(R.string.result_cancelled), false);
            } else if (mTimedOut) {
                finish(getString(R.string.result_timeout, mStep), false);
            } else {
                finish(getString(R.string.result_failed, mStep, e.getMessage()), false);
            }
        } finally {
            watchdog.cancel(false);
            mWorker = null;
            Thread.interrupted();
        }
    }

    /**
     * adb に接続して設定を書き換える。
     *
     * @param afterPairing ペアリング直後なら true。ユーザーが手動でオンにしたワイヤレスデバッグを最後にオフにする。
     */
    private void run(boolean mute, boolean afterPairing) throws Exception {
        Device.log(this, (mute ? "無音化" : "元に戻す") + "を開始 afterPairing=" + afterPairing
                + " / " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE);
        boolean canToggle = Device.canToggleWirelessDebugging(this);
        boolean turnedOn = false;
        try {
            if (!Device.isWifiConnected(this)) {
                finish(getString(R.string.result_no_wifi), false);
                return;
            }
            if (canToggle && !Device.isWirelessDebuggingOn(this)) {
                step(getString(R.string.step_wireless_on));
                Device.setWirelessDebugging(this, true);
                turnedOn = true;
            }
            mAdb = AdbManager.get(this);
            try {
                connect(mAdb);
            } catch (AdbPairingRequiredException | AdbAuthenticationFailedException e) {
                Device.log(this, "ペアリングが必要: " + e);
                // ペアリングにはワイヤレスデバッグがオンのままである必要がある
                turnedOn = false;
                mMuteAfterPairing = mute;
                needPairing(getString(R.string.need_pairing));
                return;
            } catch (DiscoveryTimeoutException e) {
                Device.log(this, "ワイヤレスデバッグが見つからない");
                if (canToggle) {
                    finish(getString(R.string.result_allow_network), false);
                } else {
                    turnedOn = false;
                    mMuteAfterPairing = mute;
                    needPairing(getString(R.string.need_pairing_connect));
                }
                return;
            }

            String result = mute ? mute(mAdb) : restore(mAdb);
            if (afterPairing || turnedOn) {
                // ここで付与した権限があればオフにできる。adb 接続を先に切ってから。
                disconnectQuietly();
                if (Device.canToggleWirelessDebugging(this)) {
                    Device.setWirelessDebugging(this, false);
                    turnedOn = false;
                }
            }
            Device.log(this, "完了: " + result);
            finish(result, true);
        } finally {
            disconnectQuietly();
            if (turnedOn) Device.setWirelessDebugging(this, false);
        }
    }

    /**
     * ワイヤレスデバッグのポートを mDNS で探して接続する。
     * 端末内の接続なので、まず 127.0.0.1 を使う（VPN などの影響を受けない）。だめなら Wi-Fi のアドレスで試す。
     */
    private void connect(AdbManager adb) throws Exception {
        // ワイヤレスデバッグをオンにした直後は、前回の古いポートが見つかったり、まだ接続を受け付けていなかったりする。
        // 接続を拒否されたら、少し待ってポートを探し直す。
        long deadline = System.currentTimeMillis() + CONNECT_RETRY_MS;
        for (int attempt = 1; ; attempt++) {
            step(getString(R.string.step_discovering));
            CountDownLatch found = new CountDownLatch(1);
            AtomicReference<String> host = new AtomicReference<>();
            AtomicInteger port = new AtomicInteger(-1);
            AdbMdns mdns = new AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_CONNECT, (address, p) -> {
                if (address != null && p > 0) {
                    host.set(address.getHostAddress());
                    port.set(p);
                    found.countDown();
                }
            });
            mdns.start();
            try {
                if (!found.await(DISCOVERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw new DiscoveryTimeoutException();
            } finally {
                mdns.stop();
            }
            Device.log(this, "見つかった: " + host.get() + ":" + port.get() + "（" + attempt + " 回目）");

            step(getString(R.string.step_connecting));
            try {
                adb.connect("127.0.0.1", port.get());
                Device.log(this, "接続しました（127.0.0.1）");
                return;
            } catch (AdbPairingRequiredException | AdbAuthenticationFailedException e) {
                throw e;
            } catch (Exception e) {
                if (mCancelled || mTimedOut) throw e;
                Device.log(this, "127.0.0.1 に接続できない: " + e);
                adb.disconnect();
            }
            try {
                adb.connect(host.get(), port.get());
                Device.log(this, "接続しました（" + host.get() + "）");
                return;
            } catch (AdbPairingRequiredException | AdbAuthenticationFailedException e) {
                throw e;
            } catch (Exception e) {
                if (mCancelled || mTimedOut || System.currentTimeMillis() > deadline) throw e;
                Device.log(this, host.get() + " にも接続できない: " + e + " → 待ってやり直す");
                adb.disconnect();
            }
            Thread.sleep(1000);
        }
    }

    private String mute(AdbManager adb) throws Exception {
        String camera = Device.CAMERA_PACKAGE;
        step(getString(R.string.step_writing));
        if (!exec(adb, "pm path " + camera).startsWith("package:")) {
            throw new IllegalStateException(getString(R.string.error_no_camera, camera));
        }
        // 2 回目以降はワイヤレスデバッグを自動でオン/オフできるようにする
        exec(adb, "pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS");
        // 1. シャッター音の強制 ON を解除する（元記事と同じ設定）
        exec(adb, "settings put system " + Device.SHUTTER_KEY + " 0");
        // 2. カメラアプリが鳴らす音を、端末の音量やマナーモードに関係なく止める
        exec(adb, "appops set " + camera + " PLAY_AUDIO ignore");
        exec(adb, "am force-stop " + camera);

        String key = exec(adb, "settings get system " + Device.SHUTTER_KEY);
        String op = exec(adb, "appops get " + camera + " PLAY_AUDIO");
        boolean keyOk = "0".equals(key);
        boolean opOk = op.contains("ignore");
        Device.prefs(this).edit()
                .putBoolean(Device.PREF_MUTED, keyOk)
                .putBoolean(Device.PREF_KEY_READABLE, "0".equals(Device.readShutterKey(this)))
                .apply();
        if (!keyOk) throw new IllegalStateException(getString(R.string.error_write_failed, key));
        return opOk
                ? getString(R.string.result_muted)
                : getString(R.string.result_partial, op);
    }

    private String restore(AdbManager adb) throws Exception {
        String camera = Device.CAMERA_PACKAGE;
        step(getString(R.string.step_writing));
        exec(adb, "settings put system " + Device.SHUTTER_KEY + " 1");
        exec(adb, "appops set " + camera + " PLAY_AUDIO allow");
        exec(adb, "am force-stop " + camera);
        Device.prefs(this).edit().putBoolean(Device.PREF_MUTED, false).apply();
        return getString(R.string.result_restored);
    }

    private String exec(AdbManager adb, String command) throws Exception {
        mStep = getString(R.string.step_command, command);
        String output = adb.exec(command);
        Device.log(this, "$ " + command + "\n" + output);
        return output;
    }

    private void needPairing(String reason) {
        Device.prefs(this).edit()
                .putBoolean(Device.PREF_PAIRED, false)
                .putBoolean(Device.PREF_WORKING, false)
                .putString(Device.PREF_ERROR, "")
                .apply();
        mBusy = false;
        startPairing();
        Device.setStatus(this, getString(R.string.need_pairing_message, reason));
        try {
            // アプリの画面が表示中なら開ける。バックグラウンドからは OS に止められるので、その場合は通知の案内に任せる。
            Device.openWirelessDebuggingSettings(this);
        } catch (RuntimeException ignored) {
        }
    }

    private void step(String step) {
        mStep = step;
        Device.log(this, "段階: " + step);
        mNotificationManager.notify(ID_WORK, progress(step + "…"));
    }

    private void disconnectQuietly() {
        AdbManager adb = mAdb;
        if (adb == null) return;
        try {
            adb.disconnect();
        } catch (Exception ignored) {
        }
    }

    private void cancel() {
        Device.log(this, "ユーザーが中止（" + mStep + "）");
        mCancelled = true;
        Thread worker = mWorker;
        if (worker != null) {
            worker.interrupt();
            disconnectQuietly();
        } else {
            finish(getString(R.string.result_cancelled), false);
        }
    }

    private void setWorking(boolean working) {
        Device.prefs(this).edit()
                .putBoolean(Device.PREF_WORKING, working)
                .putString(Device.PREF_ERROR, "")
                .apply();
    }

    private void finish(String message, boolean success) {
        mBusy = false;
        Device.prefs(this).edit()
                .putBoolean(Device.PREF_WORKING, false)
                .putBoolean(Device.PREF_PAIRING, false)
                .putBoolean(Device.PREF_PAIRING_READY, false)
                .putString(Device.PREF_ERROR, success || message == null ? "" : message)
                .apply();
        if (message != null) {
            Device.setStatus(this, message);
            mNotificationManager.notify(ID_RESULT, new Notification.Builder(this, CHANNEL_RESULT)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(getString(success ? R.string.notification_done : R.string.notification_failed))
                    .setContentText(message)
                    .setStyle(new Notification.BigTextStyle().bigText(message))
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build());
        }
        stopPairingDiscovery();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private static class DiscoveryTimeoutException extends Exception {
    }

    // ---- 通知 ----

    static void createChannels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        // ペアリングコードの入力欄を確実に出すため、作業中の通知は重要度を高くする
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_WORK, context.getString(R.string.channel_work), NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_RESULT, context.getString(R.string.channel_result), NotificationManager.IMPORTANCE_DEFAULT));
    }

    private void showForeground(Notification notification) {
        startForeground(ID_WORK, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    }

    private Notification progress(String text) {
        return new Notification.Builder(this, CHANNEL_WORK)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .addAction(cancelAction())
                .build();
    }

    private Notification pairingNotification() {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_WORK)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .addAction(cancelAction());
        if (mPairingPort > 0) {
            String text = getString(R.string.notification_pair_text);
            RemoteInput input = new RemoteInput.Builder(KEY_CODE).setLabel(getString(R.string.notification_code_label)).build();
            PendingIntent reply = PendingIntent.getForegroundService(this, 2,
                    new Intent(this, MuteService.class).setAction(ACTION_CODE),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            builder.setContentTitle(getString(R.string.notification_pair_title))
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .addAction(new Notification.Action.Builder(null, getString(R.string.notification_enter_code), reply)
                            .addRemoteInput(input).build());
        } else {
            String text = getString(R.string.notification_open_text);
            builder.setContentTitle(getString(R.string.notification_open_title))
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text));
        }
        return builder.build();
    }

    private Notification.Action cancelAction() {
        return new Notification.Action.Builder(null, getString(R.string.notification_cancel), PendingIntent.getForegroundService(this, 1,
                new Intent(this, MuteService.class).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)).build();
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
    }
}
