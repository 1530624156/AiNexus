package com.ainexus.gateway.admin;

import com.ainexus.gateway.auth.ConsoleAuthInterceptor;
import com.ainexus.gateway.auth.ConsoleSessionService;
import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.Account;
import com.ainexus.gateway.mapper.AccountMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Settings for the currently authenticated administrator. */
@RestController
@RequestMapping("/api/admin/system")
@RequiredArgsConstructor
public class AdminSystemController {
    private final AccountMapper accountMapper;
    private final ConsoleAccountService accountService;

    @PostMapping("/change-password")
    public void changePassword(HttpServletRequest request, @Valid @RequestBody ChangePasswordRequest body) {
        ConsoleSessionService.SessionAccount session = (ConsoleSessionService.SessionAccount) request.getAttribute(ConsoleAuthInterceptor.ATTRIBUTE);
        if (session == null) throw BizException.unauthorized("console_login_required", "Administrator console login is required to change password");
        Account account = accountMapper.selectById(session.id());
        if (account == null || !"ADMIN".equals(account.getRole())) throw BizException.forbidden("admin_role_required", "Administrator role is required");
        if (!accountService.matches(body.currentPassword(), account.getPasswordHash())) throw BizException.badRequest("current_password_incorrect", "Current password is incorrect");
        if (!body.newPassword().equals(body.confirmPassword())) throw BizException.badRequest("password_mismatch", "New password and confirmation password must match");
        try { accountService.validatePassword(body.newPassword()); } catch (IllegalArgumentException e) { throw BizException.badRequest("invalid_password", e.getMessage()); }
        account.setPasswordHash(accountService.hash(body.newPassword()));
        accountMapper.updateById(account);
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword, @NotBlank String confirmPassword) { }
}
