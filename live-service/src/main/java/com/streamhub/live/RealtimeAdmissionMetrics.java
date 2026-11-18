package com.streamhub.live;

import java.util.Locale;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class RealtimeAdmissionMetrics {
    public enum Reason { INVALID_REQUEST, ROOM_MISSING, ROOM_UNAVAILABLE, USER_INACTIVE,
        USER_UNAVAILABLE, NODE_CAPACITY, ROOM_CAPACITY, SUBSCRIPTION_UNAVAILABLE }
    private final MeterRegistry metrics;
    public RealtimeAdmissionMetrics(MeterRegistry metrics) { this.metrics = metrics; }
    public void reject(Reason reason) {
        metrics.counter("streamhub.realtime.admission.rejected", "reason", reason.name().toLowerCase(Locale.ROOT)).increment();
    }
    public void registered() { metrics.counter("streamhub.realtime.admission.registered").increment(); }
}
