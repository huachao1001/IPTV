package com.iptv.live;

import com.iptv.live.source.DailyRefresh;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 每日定时任务入口。 */
public class RefreshReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        DailyRefresh.schedule(context);
        DailyRefresh.refreshNow(context, null);
    }
}
