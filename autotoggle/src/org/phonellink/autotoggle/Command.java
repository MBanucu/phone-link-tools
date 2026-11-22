package org.phonelink.autotoggle;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.List;

/**
 * adb entry point:
 *
 * <pre>
 *   adb shell am broadcast -a org.phonelink.autotoggle.TOGGLE \
 *       --es labels "Phone Link Boot pre;Phone Link Boot post"
 * </pre>
 */
public final class Command extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ToggleService.ACTION_LIST.equals(intent.getAction())) {
            ToggleService.requestList();
            return;
        }
        if (!ToggleService.ACTION_TOGGLE.equals(intent.getAction())) {
            return;
        }
        String raw = intent.getStringExtra("labels");
        if (raw == null || raw.isEmpty()) {
            Log.i(ToggleService.TAG, "no labels; nothing to do");
            return;
        }
        List<String> labels = ToggleService.split(raw);
        Log.i(ToggleService.TAG, "command: " + labels);
        ToggleService.request(labels.toArray(new String[0]));
    }
}
