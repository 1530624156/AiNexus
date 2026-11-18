package com.ainexus.gateway.risk;

import com.ainexus.gateway.service.AuditDimensionService;
import com.ainexus.gateway.service.RiskAuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/** Captures acceptable-use review candidates after the gateway accepts a request. */
@Aspect
@Component
@RequiredArgsConstructor
public class RiskUseAuditAspect {
    private final RiskUseDetector detector;
    private final RiskAuditService riskAuditService;
    private final AuditDimensionService auditDimensionService;

    @After("execution(* com.ainexus.gateway.controller.ChatController.chatCompletions(..)) || "
            + "execution(* com.ainexus.gateway.controller.ChatController.relayOthers(..))")
    public void audit(JoinPoint joinPoint) {
        HttpServletRequest request = null;
        byte[] body = null;
        for (Object argument : joinPoint.getArgs()) {
            if (argument instanceof HttpServletRequest candidate) request = candidate;
            if (argument instanceof byte[] candidate) body = candidate;
        }
        RiskUseDetector.Detection detection = detector.detect(body);
        if (request == null || detection == null) return;
        Long accountId = (Long) request.getAttribute("ainexus.auth.accountId");
        AuditDimensionService.Dimensions dimensions = auditDimensionService.resolve(accountId, null);
        riskAuditService.submit(new RiskAuditService.RiskEvent(
                (String) request.getAttribute("ainexus.requestId"), accountId, dimensions.userName(),
                (String) request.getAttribute("ainexus.auth.appName"), detection.category(), detection.score(),
                detection.signals(), detection.fingerprint(), detection.preview()));
    }
}
