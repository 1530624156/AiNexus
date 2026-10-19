package com.ainexus.gateway.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Tracks active caller accounts and streaming requests for live operations dashboards. */
@Service
public class GatewayActivityService {
    private final ConcurrentHashMap<Long, AtomicInteger> activeAccounts = new ConcurrentHashMap<>();
    private final AtomicInteger streams = new AtomicInteger();

    public Permit begin(Long accountId, boolean stream) {
        if (accountId != null) activeAccounts.computeIfAbsent(accountId, ignored -> new AtomicInteger()).incrementAndGet();
        if (stream) streams.incrementAndGet();
        return () -> {
            if (accountId != null) {
                AtomicInteger count = activeAccounts.get(accountId);
                if (count != null && count.decrementAndGet() <= 0) activeAccounts.remove(accountId, count);
            }
            if (stream) streams.decrementAndGet();
        };
    }
    public int activeUsers() { return activeAccounts.size(); }
    public int streamingRequests() { return streams.get(); }
    @FunctionalInterface public interface Permit extends AutoCloseable { @Override void close(); }
}
