package com.ainexus.gateway.service;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.config.GatewayProperties;
import com.ainexus.gateway.entity.Account;
import com.ainexus.gateway.entity.AccountGithubBinding;
import com.ainexus.gateway.entity.GithubStarSetting;
import com.ainexus.gateway.entity.Token;
import com.ainexus.gateway.mapper.AccountGithubBindingMapper;
import com.ainexus.gateway.mapper.AccountMapper;
import com.ainexus.gateway.mapper.GithubStarSettingMapper;
import com.ainexus.gateway.mapper.TokenMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** GitHub OAuth binding and repository-star access enforcement for USER API keys. */
@Service
@Slf4j
@RequiredArgsConstructor
public class GithubStarGateService {
    private static final String GITHUB_API = "https://api.github.com";
    private final GithubStarSettingMapper settingMapper;
    private final AccountGithubBindingMapper bindingMapper;
    private final AccountMapper accountMapper;
    private final TokenMapper tokenMapper;
    private final RouteCacheService routeCacheService;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private java.time.LocalDate lastDailyCheck;

    public Setting settings() { GithubStarSetting row = settingMapper.selectById(1L); return row == null ? Setting.defaults() : new Setting(row.getEnabled() != null && row.getEnabled() == 1, row.getRepository(), row.getPublicBaseUrl(), row.getOauthClientId(), row.getOauthClientSecret(), row.getDailyCheckTime()); }
    public synchronized Setting update(Update update) {
        String repository = cleanRepository(update.repository());
        String baseUrl = cleanBaseUrl(update.publicBaseUrl());
        boolean enabled = Boolean.TRUE.equals(update.enabled());
        if (enabled && (!StringUtils.hasText(repository) || !StringUtils.hasText(baseUrl) || !StringUtils.hasText(update.oauthClientId()) || !StringUtils.hasText(update.oauthClientSecret()))) throw BizException.badRequest("github_star_config_incomplete", "Repository, public base URL, GitHub Client ID and Client Secret are required when enabled");
        GithubStarSetting row = settingMapper.selectById(1L); if (row == null) { row = new GithubStarSetting(); row.setId(1L); }
        row.setEnabled(enabled ? 1 : 0); row.setRepository(repository); row.setPublicBaseUrl(baseUrl); row.setOauthClientId(blankToNull(update.oauthClientId())); row.setDailyCheckTime(validTime(update.dailyCheckTime()));
        if (StringUtils.hasText(update.oauthClientSecret()) && !"<KEEP_EXISTING>".equals(update.oauthClientSecret())) row.setOauthClientSecret(update.oauthClientSecret().trim());
        if (row.getId() == 1L && settingMapper.selectById(1L) != null) settingMapper.updateById(row); else settingMapper.insert(row);
        return settings();
    }
    public String authorizationUrl(Long accountId) {
        Setting setting = settings(); requireOAuth(setting);
        String state = state(accountId, Instant.now().getEpochSecond());
        return "https://github.com/login/oauth/authorize?client_id=" + enc(setting.clientId()) + "&redirect_uri=" + enc(callbackUrl(setting)) + "&scope=" + enc("read:user") + "&state=" + enc(state);
    }
    public void bindFromCallback(String code, String state) {
        State parsed = verifyState(state); Setting setting = settings(); requireOAuth(setting);
        OAuthToken token = requestToken(setting, code); String login = requestLogin(token.accessToken());
        AccountGithubBinding binding = new AccountGithubBinding();
        binding.setAccountId(parsed.accountId()); binding.setGithubLogin(login); binding.setAccessToken(token.accessToken());
        binding.setRefreshToken(token.refreshToken()); binding.setAccessTokenExpiresAt(token.accessTokenExpiresAt());
        binding.setRefreshTokenExpiresAt(token.refreshTokenExpiresAt()); binding.setStarVerifiedAt(null);
        if (bindingMapper.selectById(parsed.accountId()) == null) bindingMapper.insert(binding);
        else bindingMapper.update(null, new LambdaUpdateWrapper<AccountGithubBinding>()
                .eq(AccountGithubBinding::getAccountId, parsed.accountId())
                .set(AccountGithubBinding::getGithubLogin, login)
                .set(AccountGithubBinding::getAccessToken, token.accessToken())
                .set(AccountGithubBinding::getRefreshToken, token.refreshToken())
                .set(AccountGithubBinding::getAccessTokenExpiresAt, token.accessTokenExpiresAt())
                .set(AccountGithubBinding::getRefreshTokenExpiresAt, token.refreshTokenExpiresAt())
                .set(AccountGithubBinding::getStarVerifiedAt, null));
    }
    public Binding binding(Long accountId) {
        AccountGithubBinding b = bindingMapper.selectById(accountId);
        return b == null ? new Binding(false, null, null, false, null, null)
                : new Binding(true, b.getGithubLogin(), b.getStarVerifiedAt(),
                        StringUtils.hasText(b.getRefreshToken()) && (b.getRefreshTokenExpiresAt() == null || b.getRefreshTokenExpiresAt().isAfter(LocalDateTime.now())),
                        b.getAccessTokenExpiresAt(), b.getRefreshTokenExpiresAt());
    }
    public void unbind(Long accountId) { bindingMapper.deleteById(accountId); }
    /** Network check used only while creating a new USER API key, never in /v1 request processing. */
    public void verifyForKeyCreation(Long accountId) {
        Setting setting = settings(); if (!setting.enabled() || accountId == null) return;
        AccountGithubBinding binding = bindingMapper.selectById(accountId);
        String repositoryUrl = "https://github.com/" + setting.repository();
        if (binding == null) throw BizException.forbidden("github_binding_required", "创建失败，请先绑定 GitHub 账号并为 " + repositoryUrl + " 点击 Star 后再创建或启用 API Key");
        StarStatus status = checkStar(binding, setting);
        if (status == StarStatus.STARRED) { binding.setStarVerifiedAt(LocalDateTime.now()); bindingMapper.updateById(binding); return; }
        if (status == StarStatus.TOKEN_INVALID) throw BizException.forbidden("github_authorization_invalid", "GitHub 授权无效或已被撤销，请重新绑定 GitHub 账号");
        if (status == StarStatus.UNAVAILABLE || status == StarStatus.RATE_LIMITED) throw BizException.forbidden("github_star_check_failed", "Unable to verify GitHub Star status");
        throw BizException.forbidden("github_star_required", "创建失败，请先为 " + repositoryUrl + " 点击 Star 后再创建或启用 API Key");
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public synchronized void runDailyCheckWhenDue() {
        Setting setting = settings();
        if (!setting.enabled()) return;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime dueAt = now.toLocalDate().atTime(java.time.LocalTime.parse(setting.dailyCheckTime()));
        if (now.isBefore(dueAt) || now.toLocalDate().equals(lastDailyCheck)) return;
        CheckResult result = verifyAllUsers(setting);
        lastDailyCheck = now.toLocalDate();
        logCheck("scheduled", result);
    }

    public synchronized CheckResult runCheckNow() {
        Setting setting = settings();
        if (!setting.enabled()) throw BizException.badRequest("github_star_gate_disabled", "GitHub Star gate is disabled");
        CheckResult result = verifyAllUsers(setting);
        logCheck("manual", result);
        return result;
    }

    private void logCheck(String trigger, CheckResult result) {
        log.info("[github-star] {} check completed: checkedUsers={}, verifiedBindings={}, disabledKeys={}",
                trigger, result.checkedUsers(), result.verifiedBindings(), result.disabledKeys());
        result.unverified().forEach(issue -> log.warn("[github-star] user='{}' github='{}' not verified: {}",
                issue.username(), issue.githubLogin(), issue.reason()));
    }

    private CheckResult verifyAllUsers(Setting setting) {
        boolean changed = false;
        int checkedUsers = 0;
        int verifiedBindings = 0;
        int disabledKeys = 0;
        List<CheckIssue> unverified = new ArrayList<>();
        for (Account account : accountMapper.selectList(new LambdaQueryWrapper<Account>().eq(Account::getRole, "USER").eq(Account::getStatus, 1))) {
            checkedUsers++;
            AccountGithubBinding binding = bindingMapper.selectById(account.getId());
            if (binding == null) {
                unverified.add(new CheckIssue(account.getUsername(), null, StarStatus.NOT_BOUND.reason));
                int disabled = disableEnabledKeys(account.getId());
                if (disabled > 0) { changed = true; disabledKeys += disabled; }
                continue;
            }
            StarStatus status = checkStar(binding, setting);
            if (status == StarStatus.STARRED) {
                binding.setStarVerifiedAt(LocalDateTime.now());
                bindingMapper.updateById(binding);
                verifiedBindings++;
                continue;
            }
            unverified.add(new CheckIssue(account.getUsername(), binding.getGithubLogin(), status.reason));
            if (!status.shouldDisableKeys) continue;
            int disabled = disableEnabledKeys(account.getId());
            if (disabled > 0) { changed = true; disabledKeys += disabled; }
        }
        if (changed) routeCacheService.refresh();
        return new CheckResult(checkedUsers, verifiedBindings, disabledKeys, List.copyOf(unverified));
    }

    private int disableEnabledKeys(Long accountId) {
        int disabled = 0;
        for (Token token : tokenMapper.selectList(new LambdaQueryWrapper<Token>().eq(Token::getAccountId, accountId).eq(Token::getStatus, 1))) {
            token.setStatus(0);
            tokenMapper.updateById(token);
            disabled++;
        }
        return disabled;
    }

    /** Checks Star status and renews an expiring OAuth access token before it becomes unusable. */
    private StarStatus checkStar(AccountGithubBinding binding, Setting setting) {
        if (binding == null || !StringUtils.hasText(binding.getAccessToken()) || !StringUtils.hasText(setting.repository())) return StarStatus.UNAVAILABLE;
        try {
            renewAccessTokenIfNeeded(binding, setting, false);
            HttpResponse<String> response = http.send(api("/user/starred/" + setting.repository(), binding.getAccessToken()), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 && renewAccessTokenIfNeeded(binding, setting, true)) {
                response = http.send(api("/user/starred/" + setting.repository(), binding.getAccessToken()), HttpResponse.BodyHandlers.ofString());
            }
            return switch (response.statusCode()) {
                case 204 -> StarStatus.STARRED;
                case 404 -> StarStatus.NOT_STARRED;
                case 401 -> StarStatus.TOKEN_INVALID;
                case 403 -> StarStatus.RATE_LIMITED;
                default -> StarStatus.UNAVAILABLE;
            };
        } catch (Exception e) {
            log.warn("[github-star] github star check failed for repository '{}': {}", setting.repository(), e.toString());
            return StarStatus.UNAVAILABLE;
        }
    }

    /** Refreshes shortly before expiration, or forces one retry after GitHub returns 401. */
    private synchronized boolean renewAccessTokenIfNeeded(AccountGithubBinding binding, Setting setting, boolean force) {
        if (!StringUtils.hasText(binding.getRefreshToken())) return false;
        if (binding.getRefreshTokenExpiresAt() != null && !binding.getRefreshTokenExpiresAt().isAfter(LocalDateTime.now())) return false;
        LocalDateTime expiresAt = binding.getAccessTokenExpiresAt();
        if (!force && (expiresAt == null || expiresAt.isAfter(LocalDateTime.now().plusMinutes(5)))) return false;
        try {
            OAuthToken refreshed = requestToken(setting, "refresh_token", binding.getRefreshToken());
            binding.setAccessToken(refreshed.accessToken());
            if (StringUtils.hasText(refreshed.refreshToken())) binding.setRefreshToken(refreshed.refreshToken());
            binding.setAccessTokenExpiresAt(refreshed.accessTokenExpiresAt());
            if (refreshed.refreshTokenExpiresAt() != null) binding.setRefreshTokenExpiresAt(refreshed.refreshTokenExpiresAt());
            bindingMapper.updateById(binding);
            log.info("[github-star] refreshed GitHub authorization for @{}", binding.getGithubLogin());
            return true;
        } catch (BizException e) {
            log.warn("[github-star] unable to refresh GitHub authorization for @{}: {}", binding.getGithubLogin(), e.getMessage());
            return false;
        }
    }

    private OAuthToken requestToken(Setting setting, String code) {
        return requestToken(setting, "authorization_code", code);
    }

    private OAuthToken requestToken(Setting setting, String grantType, String credential) {
        try {
            String body = "client_id=" + enc(setting.clientId()) + "&client_secret=" + enc(setting.clientSecret())
                    + ("refresh_token".equals(grantType)
                    ? "&grant_type=refresh_token&refresh_token=" + enc(credential)
                    : "&code=" + enc(credential) + "&redirect_uri=" + enc(callbackUrl(setting)));
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://github.com/login/oauth/access_token"))
                    .header("Accept", "application/json").header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            JsonNode json = objectMapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            if (!json.hasNonNull("access_token")) throw BizException.badRequest("github_oauth_failed", "GitHub OAuth authorization failed");
            return new OAuthToken(json.get("access_token").asText(), text(json, "refresh_token"),
                    expiry(json, "expires_in"), expiry(json, "refresh_token_expires_in"));
        } catch (BizException e) { throw e; } catch (Exception e) { throw BizException.badRequest("github_oauth_failed", "Unable to complete GitHub authorization"); }
    }

    private LocalDateTime expiry(JsonNode json, String field) {
        long seconds = json.path(field).asLong(0);
        return seconds > 0 ? LocalDateTime.now().plusSeconds(seconds) : null;
    }

    private String text(JsonNode json, String field) { return json.hasNonNull(field) ? json.get(field).asText() : null; }
    private String requestLogin(String token) { try { HttpRequest req = api("/user", token); JsonNode json = objectMapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body()); if (!json.hasNonNull("login")) throw BizException.badRequest("github_oauth_failed", "Unable to read GitHub account"); return json.get("login").asText(); } catch (BizException e) { throw e; } catch (Exception e) { throw BizException.badRequest("github_oauth_failed", "Unable to read GitHub account"); } }
    private HttpRequest api(String path, String token) { return HttpRequest.newBuilder(URI.create(GITHUB_API + path)).header("Accept", "application/vnd.github+json").header("Authorization", "Bearer " + token).header("X-GitHub-Api-Version", "2022-11-28").GET().build(); }
    private String state(Long id, long time) { try { String payload = id + ":" + time; return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + sign(payload); } catch (Exception e) { throw new IllegalStateException("Unable to create GitHub authorization state", e); } }
    private State verifyState(String state) { try { String[] p=state.split("\\.",2); String payload=new String(Base64.getUrlDecoder().decode(p[0]),StandardCharsets.UTF_8); if(p.length!=2||!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8),p[1].getBytes(StandardCharsets.UTF_8))) throw new Exception(); String[] v=payload.split(":",2); if(Instant.now().getEpochSecond()-Long.parseLong(v[1])>600) throw new Exception(); return new State(Long.parseLong(v[0])); } catch(Exception e){ throw BizException.badRequest("invalid_github_state","GitHub authorization state is invalid or expired"); } }
    private String sign(String value) throws Exception { Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(properties.getConsole().getSessionSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
    private void requireOAuth(Setting s) { if(!StringUtils.hasText(s.clientId())||!StringUtils.hasText(s.clientSecret())||!StringUtils.hasText(s.publicBaseUrl())) throw BizException.badRequest("github_oauth_not_configured","GitHub OAuth is not configured"); }
    private String callbackUrl(Setting s) { return s.publicBaseUrl().replaceAll("/+$","")+"/api/console/github/callback"; }
    private String validTime(String value) { try { return java.time.LocalTime.parse(StringUtils.hasText(value) ? value.trim() : "06:00").toString().substring(0, 5); } catch (Exception e) { throw BizException.badRequest("invalid_daily_check_time", "Daily check time must use HH:mm"); } }
    private String cleanRepository(String value) { if(!StringUtils.hasText(value)) return null; String v=value.trim().replaceFirst("^https?://github\\.com/","").replaceAll("/$",""); if(!v.matches("[^/\\s]+/[^/\\s]+")) throw BizException.badRequest("invalid_github_repository","Repository must be owner/repository"); return v; }
    private String cleanBaseUrl(String value) { if(!StringUtils.hasText(value)) return null; try { URI u=URI.create(value.trim().replaceAll("/+$","")); if(!"http".equalsIgnoreCase(u.getScheme())&&!"https".equalsIgnoreCase(u.getScheme())) throw new Exception(); return u.toString(); } catch(Exception e){throw BizException.badRequest("invalid_public_base_url","Public base URL must use HTTP or HTTPS");} }
    private String blankToNull(String s){return StringUtils.hasText(s)?s.trim():null;} private String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    public record Update(Boolean enabled,String repository,String publicBaseUrl,String oauthClientId,String oauthClientSecret,String dailyCheckTime){} public record Setting(boolean enabled,String repository,String publicBaseUrl,String clientId,String clientSecret,String dailyCheckTime){static Setting defaults(){return new Setting(false,null,null,null,null,"06:00");}} public record Binding(boolean bound,String githubLogin,LocalDateTime starVerifiedAt,boolean refreshable,LocalDateTime accessTokenExpiresAt,LocalDateTime refreshTokenExpiresAt){} public record CheckResult(int checkedUsers, int verifiedBindings, int disabledKeys, List<CheckIssue> unverified){} public record CheckIssue(String username, String githubLogin, String reason){} private record OAuthToken(String accessToken,String refreshToken,LocalDateTime accessTokenExpiresAt,LocalDateTime refreshTokenExpiresAt){} private record State(Long accountId){}

    private enum StarStatus {
        STARRED(null, false), NOT_BOUND("未绑定 GitHub", true), NOT_STARRED("未 Star 该仓库", true),
        TOKEN_INVALID("GitHub 授权已失效，需重新绑定", true), RATE_LIMITED("GitHub 接口限流(403)，本次未停用 Key", false),
        UNAVAILABLE("GitHub 校验失败或网络异常，本次未停用 Key", false);
        private final String reason;
        private final boolean shouldDisableKeys;
        StarStatus(String reason, boolean shouldDisableKeys) { this.reason = reason; this.shouldDisableKeys = shouldDisableKeys; }
    }
}
