package com.ainexus.gateway.service;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.config.GatewayProperties;
import com.ainexus.gateway.entity.AccountGithubBinding;
import com.ainexus.gateway.entity.GithubStarSetting;
import com.ainexus.gateway.entity.Token;
import com.ainexus.gateway.mapper.AccountGithubBindingMapper;
import com.ainexus.gateway.mapper.GithubStarSettingMapper;
import com.ainexus.gateway.mapper.TokenMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.util.Base64;

/** GitHub OAuth binding and repository-star access enforcement for USER API keys. */
@Service
@RequiredArgsConstructor
public class GithubStarGateService {
    private static final String GITHUB_API = "https://api.github.com";
    private final GithubStarSettingMapper settingMapper;
    private final AccountGithubBindingMapper bindingMapper;
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
        String token = requestToken(setting, code); String login = requestLogin(token);
        AccountGithubBinding binding = new AccountGithubBinding(); binding.setAccountId(parsed.accountId()); binding.setGithubLogin(login); binding.setAccessToken(token); binding.setStarVerifiedAt(null);
        if (bindingMapper.selectById(parsed.accountId()) == null) bindingMapper.insert(binding); else bindingMapper.updateById(binding);
    }
    public Binding binding(Long accountId) { AccountGithubBinding b = bindingMapper.selectById(accountId); return b == null ? new Binding(false, null, null) : new Binding(true, b.getGithubLogin(), b.getStarVerifiedAt()); }
    public void unbind(Long accountId) { bindingMapper.deleteById(accountId); }
    /** Network check used only while creating a new USER API key, never in /v1 request processing. */
    public void verifyForKeyCreation(Long accountId) {
        Setting setting = settings(); if (!setting.enabled() || accountId == null) return;
        AccountGithubBinding binding = bindingMapper.selectById(accountId);
        String repositoryUrl = "https://github.com/" + setting.repository();
        if (binding == null) throw BizException.forbidden("github_binding_required", "创建失败，请先绑定 GitHub 账号并为 " + repositoryUrl + " 点击 Star 后再创建或启用 API Key");
        if (!hasStar(binding.getAccessToken(), setting.repository())) throw BizException.forbidden("github_star_required", "创建失败，请先为 " + repositoryUrl + " 点击 Star 后再创建或启用 API Key");
        binding.setStarVerifiedAt(LocalDateTime.now()); bindingMapper.updateById(binding);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public synchronized void runDailyCheckWhenDue() {
        Setting setting = settings();
        if (!setting.enabled()) return;
        LocalDateTime now = LocalDateTime.now();
        if (!now.toLocalTime().withSecond(0).withNano(0).equals(java.time.LocalTime.parse(setting.dailyCheckTime())) || now.toLocalDate().equals(lastDailyCheck)) return;
        verifyAllUsers(setting);
        lastDailyCheck = now.toLocalDate();
    }

    private void verifyAllUsers(Setting setting) {
        boolean changed = false;
        for (AccountGithubBinding binding : bindingMapper.selectList(new LambdaQueryWrapper<>())) {
            boolean starred;
            try { starred = hasStar(binding.getAccessToken(), setting.repository()); } catch (BizException ignored) { starred = false; }
            if (starred) { binding.setStarVerifiedAt(LocalDateTime.now()); bindingMapper.updateById(binding); continue; }
            for (Token token : tokenMapper.selectList(new LambdaQueryWrapper<Token>().eq(Token::getAccountId, binding.getAccountId()).eq(Token::getStatus, 1))) { token.setStatus(0); tokenMapper.updateById(token); changed = true; }
        }
        if (changed) routeCacheService.refresh();
    }
    private String requestToken(Setting s, String code) { try { HttpRequest req = HttpRequest.newBuilder(URI.create("https://github.com/login/oauth/access_token")).header("Accept", "application/json").header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("client_id="+enc(s.clientId())+"&client_secret="+enc(s.clientSecret())+"&code="+enc(code)+"&redirect_uri="+enc(callbackUrl(s)))).build(); JsonNode json = objectMapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body()); if (!json.hasNonNull("access_token")) throw BizException.badRequest("github_oauth_failed", "GitHub OAuth authorization failed"); return json.get("access_token").asText(); } catch (BizException e) { throw e; } catch (Exception e) { throw BizException.badRequest("github_oauth_failed", "Unable to complete GitHub authorization"); } }
    private String requestLogin(String token) { try { HttpRequest req = api("/user", token); JsonNode json = objectMapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body()); if (!json.hasNonNull("login")) throw BizException.badRequest("github_oauth_failed", "Unable to read GitHub account"); return json.get("login").asText(); } catch (BizException e) { throw e; } catch (Exception e) { throw BizException.badRequest("github_oauth_failed", "Unable to read GitHub account"); } }
    private boolean hasStar(String token, String repository) { try { HttpResponse<String> response = http.send(api("/user/starred/" + repository, token), HttpResponse.BodyHandlers.ofString()); return response.statusCode() == 204; } catch (Exception e) { throw BizException.forbidden("github_star_check_failed", "Unable to verify GitHub Star status"); } }
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
    public record Update(Boolean enabled,String repository,String publicBaseUrl,String oauthClientId,String oauthClientSecret,String dailyCheckTime){} public record Setting(boolean enabled,String repository,String publicBaseUrl,String clientId,String clientSecret,String dailyCheckTime){static Setting defaults(){return new Setting(false,null,null,null,null,"06:00");}} public record Binding(boolean bound,String githubLogin,LocalDateTime starVerifiedAt){} private record State(Long accountId){}
}
