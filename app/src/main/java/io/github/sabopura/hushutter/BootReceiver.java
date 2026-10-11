package io.github.sabopura.hushutter;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** OS アップデートなどでシャッター音の設定が戻っていたら、再設定を促す通知を出す。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences prefs = Device.prefs(context);
        // 無音化済みで、かつアプリから設定値を読める端末だけ確認する
        if (!prefs.getBoolean(Device.PREF_MUTED, false) || !prefs.getBoolean(Device.PREF_KEY_READABLE, false)) return;
        if ("0".equals(Device.readShutterKey(context))) return;

        prefs.edit().putBoolean(Device.PREF_MUTED, false).apply();
        MuteService.createChannels(context);
        PendingIntent open = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_MUTE_NOW, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        context.getSystemService(NotificationManager.class).notify(MuteService.ID_RESULT,
                new Notification.Builder(context, MuteService.CHANNEL_RESULT)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(context.getString(R.string.boot_title))
                        .setContentText(context.getString(R.string.boot_text))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build());
    }
}
