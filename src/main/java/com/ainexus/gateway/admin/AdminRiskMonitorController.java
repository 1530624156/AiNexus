package com.ainexus.gateway.admin;

import com.ainexus.gateway.entity.RiskAuditEvent;
import com.ainexus.gateway.mapper.RiskAuditEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only manual-review queue for usage that may be outside the coding-agent scope. */
@RestController
@RequestMapping("/api/admin/risk-monitor")
@RequiredArgsConstructor
public class AdminRiskMonitorController {
    private final RiskAuditEventMapper riskAuditEventMapper;

    @GetMapping
    public Page<RiskAuditEvent> events(@RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "50") long size,
                                       @RequestParam(required = false) String category,
                                       @RequestParam(required = false) String username) {
        return riskAuditEventMapper.selectPage(new Page<>(Math.max(page, 1), Math.min(Math.max(size, 1), 100)),
                new LambdaQueryWrapper<RiskAuditEvent>()
                        .eq(category != null && !category.isBlank(), RiskAuditEvent::getRiskCategory, category)
                        .eq(username != null && !username.isBlank(), RiskAuditEvent::getUserName, username)
                        .orderByDesc(RiskAuditEvent::getCreateTime)
                        .orderByDesc(RiskAuditEvent::getId));
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        LocalDateTime today = LocalDateTime.of(LocalDateTime.now().toLocalDate(), LocalTime.MIN);
        List<RiskAuditEvent> todayEvents = riskAuditEventMapper.selectList(new LambdaQueryWrapper<RiskAuditEvent>()
                .ge(RiskAuditEvent::getCreateTime, today));
        Map<String, Long> categories = new LinkedHashMap<>();
        todayEvents.forEach(event -> categories.merge(event.getRiskCategory(), 1L, Long::sum));
        long pending = todayEvents.stream().filter(event -> "PENDING".equals(event.getReviewStatus())).count();
        return Map.of("todayEvents", todayEvents.size(), "pendingReview", pending, "categories", categories,
                "notice", "仅作人工复核候选，不自动限制、停用或封禁用户；事件不保存完整请求内容。");
    }
}
