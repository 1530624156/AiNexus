package com.ainexus.gateway;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AiNexus AI 网关启动类
 *
 * <p>架构基调: Servlet(Tomcat) + 阻塞式编程 + 虚拟线程(spring.threads.virtual.enabled=true)。
 * 不引入 WebFlux/Spring AI/LangChain4j, 网关只做纯 HTTP 报文透传。
 */
@EnableScheduling
@ConfigurationPropertiesScan
@MapperScan("com.ainexus.gateway.mapper")
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
