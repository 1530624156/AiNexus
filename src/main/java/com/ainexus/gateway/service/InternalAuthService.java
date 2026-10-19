package com.ainexus.gateway.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 内部应用鉴权服务。
 *
 * <p>请求路径只通过 {@link RouteCacheService} 查询 Redis Token 缓存；Redis 不可用时由缓存服务回落至启动/刷新时
 * 的本机快照，不访问 MySQL。
 */
@Service
public class InternalAuthService {

    private final RouteCacheService routeCacheService;

    public InternalAuthService(RouteCacheService routeCacheService) {
        this.routeCacheService = routeCacheService;
    }

    public boolean verify(String internalToken) {
        return StringUtils.hasText(internalToken) && routeCacheService.getValidToken(internalToken) != null;
    }

    public RouteCacheService.CachedToken find(String internalToken) {
        return StringUtils.hasText(internalToken) ? routeCacheService.getValidToken(internalToken) : null;
    }
}
