package com.ainexus.gateway.admin;

import com.ainexus.gateway.auth.ConsolePasswordService;
import com.ainexus.gateway.config.GatewayProperties;
import com.ainexus.gateway.entity.Account;
import com.ainexus.gateway.mapper.AccountMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Account lifecycle and bootstrap administrator creation. */
@Service
@RequiredArgsConstructor
public class ConsoleAccountService {
    private final AccountMapper accountMapper;
    private final ConsolePasswordService passwordService;
    private final GatewayProperties properties;

    @PostConstruct
    public void ensureBootstrapAdmin() {
        Long count = accountMapper.selectCount(new LambdaQueryWrapper<Account>().eq(Account::getRole, "ADMIN"));
        if (count != null && count > 0) return;
        Account admin = new Account();
        admin.setUsername("admin");
        admin.setPasswordHash(passwordService.hash(properties.getConsole().getBootstrapAdminPassword()));
        admin.setRole("ADMIN");
        admin.setStatus(1);
        admin.setRemark("Bootstrap administrator");
        accountMapper.insert(admin);
    }

    public Account findByUsername(String username) {
        return accountMapper.selectOne(new LambdaQueryWrapper<Account>().eq(Account::getUsername, username));
    }

    public void validateNewUsername(String username) {
        if (!StringUtils.hasText(username) || username.trim().length() < 3 || username.trim().length() > 64) {
            throw new IllegalArgumentException("Username must be 3 to 64 characters");
        }
    }

    public void validatePassword(String password) {
        if (!StringUtils.hasText(password) || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("Password must be 8 to 128 characters");
        }
    }

    public String hash(String password) { return passwordService.hash(password); }
    public boolean matches(String password, String storedHash) { return passwordService.matches(password, storedHash); }
}
