package com.ainexus.gateway.admin;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.Account;
import com.ainexus.gateway.mapper.AccountMapper;
import com.ainexus.gateway.service.UserQuotaService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Administrator CRUD for ordinary USER accounts. */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {
    private final AccountMapper accountMapper;
    private final ConsoleAccountService accountService;
    private final UserQuotaService userQuotaService;

    @GetMapping
    public List<UserView> list() {
        return accountMapper.selectList(new LambdaQueryWrapper<Account>()
                        .eq(Account::getRole, "USER").orderByAsc(Account::getId))
                .stream().map(this::view).toList();
    }

    @PostMapping
    public UserView create(@Valid @RequestBody UserCreateRequest request) {
        validatePasswords(request.password(), request.confirmPassword());
        accountService.validateNewUsername(request.username());
        Account account = new Account();
        account.setUsername(request.username().trim());
        account.setPasswordHash(accountService.hash(request.password()));
        account.setRole("USER");
        account.setStatus(request.status() == null ? 1 : request.status());
        account.setRemark(request.remark());
        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException e) {
            throw BizException.badRequest("username_exists", "Username already exists");
        }
        return view(accountMapper.selectById(account.getId()));
    }

    @PutMapping("/{id}")
    public UserView update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        Account account = requiredUser(id);
        if (StringUtils.hasText(request.password()) || StringUtils.hasText(request.confirmPassword())) {
            validatePasswords(request.password(), request.confirmPassword());
            account.setPasswordHash(accountService.hash(request.password()));
        }
        account.setRemark(request.remark());
        account.setStatus(request.status() == null ? account.getStatus() : request.status());
        accountMapper.updateById(account);
        return view(accountMapper.selectById(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        requiredUser(id);
        accountMapper.deleteById(id);
    }

    private Account requiredUser(Long id) {
        Account account = accountMapper.selectById(id);
        if (account == null || !"USER".equals(account.getRole())) {
            throw BizException.badRequest("user_not_found", "User not found: " + id);
        }
        return account;
    }

    private void validatePasswords(String password, String confirmPassword) {
        if (!StringUtils.hasText(password) || !password.equals(confirmPassword)) {
            throw BizException.badRequest("password_mismatch", "Password and confirmation password must match");
        }
        try {
            accountService.validatePassword(password);
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("invalid_password", e.getMessage());
        }
    }

    private UserView view(Account account) {
        return new UserView(account.getId(), account.getUsername(), account.getRemark(), account.getStatus(),
                userQuotaService.quotas(account.getId()), account.getCreateTime(), account.getUpdateTime());
    }

    public record UserCreateRequest(@NotBlank String username, @NotBlank String password, @NotBlank String confirmPassword,
                                    String remark, Integer status) { }
    public record UserUpdateRequest(String password, String confirmPassword, String remark, Integer status) { }
    public record UserView(Long id, String username, String remark, Integer status,
                           List<UserQuotaService.Quota> quotas, Object createTime, Object updateTime) { }
}
