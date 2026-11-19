package com.extbridge.daemon.display;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Parcel;
import android.os.SystemClock;

import com.extbridge.daemon.platform.AndroidServices;
import com.extbridge.daemon.util.ShellCommand;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Rate-limited mirror sidebar telemetry collector (Binder code 39): audio output route,
 * now-playing MediaSession metadata, sticky battery stats, Wi-Fi link speed/RSSI, and
 * upcoming calendar instances.
 */
final class MirrorTelemetryCollector {
    private static final long CACHE_TTL_MS = 1800L;

    private final AndroidServices services;
    private final Supplier<String> btNameSupplier;
    private final AtomicLong lastRefreshMs = new AtomicLong(0L);

    private volatile String audioRoute = "平板外放";
    private volatile String audioDetail = "";
    private volatile String mediaPkg = "";
    private volatile int mediaState = 0;
    private volatile String mediaTitle = "";
    private volatile String mediaSub = "";
    private volatile String calendarText = "";
    private volatile String battDetail = "";
    private volatile String wifiDetail = "";

    MirrorTelemetryCollector(AndroidServices services, Supplier<String> btNameSupplier) {
        this.services = services;
        this.btNameSupplier = btNameSupplier;
    }

    void writeToParcel(Parcel reply) {
        if (reply == null) return;
        refreshIfNeeded();
        reply.writeString(nonNull(audioRoute));
        reply.writeString(nonNull(audioDetail));
        reply.writeString(nonNull(mediaPkg));
        reply.writeInt(mediaState);
        reply.writeString(nonNull(mediaTitle));
        reply.writeString(nonNull(mediaSub));
        reply.writeString(nonNull(calendarText));
        reply.writeString(nonNull(battDetail));
        reply.writeString(nonNull(wifiDetail));
    }

    private void refreshIfNeeded() {
        long now = SystemClock.uptimeMillis();
        long prev = lastRefreshMs.get();
        if (now - prev < CACHE_TTL_MS || !lastRefreshMs.compareAndSet(prev, now)) return;

        refreshAudio();
        refreshMediaSession();
        battDetail = readBatteryDetail();
        wifiDetail = readWifiDetail();
        calendarText = readCalendarSummary();
    }

