package com.ainexus.gateway.service;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.EpaySetting;
import com.ainexus.gateway.entity.SponsorshipOrder;
import com.ainexus.gateway.mapper.EpaySettingMapper;
import com.ainexus.gateway.mapper.SponsorshipOrderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EpaySponsorshipService {
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000.00");
    private final EpaySettingMapper settingMapper;
    private final SponsorshipOrderMapper orderMapper;

    public AdminSettingView settings() { return toView(setting()); }

    public synchronized AdminSettingView updateSettings(UpdateSetting request) {
        EpaySetting current = setting();
        EpaySetting next = new EpaySetting();
        next.setId(1L);
        next.setEnabled(Boolean.TRUE.equals(request.enabled()) ? 1 : 0);
        next.setGatewayUrl(trim(request.gatewayUrl()));
        next.setMerchantId(trim(request.merchantId()));
        next.setMerchantKey(resolveSecret(request.merchantKey(), current.getMerchantKey()));
        next.setNotifyUrl(trim(request.notifyUrl()));
        next.setReturnUrl(trim(request.returnUrl()));
        next.setDefaultPaymentType(defaultValue(trim(request.defaultPaymentType()), "alipay"));
        next.setSignType("MD5");
        validate(next, Boolean.TRUE.equals(request.enabled()));
        settingMapper.updateById(next);
        return toView(next);
    }

    public PaymentPage createPayment(Long accountId, String username, BigDecimal requestedAmount, String requestedType) {
        EpaySetting setting = setting();
        ensureReady(setting);
        BigDecimal amount = normalizeAmount(requestedAmount);
        String paymentType = defaultValue(trim(requestedType), setting.getDefaultPaymentType());
        SponsorshipOrder order = new SponsorshipOrder();
        order.setOutTradeNo("SP" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        order.setAccountId(accountId);
        order.setSponsorName(username);
        order.setAmount(amount);
        order.setCurrency("CNY");
        order.setSubject("AiNexus voluntary sponsorship");
        order.setPaymentType(paymentType);
        order.setStatus("PENDING");
        orderMapper.insert(order);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", setting.getMerchantId());
        params.put("type", paymentType);
        params.put("out_trade_no", order.getOutTradeNo());
        params.put("notify_url", setting.getNotifyUrl());
        params.put("return_url", setting.getReturnUrl());
        params.put("name", order.getSubject());
        params.put("money", amount.toPlainString());
        params.put("sign_type", "MD5");
        params.put("sign", sign(params, setting.getMerchantKey()));
        return new PaymentPage(order.getOutTradeNo(), UriComponentsBuilder.fromHttpUrl(setting.getGatewayUrl()).queryParams(toMultiValueMap(params)).build().encode(StandardCharsets.UTF_8).toUriString());
    }

    public boolean handleNotification(Map<String, String> params) {
        EpaySetting setting = setting();
        if (!isReady(setting) || !setting.getMerchantId().equals(params.get("pid"))) return false;
        try {
            if (!"MD5".equalsIgnoreCase(params.get("sign_type")) || !MessageDigest.isEqual(sign(params, setting.getMerchantKey()).getBytes(StandardCharsets.UTF_8), params.getOrDefault("sign", "").getBytes(StandardCharsets.UTF_8))) return false;
            if (!"TRADE_SUCCESS".equals(params.get("trade_status"))) return false;
            SponsorshipOrder order = orderMapper.selectOne(new LambdaQueryWrapper<SponsorshipOrder>().eq(SponsorshipOrder::getOutTradeNo, params.get("out_trade_no")));
            if (order == null || !"CNY".equals(order.getCurrency()) || (!"PENDING".equals(order.getStatus()) && !"PAID".equals(order.getStatus()))) return false;
            BigDecimal paidAmount = new BigDecimal(params.get("money")).setScale(2, RoundingMode.HALF_UP);
            String tradeNo = trim(params.get("trade_no"));
            if (!StringUtils.hasText(tradeNo) || order.getAmount().compareTo(paidAmount) != 0 || !order.getPaymentType().equals(params.get("type"))) return false;
            if ("PAID".equals(order.getStatus())) return java.util.Objects.equals(order.getTradeNo(), tradeNo);
            int updated = orderMapper.update(null, new LambdaUpdateWrapper<SponsorshipOrder>().eq(SponsorshipOrder::getId, order.getId()).eq(SponsorshipOrder::getStatus, "PENDING")
                    .set(SponsorshipOrder::getStatus, "PAID").set(SponsorshipOrder::getTradeNo, tradeNo).set(SponsorshipOrder::getPaidAt, LocalDateTime.now()));
            return updated == 1;
        } catch (Exception ignored) { return false; }
    }

    public OrderView orderForAccount(Long accountId, String outTradeNo) {
        SponsorshipOrder order = orderMapper.selectOne(new LambdaQueryWrapper<SponsorshipOrder>().eq(SponsorshipOrder::getOutTradeNo, outTradeNo).eq(SponsorshipOrder::getAccountId, accountId));
        if (order == null) throw BizException.badRequest("sponsorship_order_not_found", "Sponsorship order was not found");
        return toOrderView(order);
    }
    public List<OrderView> adminOrders() { return orderMapper.selectList(new LambdaQueryWrapper<SponsorshipOrder>().orderByDesc(SponsorshipOrder::getId)).stream().map(this::toOrderView).toList(); }
    public List<RankingView> ranking() {
        Map<String, BigDecimal> totals = new java.util.HashMap<>();
        orderMapper.selectList(new LambdaQueryWrapper<SponsorshipOrder>().eq(SponsorshipOrder::getStatus, "PAID")).forEach(order ->
                totals.merge(order.getSponsorName(), order.getAmount(), BigDecimal::add));
        return totals.entrySet().stream().sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(20).map(entry -> new RankingView(entry.getKey(), entry.getValue())).toList();
    }

    private EpaySetting setting() { EpaySetting value = settingMapper.selectById(1L); return value == null ? new EpaySetting() : value; }
    private void ensureReady(EpaySetting setting) { if (!isReady(setting)) throw BizException.badRequest("epay_not_configured", "Epay sponsorship is not configured or enabled"); }
    private boolean isReady(EpaySetting s) { return s.getEnabled() != null && s.getEnabled() == 1 && StringUtils.hasText(s.getGatewayUrl()) && StringUtils.hasText(s.getMerchantId()) && StringUtils.hasText(s.getMerchantKey()) && StringUtils.hasText(s.getNotifyUrl()) && StringUtils.hasText(s.getReturnUrl()); }
    private void validate(EpaySetting s, boolean enabled) { if (enabled && !isReady(s)) throw BizException.badRequest("incomplete_epay_setting", "Gateway URL, merchant ID, merchant key, notify URL and return URL are required when Epay is enabled"); validHttpUrl(s.getGatewayUrl(), "gatewayUrl"); if (StringUtils.hasText(s.getNotifyUrl())) validHttpUrl(s.getNotifyUrl(), "notifyUrl"); if (StringUtils.hasText(s.getReturnUrl())) validHttpUrl(s.getReturnUrl(), "returnUrl"); }
    private void validHttpUrl(String value, String field) { if (!StringUtils.hasText(value)) return; try { java.net.URI uri = java.net.URI.create(value); if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException(); } catch (Exception e) { throw BizException.badRequest("invalid_epay_setting", field + " must be an HTTP or HTTPS URL"); } }
    private String sign(Map<String, String> params, String key) { return md5(params.entrySet().stream().filter(entry -> !"sign".equals(entry.getKey()) && !"sign_type".equals(entry.getKey()) && StringUtils.hasText(entry.getValue())).sorted(Map.Entry.comparingByKey()).map(entry -> entry.getKey() + "=" + entry.getValue()).reduce((left, right) -> left + "&" + right).orElse("") + key); }
    private String md5(String value) { try { byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(32); for (byte b : digest) result.append(String.format("%02x", b & 0xff)); return result.toString(); } catch (Exception e) { throw new IllegalStateException("MD5 unavailable", e); } }
    private org.springframework.util.MultiValueMap<String, String> toMultiValueMap(Map<String, String> values) { org.springframework.util.LinkedMultiValueMap<String, String> result = new org.springframework.util.LinkedMultiValueMap<>(); values.forEach(result::add); return result; }
    private BigDecimal normalizeAmount(BigDecimal amount) { if (amount == null) throw BizException.badRequest("invalid_sponsorship_amount", "Sponsorship amount is required"); BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP); if (value.compareTo(MIN_AMOUNT) < 0 || value.compareTo(MAX_AMOUNT) > 0) throw BizException.badRequest("invalid_sponsorship_amount", "Sponsorship amount must be between 0.01 and 100000.00 CNY"); return value; }
    private String trim(String value) { return value == null ? null : value.trim(); }
    private String defaultValue(String value, String fallback) { return StringUtils.hasText(value) ? value : fallback; }
    private String resolveSecret(String submitted, String current) { return "<KEEP_EXISTING>".equals(submitted) ? current : trim(submitted); }
    private AdminSettingView toView(EpaySetting s) { return new AdminSettingView(s.getEnabled() != null && s.getEnabled() == 1, s.getGatewayUrl(), s.getMerchantId(), maskSecret(s.getMerchantKey()), s.getNotifyUrl(), s.getReturnUrl(), s.getDefaultPaymentType(), s.getSignType()); }
    private String maskSecret(String value) { return StringUtils.hasText(value) ? "<KEEP_EXISTING>" : null; }
    private OrderView toOrderView(SponsorshipOrder o) { return new OrderView(o.getOutTradeNo(), o.getSponsorName(), o.getAmount(), o.getCurrency(), o.getPaymentType(), o.getStatus(), o.getTradeNo(), o.getPaidAt(), o.getCreateTime()); }

    public record UpdateSetting(Boolean enabled, String gatewayUrl, String merchantId, String merchantKey, String notifyUrl, String returnUrl, String defaultPaymentType) { }
    public record AdminSettingView(boolean enabled, String gatewayUrl, String merchantId, String merchantKey, String notifyUrl, String returnUrl, String defaultPaymentType, String signType) { }
    public record PaymentPage(String outTradeNo, String paymentUrl) { }
    public record RankingView(String sponsorName, BigDecimal amount) { }
    public record OrderView(String outTradeNo, String sponsorName, BigDecimal amount, String currency, String paymentType, String status, String tradeNo, LocalDateTime paidAt, LocalDateTime createTime) { }
}
