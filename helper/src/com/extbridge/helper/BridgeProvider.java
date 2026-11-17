package com.extbridge.helper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

public class BridgeProvider extends ContentProvider {
    private static final Binder sProcToken = new Binder();

    @Override
    public boolean onCreate() {
        return true;
    }

    private boolean isTrustedCaller() {
        int uid = Binder.getCallingUid();
        if (uid == 0 || uid == 1000 || uid == 2000) return true;
        return uid == android.os.Process.myUid();
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!isTrustedCaller()) {
            Bundle denied = new Bundle();
            denied.putBoolean("ok", false);
            return denied;
        }
        if ("setBinder".equals(method) && extras != null) {
            if (BridgeService.sUserStopping) {
                Bundle denied = new Bundle();
                denied.putBoolean("ok", false);
                return denied;
            }
            IBinder b = extras.getBinder("cb");
            boolean descOk = false;
            if (b != null && b.pingBinder()) {
                try {
                    descOk = BridgeClient.BINDER_DESC.equals(b.getInterfaceDescriptor());
                } catch (Throwable ignored) {}
            }
            if (descOk) {
                BridgeClient.setBinder(b);
                BridgeService.ensureStarted(getContext());
                BridgeService.refreshFromReceiver(getContext());
                Bundle ok = new Bundle();
                ok.putBoolean("ok", true);
                ok.putBinder("proc", sProcToken);
                return ok;
            }
        } else if ("syncState".equals(method)) {
            android.content.Context ctx = getContext();
            if (ctx != null && !BridgeService.sUserStopping) {
                BridgeService.refreshFromReceiver(ctx);
                try {
                    android.content.Intent syncIntent = new android.content.Intent("com.extbridge.helper.STATE_SYNC")
                            .setPackage(ctx.getPackageName());
                    ctx.sendBroadcast(syncIntent);
                } catch (Throwable ignored) {}
            }
            Bundle ok = new Bundle();
            ok.putBoolean("ok", true);
            return ok;
        } else if ("mirEdgeCapture".equals(method) && extras != null) {
            if (!BridgeService.sUserStopping) {
                boolean enter = extras.getBoolean("enter", true);
                BridgeService.onEdgeCaptureFromDaemon(enter);
            }
            Bundle ok = new Bundle();
            ok.putBoolean("ok", true);
            return ok;
        }
        return new Bundle();
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] sa, String so) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] sa) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] sa) { return 0; }
}