    private void refreshAudio() {
        String route = "平板外放";
        String det = "";
        try {
            AudioManager am = (AudioManager) services.context.getSystemService(Context.AUDIO_SERVICE);
            int curVol = (am != null) ? am.getStreamVolume(AudioManager.STREAM_MUSIC) : 0;
            int maxVol = (am != null) ? Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) : 15;
            boolean muted = (am != null) && (curVol == 0 || am.isStreamMute(AudioManager.STREAM_MUSIC));

            String devStr = ShellCommand.runOutput("sh", "-c",
                    "dumpsys audio 2>/dev/null | grep -A 8 'STREAM_MUSIC:' | grep 'Devices:' | head -n 1")
                    .trim().toLowerCase(Locale.US);
            String btName = nonNull(btNameSupplier.get());
            if (devStr.contains("hdmi") || devStr.contains("aux_digital")) route = "HDMI / DP 外接音频";
            else if (devStr.contains("bt_a2dp") || devStr.contains("ble_headset") || devStr.contains("bt_sco"))
                route = btName.isEmpty() ? "蓝牙音频设备" : ("蓝牙 · " + btName);
            else if (devStr.contains("usb")) route = "USB-C 外置声卡";
            else if (devStr.contains("wired") || devStr.contains("headphone") || devStr.contains("headset"))
                route = "有线耳机";
            else route = (am != null && devStr.isEmpty()) ? resolveAudioRouteFromDevices(am) : "平板扬声器";
            det = muted ? "静音 (0%)"
                    : ("媒体音量 " + Math.round(curVol * 100f / maxVol) + "% (" + curVol + "/" + maxVol + ")");
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_AUDIO_TELEMETRY_WARN: " + e.getMessage());
        }
        audioRoute = route;
        audioDetail = det;
    }

    private static String resolveAudioRouteFromDevices(AudioManager am) {
        boolean hdmi = false, bt = false, usb = false, wired = false;
        String btProduct = "";
        AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (outs != null) {
            for (AudioDeviceInfo d : outs) {
                if (d == null) continue;
                int tp = d.getType();
                if (tp == AudioDeviceInfo.TYPE_HDMI || tp == AudioDeviceInfo.TYPE_HDMI_ARC || tp == 10 || tp == 11) hdmi = true;
                else if (tp == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || tp == AudioDeviceInfo.TYPE_BLE_HEADSET
                        || tp == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                    bt = true;
                    if (d.getProductName() != null) btProduct = d.getProductName().toString();
                } else if (tp == AudioDeviceInfo.TYPE_USB_DEVICE || tp == AudioDeviceInfo.TYPE_USB_HEADSET
                        || tp == AudioDeviceInfo.TYPE_USB_ACCESSORY) usb = true;
                else if (tp == AudioDeviceInfo.TYPE_WIRED_HEADSET || tp == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) wired = true;
            }
        }
        if (bt) return btProduct.isEmpty() ? "蓝牙音频" : ("蓝牙 · " + btProduct);
        if (usb) return "USB 音频";
        if (wired) return "有线耳机";
        return hdmi ? "HDMI 外接音频" : "平板扬声器";
    }

    private void refreshMediaSession() {
        String curPkg = "", bestPkg = "", bestTitle = "", bestSub = "";
        int curState = 0, bestScore = -1, bestState = 0;
        for (String ln : ShellCommand.runOutput("dumpsys", "media_session").split("\n")) {
            String t = ln.trim();
            if (t.startsWith("package=")) { curPkg = t.substring(8).trim(); curState = 0; }
            else if (t.contains("state=PLAYING")) curState = 3;
            else if (t.contains("state=PAUSED")) curState = 2;
            else if (t.contains("state=STOPPED")) curState = 1;
            else if (t.startsWith("metadata:") && t.contains("description=")) {
                String desc = t.substring(t.indexOf("description=") + 12).trim();
                if (curPkg.isEmpty() || "com.android.server.telecom".equals(curPkg)
                        || desc.isEmpty() || "null".equals(desc)) continue;
                int score = (curState == 3 ? 30 : (curState == 2 ? 20 : 10));
                if (score > bestScore) {
                    bestScore = score;
                    bestPkg = curPkg;
                    bestState = curState;
                    String[] segs = desc.split(",");
                    bestTitle = segs.length > 0 ? segs[0].trim() : desc;
                    String s1 = segs.length > 1 ? segs[1].trim() : "";
                    bestSub = "null".equals(s1) ? "" : s1;
                }
            }
        }
        mediaPkg = bestScore >= 0 ? bestPkg : "";
        mediaState = bestScore >= 0 ? bestState : 0;
        mediaTitle = bestScore >= 0 ? bestTitle : "";
        mediaSub = bestScore >= 0 ? bestSub : "";
    }

    private String readBatteryDetail() {
        try {
            Intent bat = (services.context != null)
                    ? services.context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)) : null;
            if (bat == null) return "";
            int plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            int temp10 = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            int voltMv = bat.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            String src = ((plugged & BatteryManager.BATTERY_PLUGGED_AC) != 0) ? "AC快充"
                    : (((plugged & BatteryManager.BATTERY_PLUGGED_USB) != 0) ? "USB供电"
                    : (((plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) ? "无线充电" : "电池供电"));
            if (temp10 > 0) src += String.format(Locale.US, " · %.1f℃", temp10 / 10f);
            if (voltMv > 1000) src += String.format(Locale.US, " · %.2fV", voltMv / 1000f);
            return src;
        } catch (RuntimeException e) { return ""; }
    }

    @SuppressWarnings("deprecation")
    private String readWifiDetail() {
        try {
            WifiManager wm = (WifiManager) services.context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null && wi.getLinkSpeed() > 0) {
                    return wi.getLinkSpeed() + " Mbps · " + wi.getRssi() + " dBm";
                }
            }
        } catch (RuntimeException e) {
            System.err.println("EXTBRIDGE_WIFI_DETAIL_WARN: " + e.getMessage());
        }
        return "";
    }

    private String readCalendarSummary() {
        try {
            Calendar cal = Calendar.getInstance();
            long nowEpoch = cal.getTimeInMillis();
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            long start = cal.getTimeInMillis();
            Uri uri = Uri.parse("content://com.android.calendar/instances/when/" + start + "/" + (start + 48L * 3600_000L));
            StringBuilder sb = new StringBuilder();
            try (Cursor c = services.context.getContentResolver().query(
                    uri, new String[]{"title", "begin", "allDay"}, null, null, "begin ASC")) {
                if (c != null) {
                    SimpleDateFormat tf = new SimpleDateFormat("HH:mm", Locale.CHINA);
                    SimpleDateFormat df = new SimpleDateFormat("MM/dd", Locale.CHINA);
                    int shown = 0;
                    while (c.moveToNext() && shown < 2) {
                        String title = c.getString(0);
                        long begin = c.getLong(1);
                        int allDay = c.getInt(2);
                        if (title == null || title.trim().isEmpty()) continue;
                        if (begin < nowEpoch - 3600_000L && allDay == 0) continue;
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(allDay == 1 ? (df.format(new Date(begin)) + " 全天") : tf.format(new Date(begin)))
                                .append(' ').append(title.trim());
                        shown++;
                    }
                }
            }
            return sb.length() > 0 ? sb.toString() : "近两日暂无日历日程";
        } catch (RuntimeException e) {
            return "近两日暂无日历日程";
        }
    }

    private static String nonNull(String s) { return (s != null) ? s : ""; }
}
