package com.ainexus.gateway.service;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.MailSetting;
import com.ainexus.gateway.mapper.MailSettingMapper;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Pattern;

/** Persists SMTP configuration and creates isolated mail senders from the current settings. */
@Service
@RequiredArgsConstructor
public class MailSettingsService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final MailSettingMapper mapper;

    public MailSettings settings() {
        return toView(setting());
    }

    public synchronized MailSettings update(Update request) {
        MailSetting previous = setting();
        MailSetting next = validate(request, previous);
        mapper.updateById(next);
        return toView(next);
    }

    public void sendTest(TestRequest request) {
        MailSetting configured = validate(request.settings(), setting());
        if (!configured.getEnabled().equals(1)) {
            throw BizException.badRequest("mail_disabled", "请在当前表单中启用邮件发送后再测试");
        }
        String recipient = normalizeEmail(request.recipient(), "测试收件人邮箱");
        try {
            JavaMailSenderImpl sender = sender(configured);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setTo(recipient);
            helper.setFrom(configured.getFromAddress(), configured.getFromName());
            helper.setSubject("AiNexus SMTP 测试邮件");
            helper.setText("<html><body style=\"font-family:Arial,sans-serif;color:#243042\">"
                    + "<h2>AiNexus SMTP 配置成功</h2><p>这是一封 HTML 测试邮件。</p>"
                    + "<p>如果你收到此邮件，当前 SMTP 配置可以正常发送 HTML 内容。</p>"
                    + "</body></html>", true);
            sender.send(message);
        } catch (Exception e) {
            throw BizException.badRequest("mail_send_failed", "测试邮件发送失败: " + message(e));
        }
    }

    private MailSetting setting() {
        MailSetting row = mapper.selectById(1L);
        if (row != null) return row;
        MailSetting fallback = new MailSetting();
        fallback.setId(1L); fallback.setEnabled(0); fallback.setPort(587); fallback.setStartTlsEnabled(1); fallback.setSslEnabled(0);
        return fallback;
    }

    private MailSetting validate(Update request, MailSetting previous) {
        if (request == null) throw BizException.badRequest("invalid_mail_setting", "邮件配置不能为空");
        boolean enabled = Boolean.TRUE.equals(request.enabled());
        String host = blankToNull(request.host());
        int port = request.port() == null ? 587 : request.port();
        if (port < 1 || port > 65535) throw BizException.badRequest("invalid_mail_port", "SMTP 端口必须在 1 到 65535 之间");
        boolean ssl = Boolean.TRUE.equals(request.sslEnabled());
        boolean startTls = Boolean.TRUE.equals(request.startTlsEnabled());
        if (ssl && startTls) throw BizException.badRequest("invalid_mail_security", "SSL 与 STARTTLS 不能同时启用");
        MailSetting next = new MailSetting();
        next.setId(1L); next.setEnabled(enabled ? 1 : 0);
        next.setHost(host); next.setPort(port); next.setUsername(blankToNull(request.username()));
        String password = blankToNull(request.password());
        next.setPassword(password == null || isMasked(password) ? previous.getPassword() : password);
        String fromAddress = blankToNull(request.fromAddress());
        next.setFromAddress(fromAddress == null ? null : normalizeEmail(fromAddress, "发件人邮箱"));
        next.setFromName(blankToNull(request.fromName()));
        next.setStartTlsEnabled(startTls ? 1 : 0); next.setSslEnabled(ssl ? 1 : 0);
        if (enabled) {
            next.setHost(required(host, "SMTP 主机"));
            next.setFromAddress(normalizeEmail(fromAddress, "发件人邮箱"));
            if (!StringUtils.hasText(next.getPassword())) {
                throw BizException.badRequest("missing_mail_password", "启用邮件发送时必须填写 SMTP 密码或授权码");
            }
        }
        return next;
    }

    private JavaMailSenderImpl sender(MailSetting setting) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(setting.getHost()); sender.setPort(setting.getPort()); sender.setUsername(setting.getUsername()); sender.setPassword(setting.getPassword());
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.transport.protocol", "smtp");
        properties.put("mail.smtp.auth", StringUtils.hasText(setting.getUsername()) ? "true" : "false");
        properties.put("mail.smtp.starttls.enable", setting.getStartTlsEnabled() == 1 ? "true" : "false");
        properties.put("mail.smtp.ssl.enable", setting.getSslEnabled() == 1 ? "true" : "false");
        properties.put("mail.smtp.connectiontimeout", "10000"); properties.put("mail.smtp.timeout", "15000"); properties.put("mail.smtp.writetimeout", "15000");
        return sender;
    }

    private MailSettings toView(MailSetting setting) {
        return new MailSettings(setting.getEnabled() != null && setting.getEnabled() == 1, setting.getHost(), setting.getPort(), setting.getUsername(),
                mask(setting.getPassword()), setting.getFromAddress(), setting.getFromName(), setting.getStartTlsEnabled() != null && setting.getStartTlsEnabled() == 1,
                setting.getSslEnabled() != null && setting.getSslEnabled() == 1);
    }

    private String normalizeEmail(String value, String label) {
        String email = required(value, label);
        if (!EMAIL.matcher(email).matches()) throw BizException.badRequest("invalid_mail_address", label + "格式不正确");
        return email;
    }
    private String required(String value, String label) { String trimmed = blankToNull(value); if (trimmed == null) throw BizException.badRequest("invalid_mail_setting", label + "不能为空"); return trimmed; }
    private String blankToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private boolean isMasked(String value) { return value.matches("^\\*{8,}$"); }
    private String mask(String value) { return StringUtils.hasText(value) ? "********" : null; }
    private String message(Exception error) { return StringUtils.hasText(error.getMessage()) ? error.getMessage() : error.getClass().getSimpleName(); }

    public record Update(Boolean enabled, String host, Integer port, String username, String password, String fromAddress,
                         String fromName, Boolean startTlsEnabled, Boolean sslEnabled) { }
    public record MailSettings(boolean enabled, String host, Integer port, String username, String password, String fromAddress,
                               String fromName, boolean startTlsEnabled, boolean sslEnabled) { }
    public record TestRequest(Update settings, String recipient) { }
}
