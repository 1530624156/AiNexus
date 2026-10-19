package com.ainexus.gateway.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 公共虚拟线程执行器：审计日志和渠道失败计数均不得阻塞请求/SSE 处理线程。 */
@Component
public class VirtualThreadTaskExecutor {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public void submit(Runnable task) {
        executor.submit(task);
    }

    @PreDestroy
    public void shutdown() {
        executor.close();
    }
}
