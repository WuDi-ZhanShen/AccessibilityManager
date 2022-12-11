package com.accessibilitymanager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.regex.Pattern;

public class daemonService extends Service {

    private SettingsValueChangeContentObserver mContentOb;
    SharedPreferences sp = null;
    Notification.Builder notification;
    NotificationManager systemService;
    String tmpsettingValue;


    //自定义一个内容监视器
    class SettingsValueChangeContentObserver extends ContentObserver {

        public SettingsValueChangeContentObserver() {
            super(new Handler());
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            //如果这俩相等，说明本次变动是APP自己改的。于是就不需要做处理。
            if (tmpsettingValue.equals(s)) return;

            doDaemon(s);
        }
    }


    private void doDaemon(String s) {

        if (s == null || s.length() == 0) s = "";
        if (sp == null) sp = getSharedPreferences("data", 0);
        String list = sp.getString("daemon", "");
        String[] Packagename = Pattern.compile(":").split(list);
        StringBuilder add = new StringBuilder();
        for (String i : Packagename) {
            if (i == null || i.equals("null") || i.length() == 0 || s.contains(i)) continue;
            add.append(i).append(":");
            Toast.makeText(daemonService.this, "保活" + i, Toast.LENGTH_SHORT).show();
        }
        String add1 = add.toString();
        if (add1.length() > 0) {
            tmpsettingValue = add1 +s;
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, tmpsettingValue);
            notification.setContentText(add1.replace(":", "\n") + new SimpleDateFormat("时间：H时m分ss秒").format(Calendar.getInstance().getTime())).setContentTitle("已保活以下无障碍服务：");
            systemService.notify(1, notification.build());
        }
    }


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Toast.makeText(daemonService.this, "启动保活", Toast.LENGTH_SHORT).show();


        //注册监视器，读取当前设置项并存到tmpsettingValue
        mContentOb = new SettingsValueChangeContentObserver();
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), true, mContentOb);
        tmpsettingValue =  Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);




        //发送前台通知
        notification = new Notification.Builder(getApplication()).setAutoCancel(true).
                setContentText("点击此处查看无障碍列表").
                setContentTitle("保活无障碍中...").
                setWhen(System.currentTimeMillis());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notification.setSmallIcon(Icon.createWithResource(this, R.drawable.icon)).
                    setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
            ;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel("daemon", "daemon", NotificationManager.IMPORTANCE_DEFAULT);
            notificationChannel.enableLights(false);
            notificationChannel.setShowBadge(false);
            notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            systemService = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            systemService.createNotificationChannel(notificationChannel);
            notification.setChannelId("daemon");
        }
        startForeground(1, notification.build());
        //先做一次保活
        doDaemon(tmpsettingValue);
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        getContentResolver().unregisterContentObserver(mContentOb);
        Toast.makeText(daemonService.this, "停止保活", Toast.LENGTH_SHORT).show();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

}
