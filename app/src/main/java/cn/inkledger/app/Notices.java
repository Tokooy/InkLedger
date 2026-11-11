package cn.inkledger.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

final class Notices {
    static final int MONITOR_ID = 1;
    private static final int RESULT_ID = 2;
    private static final String MONITOR = "screenshot_monitor";
    private static final String RESULT = "recognized_expenses";

    private Notices() {}

    static Notification monitor(Context context) {
        NotificationChannel channel = new NotificationChannel(MONITOR, "截图识别服务",
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        PendingIntent stop = PendingIntent.getService(context, 1,
                new Intent(context, ScreenshotService.class).setAction(ScreenshotService.STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(context, MONITOR)
                .setSmallIcon(R.drawable.ic_notice)
                .setContentTitle("墨账正在识别截图")
                .setContentText("仅在本机处理新保存的截图")
                .setContentIntent(open(context))
                .setShowWhen(false)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(new Notification.Action.Builder((Icon) null, "停止识别", stop).build())
                .build();
    }

    static void expense(Context context, PaymentParser.Result payment) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return;
        NotificationChannel channel = new NotificationChannel(RESULT, "消费识别结果",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);

        String amount = Expense.money(payment.cents());
        String category = payment.category().label;
        Notification notification = new Notification.Builder(context, RESULT)
                .setSmallIcon(R.drawable.ic_notice)
                .setContentTitle(amount + " · " + category)
                .setContentText(payment.merchant())
                .setContentIntent(open(context))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setTimeoutAfter(120_000L)
                .build();
        addIsland(context, notification, amount, category, payment.merchant());
        manager.notify(RESULT_ID, notification);
    }

    private static void addIsland(Context context, Notification notification, String amount,
                                  String category, String merchant) {
        String business = context.getString(R.string.xiaomi_island_business).trim();
        String appId = context.getString(R.string.xiaomi_app_id).trim();
        if (business.isEmpty() || appId.isEmpty() || !focusAllowed(context)) return;
        try {
            String pictureKey = "miui.focus.pic_ledger";
            JSONObject picture = new JSONObject().put("type", 1).put("pic", pictureKey);
            JSONObject text = new JSONObject().put("title", amount).put("content", category);
            JSONObject large = new JSONObject().put("imageTextInfoLeft", new JSONObject()
                    .put("type", 1).put("picInfo", picture).put("miui.focus.paramtextInfo", text));
            JSONObject island = new JSONObject().put("islandProperty", 1).put("islandTimeout", 12)
                    .put("bigIslandArea", large)
                    .put("smallIslandArea", new JSONObject().put("picInfo", picture));
            JSONObject base = new JSONObject().put("type", 2)
                    .put("title", amount + " · " + category).put("content", merchant);
            JSONObject parameters = new JSONObject().put("protocol", 1).put("business", business)
                    .put("islandFirstFloat", true).put("timeout", 2)
                    .put("baseInfo", base).put("param_island", island);
            Bundle pictures = new Bundle();
            pictures.putParcelable(pictureKey, Icon.createWithResource(context, R.drawable.ic_launcher));
            notification.extras.putBundle("miui.focus.pics", pictures);
            notification.extras.putString("miui.focus.param",
                    new JSONObject().put("param_v2", parameters).toString());
        } catch (JSONException ignored) {
            // The standard notification already contains the complete result.
        }
    }

    private static boolean focusAllowed(Context context) {
        try {
            if (Settings.System.getInt(context.getContentResolver(), "notification_focus_protocol", 0) < 3) return false;
            Bundle request = new Bundle();
            request.putString("package", context.getPackageName());
            Bundle answer = context.getContentResolver().call(
                    Uri.parse("content://miui.statusbar.notification.public"), "canShowFocus", null, request);
            return answer != null && answer.getBoolean("canShowFocus");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static PendingIntent open(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
