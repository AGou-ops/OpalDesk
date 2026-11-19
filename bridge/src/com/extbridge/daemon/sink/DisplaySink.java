package com.extbridge.daemon.sink;

import android.view.Surface;

/**
 * Unified abstraction over display output sinks (physical HDMI/DP vs remote USB/Wi-Fi stream).
 */
public interface DisplaySink {
    int SINK_NONE = 0;
    int SINK_PHYSICAL = 1;
    int SINK_REMOTE_USB = 2;
    int SINK_REMOTE_WIFI = 3;
    int SINK_REMOTE_WIFI_ADB = 4;

    int getSinkType();

    boolean isActive();

    int getWidth();

    int getHeight();

    int getDpi();

    float getFps();

    int getBitrateKbps();

    int getActualFpsX100();

    Surface getInputSurface();

    void requestKeyFrame();

    void updateBitrateKbps(int bitrateKbps);

    void stop();
}
