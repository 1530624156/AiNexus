package com.ainexus.gateway.admin;

import com.ainexus.gateway.entity.Account;
import com.ainexus.gateway.entity.RequestAuditLog;
import com.ainexus.gateway.mapper.AccountMapper;
import com.ainexus.gateway.mapper.RequestAuditLogMapper;
import com.ainexus.gateway.service.ConcurrencyHistoryService;
import com.ainexus.gateway.service.GatewayActivityService;
import com.ainexus.gateway.service.GatewayConcurrencyService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sun.management.OperatingSystemMXBean;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Real-time operational snapshot for the administrator monitoring screen. */
@RestController
@RequestMapping("/api/admin/monitor")
@RequiredArgsConstructor
public class AdminMonitorController {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final GatewayConcurrencyService gatewayConcurrencyService;
    private final GatewayActivityService activityService;
    private final ConcurrencyHistoryService historyService;
    private final AccountMapper accountMapper;
    private final RequestAuditLogMapper auditMapper;

    @GetMapping("/snapshot")
    public Snapshot snapshot() {
        GatewayConcurrencyService.Snapshot gateway = gatewayConcurrencyService.snapshot();
        OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = os == null ? runtime.totalMemory() : os.getTotalMemorySize();
        long freeMemory = os == null ? runtime.freeMemory() : os.getFreeMemorySize();
        long usedMemory = Math.max(0, totalMemory - freeMemory);
        double cpuLoad = os == null ? -1 : os.getCpuLoad();
        double processLoad = os == null ? -1 : os.getProcessCpuLoad();

        List<Long> activeAccountIds = activityService.activeAccountIds();
        List<Account> accounts = activeAccountIds.isEmpty() ? List.of() : accountMapper.selectBatchIds(activeAccountIds);
        List<User> users = accounts.stream().map(account -> new User(mask(account.getUsername()), "未采集", "未采集", "活跃")).toList();
        List<Log> logs = auditMapper.selectList(new LambdaQueryWrapper<RequestAuditLog>()
                        .orderByDesc(RequestAuditLog::getCreateTime).orderByDesc(RequestAuditLog::getId).last("LIMIT 18"))
                .stream().map(this::toLog).toList();
        List<HistoryPoint> history = historyService.since(java.time.LocalDateTime.now().minusHours(24)).stream()
                .map(point -> new HistoryPoint(point.time().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), point.concurrent(), point.waiting(), point.activeUsers(), point.streams())).toList();

        return new Snapshot(
                activityService.activeUsers(), activityService.streamingRequests(), gateway.active(), gateway.waiting(), gateway.maxConcurrent(), gateway.queueCapacity(),
                percent(cpuLoad), percent(processLoad), Runtime.getRuntime().availableProcessors(),
                percent(totalMemory == 0 ? -1 : (double) usedMemory / totalMemory), gigabytes(usedMemory), gigabytes(totalMemory),
                Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime()).getSeconds(), users, logs, history
        );
    }

    private Log toLog(RequestAuditLog row) {
        int status = row.getStatusCode() == null ? 0 : row.getStatusCode();
        String level = status >= 500 ? "ERROR" : status >= 400 ? "WARN" : "INFO";
        String message = row.getErrorMsg();
        if (message == null || message.isBlank()) message = "请求 " + (row.getRequestModel() == null ? "网关" : row.getRequestModel()) + " 已完成，状态 " + status;
        return new Log(row.getCreateTime() == null ? "--:--:--" : TIME.format(row.getCreateTime().atZone(ZoneId.systemDefault()).toInstant()), level, message);
    }

    private String mask(String username) {
        if (username == null || username.isBlank()) return "***";
        if (username.length() <= 2) return username.charAt(0) + "***";
        return username.substring(0, 1) + "***" + username.substring(username.length() - 1);
    }

    private double percent(double fraction) { return fraction < 0 ? 0 : Math.round(fraction * 1000d) / 10d; }
    private double gigabytes(long bytes) { return Math.round(bytes / 1024d / 1024d / 1024d * 10d) / 10d; }

    public record Snapshot(int online, int streamingRequests, int concurrent, int waiting, int maxConcurrent, int queueCapacity,
                           double cpu, double processCpu, int cpuCores, double memory, double memoryUsedGb, double memoryTotalGb,
                           long uptimeSeconds, List<User> users, List<Log> logs, List<HistoryPoint> history) { }
    public record User(String name, String ip, String region, String status) { }
    public record Log(String time, String level, String message) { }
    public record HistoryPoint(long time, int concurrent, int waiting, int activeUsers, int streams) { }
}
