package com.ainexus.gateway.service;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.internal.util.AlipaySignature;
import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.AlipaySetting;
import com.ainexus.gateway.entity.SponsorshipOrder;
import com.ainexus.gateway.mapper.AlipaySettingMapper;
import com.ainexus.gateway.mapper.SponsorshipOrderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AlipaySponsorshipService {
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000.00");
    private final AlipaySettingMapper settingMapper;
    private final SponsorshipOrderMapper orderMapper;

    public AdminSettingView settings() {
        AlipaySetting setting = setting();
        return toView(setting);
    }

    public synchronized AdminSettingView updateSettings(UpdateSetting request) {
        AlipaySetting current = setting();
        AlipaySetting next = new AlipaySetting();
        next.setId(1L);
        next.setEnabled(Boolean.TRUE.equals(request.enabled()) ? 1 : 0);
        next.setAppId(trim(request.appId()));
        next.setSellerId(trim(request.sellerId()));
        next.setPrivateKey(resolveSecret(request.privateKey(), current.getPrivateKey()));
        next.setAlipayPublicKey(resolveSecret(request.alipayPublicKey(), current.getAlipayPublicKey()));
        next.setGatewayUrl(defaultValue(trim(request.gatewayUrl()), "https://openapi.alipay.com/gateway.do"));
        next.setNotifyUrl(trim(request.notifyUrl()));
        next.setReturnUrl(trim(request.returnUrl()));
        next.setSignType(defaultValue(trim(request.signType()), "RSA2").toUpperCase());
        next.setCharset(defaultValue(trim(request.charset()), "UTF-8"));
        next.setFormat(defaultValue(trim(request.format()), "json"));
        validate(next, Boolean.TRUE.equals(request.enabled()));
        settingMapper.updateById(next);
        return toView(next);
    }

    public PaymentPage createPayment(Long accountId, String username, BigDecimal requestedAmount) {
        AlipaySetting setting = setting();
        ensureReady(setting);
        BigDecimal amount = normalizeAmount(requestedAmount);
        SponsorshipOrder order = new SponsorshipOrder();
        order.setOutTradeNo("SP" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now())
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        order.setAccountId(accountId);
        order.setSponsorName(username);
        order.setAmount(amount);
        order.setCurrency("CNY");
        order.setSubject("AiNexus voluntary sponsorship");
        order.setStatus("PENDING");
        orderMapper.insert(order);
        try {
            AlipayTradePagePayRequest payRequest = new AlipayTradePagePayRequest();
            payRequest.setNotifyUrl(setting.getNotifyUrl());
            payRequest.setReturnUrl(setting.getReturnUrl());
            AlipayTradePagePayModel model = new AlipayTradePagePayModel();
            model.setOutTradeNo(order.getOutTradeNo());
            model.setTotalAmount(amount.toPlainString());
            model.setSubject(order.getSubject());
            model.setProductCode("FAST_INSTANT_TRADE_PAY");
            payRequest.setBizModel(model);
            AlipayTradePagePayResponse response = client(setting).pageExecute(payRequest, "GET");
            if (!response.isSuccess() || !StringUtils.hasText(response.getBody())) {
                throw new IllegalStateException(response.getSubMsg());
            }
            return new PaymentPage(order.getOutTradeNo(), response.getBody());
        } catch (Exception e) {
            orderMapper.update(null, new LambdaUpdateWrapper<SponsorshipOrder>()
                    .eq(SponsorshipOrder::getId, order.getId()).set(SponsorshipOrder::getStatus, "CLOSED"));
            throw BizException.badRequest("alipay_payment_create_failed", "Unable to create Alipay payment order");
        }
    }

    public boolean handleNotification(Map<String, String> params) {
        AlipaySetting setting = setting();
        if (!isReady(setting)) return false;
        try {
            if (!AlipaySignature.rsaCheckV1(params, setting.getAlipayPublicKey(), setting.getCharset(), setting.getSignType())) return false;
            if (!setting.getAppId().equals(params.get("app_id")) || !setting.getSellerId().equals(params.get("seller_id")) || !"TRADE_SUCCESS".equals(params.get("trade_status"))) return false;
            SponsorshipOrder order = orderMapper.selectOne(new LambdaQueryWrapper<SponsorshipOrder>()
                    .eq(SponsorshipOrder::getOutTradeNo, params.get("out_trade_no")));
            if (order == null || !"CNY".equals(order.getCurrency()) || !"PENDING".equals(order.getStatus()) && !"PAID".equals(order.getStatus())) return false;
            BigDecimal paidAmount = new BigDecimal(params.get("total_amount")).setScale(2, RoundingMode.HALF_UP);
            if (order.getAmount().compareTo(paidAmount) != 0) return false;
            if ("PAID".equals(order.getStatus())) return order.getTradeNo().equals(params.get("trade_no"));
            int updated = orderMapper.update(null, new LambdaUpdateWrapper<SponsorshipOrder>()
                    .eq(SponsorshipOrder::getId, order.getId()).eq(SponsorshipOrder::getStatus, "PENDING")
                    .set(SponsorshipOrder::getStatus, "PAID").set(SponsorshipOrder::getTradeNo, params.get("trade_no"))
                    .set(SponsorshipOrder::getPaidAt, LocalDateTime.now()));
            return updated == 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    public OrderView orderForAccount(Long accountId, String outTradeNo) {
        SponsorshipOrder order = orderMapper.selectOne(new LambdaQueryWrapper<SponsorshipOrder>()
                .eq(SponsorshipOrder::getOutTradeNo, outTradeNo).eq(SponsorshipOrder::getAccountId, accountId));
        if (order == null) throw BizException.badRequest("sponsorship_order_not_found", "Sponsorship order was not found");
        return toOrderView(order);
    }

    public List<OrderView> adminOrders() {
        return orderMapper.selectList(new LambdaQueryWrapper<SponsorshipOrder>().orderByDesc(SponsorshipOrder::getId))
                .stream().map(this::toOrderView).toList();
    }

    private AlipayClient client(AlipaySetting setting) {
        return new DefaultAlipayClient(setting.getGatewayUrl(), setting.getAppId(), setting.getPrivateKey(), setting.getFormat(), setting.getCharset(), setting.getAlipayPublicKey(), setting.getSignType());
    }
    private AlipaySetting setting() { AlipaySetting value = settingMapper.selectById(1L); return value == null ? new AlipaySetting() : value; }
    private void ensureReady(AlipaySetting setting) { if (!isReady(setting)) throw BizException.badRequest("alipay_not_configured", "Alipay sponsorship is not configured or enabled"); }
    private boolean isReady(AlipaySetting s) { return s.getEnabled() != null && s.getEnabled() == 1 && StringUtils.hasText(s.getAppId()) && StringUtils.hasText(s.getSellerId()) && StringUtils.hasText(s.getPrivateKey()) && StringUtils.hasText(s.getAlipayPublicKey()) && StringUtils.hasText(s.getNotifyUrl()) && StringUtils.hasText(s.getReturnUrl()); }
    private void validate(AlipaySetting s, boolean enabled) {
        if (!"RSA2".equals(s.getSignType())) throw BizException.badRequest("invalid_alipay_sign_type", "Only RSA2 is supported");
        if (enabled && !isReady(s)) throw BizException.badRequest("incomplete_alipay_setting", "App ID, seller ID, keys, notify URL and return URL are required when Alipay is enabled");
        validHttpUrl(s.getGatewayUrl(), "gatewayUrl"); if (StringUtils.hasText(s.getNotifyUrl())) validHttpUrl(s.getNotifyUrl(), "notifyUrl"); if (StringUtils.hasText(s.getReturnUrl())) validHttpUrl(s.getReturnUrl(), "returnUrl");
    }
    private void validHttpUrl(String value, String field) { try { java.net.URI uri = java.net.URI.create(value); if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException(); } catch (Exception e) { throw BizException.badRequest("invalid_alipay_setting", field + " must be an HTTP or HTTPS URL"); } }
    private BigDecimal normalizeAmount(BigDecimal amount) { if (amount == null) throw BizException.badRequest("invalid_sponsorship_amount", "Sponsorship amount is required"); BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP); if (value.compareTo(MIN_AMOUNT) < 0 || value.compareTo(MAX_AMOUNT) > 0) throw BizException.badRequest("invalid_sponsorship_amount", "Sponsorship amount must be between 0.01 and 100000.00 CNY"); return value; }
    private String trim(String value) { return value == null ? null : value.trim(); }
    private String defaultValue(String value, String fallback) { return StringUtils.hasText(value) ? value : fallback; }
    private String resolveSecret(String submitted, String current) { return "<KEEP_EXISTING>".equals(submitted) ? current : trim(submitted); }
    private AdminSettingView toView(AlipaySetting s) { return new AdminSettingView(s.getEnabled() != null && s.getEnabled() == 1, s.getAppId(), s.getSellerId(), maskSecret(s.getPrivateKey()), maskSecret(s.getAlipayPublicKey()), s.getGatewayUrl(), s.getNotifyUrl(), s.getReturnUrl(), s.getSignType(), s.getCharset(), s.getFormat()); }
    private String maskSecret(String value) { return StringUtils.hasText(value) ? "<KEEP_EXISTING>" : null; }
    private OrderView toOrderView(SponsorshipOrder o) { return new OrderView(o.getOutTradeNo(), o.getSponsorName(), o.getAmount(), o.getCurrency(), o.getStatus(), o.getTradeNo(), o.getPaidAt(), o.getCreateTime()); }

    public record UpdateSetting(Boolean enabled, String appId, String sellerId, String privateKey, String alipayPublicKey, String gatewayUrl, String notifyUrl, String returnUrl, String signType, String charset, String format) { }
    public record AdminSettingView(boolean enabled, String appId, String sellerId, String privateKey, String alipayPublicKey, String gatewayUrl, String notifyUrl, String returnUrl, String signType, String charset, String format) { }
    public record PaymentPage(String outTradeNo, String paymentForm) { }
    public record OrderView(String outTradeNo, String sponsorName, BigDecimal amount, String currency, String status, String tradeNo, LocalDateTime paidAt, LocalDateTime createTime) { }
}
