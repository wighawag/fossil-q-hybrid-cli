package nodomain.freeyourgadget.gadgetbridge.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GB {
    private static final Logger LOG = LoggerFactory.getLogger("GB");
    public static final int INFO = 0, WARN = 1, ERROR = 2;

    public static void toast(String msg, int duration, int severity) {
        LOG.info("[toast] {}", msg);
    }

    public static void toast(String msg, int duration, int severity, Throwable e) {
        LOG.error("[toast] {}", msg, e);
    }

    public static void toast(Object context, String msg, int duration, int severity) {
        LOG.info("[toast] {}", msg);
    }

    public static void toast(Object context, String msg, int duration, int severity, Throwable e) {
        LOG.error("[toast] {}", msg, e);
    }

    public static void updateTransferNotification(String a, String b, boolean c, int d, Object ctx) {
    }

    public static void updateInstallNotification(String a, boolean b, int c, Object ctx) {
    }

    public static void signalActivityDataFinish(Object device) {
    }
}
