package io.github.lengsukq.opaldesk.daemon.platform;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class VirtualDisplayFactoryTest {

    public static final class FakeConfig {}

    public static final class FakeMediaProjection {}

    public static final class FakeHandler {}

    public static final class FakeCallback {}

    public static final class Api34DisplayManager {
        public Object createVirtualDisplay(FakeConfig config) { return null; }
        public Object createVirtualDisplay(FakeConfig config, FakeHandler h, FakeCallback cb) { return null; }
    }

    public static final class Api33DisplayManager {
        public Object createVirtualDisplay(FakeConfig config, FakeHandler h, FakeCallback cb) { return null; }
    }

    public static final class Api31DisplayManager {
        public Object createVirtualDisplay(FakeMediaProjection mp, FakeConfig config, FakeCallback cb, FakeHandler h) { return null; }
    }

    public static final class LegacyOnlyDisplayManager {
        public Object createVirtualDisplay(String name, int w, int h, int dpi, Object surf, int flags) { return null; }
    }

    @Test
    public void prefersOneArgOverloadOnApi34Plus() {
        VirtualDisplayFactory.ResolvedCreateMethod resolved =
                VirtualDisplayFactory.resolveCreateMethod(Api34DisplayManager.class, FakeConfig.class);
        assertEquals(VirtualDisplayFactory.CreateMode.CONFIG_1_ARG, resolved.mode);
        assertNotNull(resolved.method);
        assertEquals(1, resolved.method.getParameterCount());
    }

    @Test
    public void fallsBackToThreeArgOverloadOnApi33() {
        VirtualDisplayFactory.ResolvedCreateMethod resolved =
                VirtualDisplayFactory.resolveCreateMethod(Api33DisplayManager.class, FakeConfig.class);
        assertEquals(VirtualDisplayFactory.CreateMode.CONFIG_3_ARG, resolved.mode);
        assertNotNull(resolved.method);
        assertEquals(3, resolved.method.getParameterCount());
    }

    @Test
    public void fallsBackToFourArgOverloadOnApi30To32() {
        VirtualDisplayFactory.ResolvedCreateMethod resolved =
                VirtualDisplayFactory.resolveCreateMethod(Api31DisplayManager.class, FakeConfig.class);
        assertEquals(VirtualDisplayFactory.CreateMode.CONFIG_4_ARG, resolved.mode);
        assertNotNull(resolved.method);
        assertEquals(4, resolved.method.getParameterCount());
    }

    @Test
    public void fallsBackToLegacySixArgWhenConfigOverloadUnavailable() {
        VirtualDisplayFactory.ResolvedCreateMethod resolved =
                VirtualDisplayFactory.resolveCreateMethod(LegacyOnlyDisplayManager.class, FakeConfig.class);
        assertEquals(VirtualDisplayFactory.CreateMode.LEGACY_6_ARG, resolved.mode);
        assertNull(resolved.method);

        VirtualDisplayFactory.ResolvedCreateMethod noConfig =
                VirtualDisplayFactory.resolveCreateMethod(Api34DisplayManager.class, null);
        assertEquals(VirtualDisplayFactory.CreateMode.LEGACY_6_ARG, noConfig.mode);
    }
}
