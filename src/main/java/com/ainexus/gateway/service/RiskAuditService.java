package com.ainexus.gateway.service;

import com.ainexus.gateway.entity.RiskAuditEvent;
import com.ainexus.gateway.mapper.RiskAuditEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Persists review candidates asynchronously so proxy latency is unaffected. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskAuditService {
    private final RiskAuditEventMapper riskAuditEventMapper;
    private final VirtualThreadTaskExecutor virtualThreadTaskExecutor;

    public void submit(RiskEvent event) {
        virtualThreadTaskExecutor.submit(() -> {
            try {
                RiskAuditEvent row = new RiskAuditEvent();
                row.setRequestId(truncate(event.requestId(), 64));
                row.setAccountId(event.accountId());
                row.setUserName(truncate(event.userName(), 128));
                row.setAppName(truncate(event.appName(), 128));
                row.setRiskCategory(truncate(event.category(), 64));
                row.setRiskScore(event.score());
                row.setMatchedSignals(truncate(event.signals(), 512));
                row.setContentFingerprint(truncate(event.fingerprint(), 64));
                row.setReviewStatus("PENDING");
                riskAuditEventMapper.insert(row);
            } catch (Exception e) {
                log.warn("[risk-audit] async insert failed: {}", e.toString());
            }
        });
    }

    private String truncate(String value, int limit) {
        return value == null || value.length() <= limit ? value : value.substring(0, limit);
    }

    public record RiskEvent(String requestId, Long accountId, String userName, String appName,
                            String category, int score, String signals, String fingerprint, String preview) {
    }
}
