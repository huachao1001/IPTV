package com.iptv.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机或应用更新后重新注册定时任务，并立即拉一次。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        DailyRefresh.schedule(context);
        DailyRefresh.refreshNow(context, null);
    }
}
