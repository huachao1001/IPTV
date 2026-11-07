package com.iptv.live;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

/** 每日定时刷新：每天凌晨固定时间拉一次，失败保留旧数据。 */
public final class DailyRefresh {

    private static final int REQ = 1001;
    /** 定时刷新的时刻，凌晨 4:10 避开使用高峰。 */
    private static final int HOUR = 4;
    private static final int MINUTE = 10;

    private DailyRefresh() {
    }

    public static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, nextTrigger(), AlarmManager.INTERVAL_DAY,
                pending(ctx));
    }

    /** 今天 4:10 已过则顺延到明天。 */
    private static long nextTrigger() {
        Calendar c = Calendar.getInstance();
        Calendar target = (Calendar) c.clone();
        target.set(Calendar.HOUR_OF_DAY, HOUR);
        target.set(Calendar.MINUTE, MINUTE);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        if (target.getTimeInMillis() <= c.getTimeInMillis()) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        }
        return target.getTimeInMillis();
    }

    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(pending(ctx));
        }
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, RefreshReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, REQ, i, flags);
    }

    /** 后台线程执行一次刷新，不抛异常。 */
    public static void refreshNow(Context ctx, final Runnable done) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                new ChannelStore(app).refresh();
            } catch (Exception ignored) {
                // 网络不通时保留旧数据，下次定时再试
            }
            if (done != null) {
                done.run();
            }
        }, "iptv-refresh").start();
    }
}
