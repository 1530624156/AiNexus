package com.ainexus.gateway.admin;

import com.ainexus.gateway.entity.RequestAuditLog;
import com.ainexus.gateway.mapper.ChannelMapper;
import com.ainexus.gateway.mapper.RequestAuditLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

/** 审计日志分页与仪表盘统计。 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminLogController {

    private final RequestAuditLogMapper requestAuditLogMapper;
    private final ChannelMapper channelMapper;

    @GetMapping("/logs")
    public Page<RequestAuditLog> logs(@RequestParam(defaultValue = "1") long page,
                                      @RequestParam(defaultValue = "20") long size,
                                      @RequestParam(required = false) String appName,
                                      @RequestParam(required = false) Integer statusCode) {
        long safeSize = Math.min(Math.max(size, 1), 100);
        return requestAuditLogMapper.selectPage(new Page<>(Math.max(page, 1), safeSize),
                new LambdaQueryWrapper<RequestAuditLog>()
                        .eq(appName != null && !appName.isBlank(), RequestAuditLog::getAppName, appName)
                        .eq(statusCode != null, RequestAuditLog::getStatusCode, statusCode)
                        .orderByDesc(RequestAuditLog::getCreateTime)
                        .orderByDesc(RequestAuditLog::getId));
    }

    @GetMapping("/logs/{id}")
    public RequestAuditLog detail(@PathVariable Long id) {
        return requestAuditLogMapper.selectById(id);
    }

    @GetMapping("/dashboard")
    public Map<String, Long> dashboard() {
        LocalDateTime startOfToday = LocalDateTime.of(LocalDateTime.now().toLocalDate(), LocalTime.MIN);
        long todayRequests = requestAuditLogMapper.selectCount(new LambdaQueryWrapper<RequestAuditLog>()
                .ge(RequestAuditLog::getCreateTime, startOfToday));
        long todayErrors = requestAuditLogMapper.selectCount(new LambdaQueryWrapper<RequestAuditLog>()
                .ge(RequestAuditLog::getCreateTime, startOfToday)
                .ge(RequestAuditLog::getStatusCode, 400));
        long enabledChannels = channelMapper.selectCount(new LambdaQueryWrapper<com.ainexus.gateway.entity.Channel>()
                .eq(com.ainexus.gateway.entity.Channel::getStatus, 1));
        return Map.of("todayRequests", todayRequests, "todayErrors", todayErrors, "enabledChannels", enabledChannels);
    }
}
