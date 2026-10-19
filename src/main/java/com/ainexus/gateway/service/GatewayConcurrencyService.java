package com.ainexus.gateway.service;

import com.ainexus.gateway.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Dynamic global gateway concurrency limit with bounded waiting admission. */
@Service
@RequiredArgsConstructor
public class GatewayConcurrencyService {
    private final GatewayRuntimeSettingsService settingsService;
    private final Object monitor = new Object();
    private int active;
    private int waiting;

    public Permit acquire() {
        synchronized (monitor) {
            GatewayRuntimeSettingsService.Settings settings = settingsService.get();
            if (settings.maxConcurrent() == 0) { active++; return new Permit(); }
            if (active >= settings.maxConcurrent()) {
                if (settings.queueCapacity() == 0 || waiting >= settings.queueCapacity()) {
                    throw BizException.rateLimited("Gateway concurrency limit reached and waiting queue is full");
                }
                waiting++;
                try {
                    while (active >= settingsService.get().maxConcurrent()) monitor.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw BizException.rateLimited("Gateway request interrupted while waiting for concurrency permit");
                } finally { waiting--; }
            }
            active++;
            return new Permit();
        }
    }

    public Snapshot snapshot() { synchronized (monitor) { return new Snapshot(active, waiting, settingsService.get().maxConcurrent(), settingsService.get().queueCapacity()); } }

    public record Snapshot(int active, int waiting, int maxConcurrent, int queueCapacity) { }

    public final class Permit implements AutoCloseable {
        private boolean released;
        @Override public void close() {
            synchronized (monitor) {
                if (!released) { released = true; active--; monitor.notifyAll(); }
            }
        }
    }
}
