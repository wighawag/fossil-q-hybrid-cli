package nodomain.freeyourgadget.gadgetbridge.model;

import java.util.concurrent.atomic.AtomicInteger;

public class NotificationSpec {
    private static final AtomicInteger counter = new AtomicInteger((int) (System.currentTimeMillis() / 1000));
    private final int id;

    public String body;
    public int dndSuppressed;

    public NotificationSpec() {
        this.id = counter.incrementAndGet();
    }

    public NotificationSpec(int id) {
        this.id = (id != -1) ? id : counter.incrementAndGet();
    }

    public int getId() {
        return id;
    }
}
