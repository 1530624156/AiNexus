package com.ainexus.gateway.service;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.AdSetting;
import com.ainexus.gateway.entity.AdSlot;
import com.ainexus.gateway.mapper.AdSettingMapper;
import com.ainexus.gateway.mapper.AdSlotMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 商业广告位: 全局招商联系方式 + 三个广告位（登录页左下角 / 用户端顶部右侧 / 用户端侧边栏底部）。
 * 状态 LEASING=招租中, ACTIVE=已投放, CLOSED=关闭；关闭的广告位不会出现在公开接口中，
 * 前端因此不会渲染卡片，也不会留下空白占位。
 */
@Service
@RequiredArgsConstructor
public class AdvertisementService {
    public static final String SLOT_LOGIN_ASIDE = "LOGIN_ASIDE";
    public static final String SLOT_USER_HERO = "USER_HERO";
    public static final String SLOT_USER_SIDEBAR = "USER_SIDEBAR";
    private static final List<String> SLOT_KEYS = List.of(SLOT_LOGIN_ASIDE, SLOT_USER_HERO, SLOT_USER_SIDEBAR);
    private static final Set<String> STATUSES = Set.of("LEASING", "ACTIVE", "CLOSED");
    private static final Set<String> CONTACT_TYPES = Set.of("WECHAT", "QQ", "EMAIL", "QR", "LINK");
    private static final String DEFAULT_STATUS = "LEASING";
    private static final String DEFAULT_CONTACT_TYPE = "WECHAT";
    private static final int MAX_TITLE_LENGTH = 128;
    private static final int MAX_SUBTITLE_LENGTH = 255;
    private static final int MAX_BUTTON_LENGTH = 32;
    private static final int MAX_URL_LENGTH = 512;

    private final AdSettingMapper settingMapper;
    private final AdSlotMapper slotMapper;

    /** Full configuration for the administrator console, always containing the three known slots. */
    public AdsView adminView() { return view(false); }

    /** Public payload used by the login page and the user workspace; closed slots are omitted. */
    public AdsView publicView() { return view(true); }

    public synchronized AdsView update(UpdateRequest request) {
        ContactView contact = validateContact(request == null ? null : request.contact());
        List<SlotView> slots = validateSlots(request == null ? null : request.slots());
        AdSetting setting = new AdSetting();
        setting.setId(1L);
        setting.setContactType(contact.type());
        setting.setContactValue(contact.value());
        if (settingMapper.selectById(1L) == null) {
            settingMapper.insert(setting);
        } else {
            // Explicit SET so that clearing a configured value really writes NULL (updateById skips null fields).
            settingMapper.update(null, new LambdaUpdateWrapper<AdSetting>()
                    .eq(AdSetting::getId, 1L)
                    .set(AdSetting::getContactType, contact.type())
                    .set(AdSetting::getContactValue, contact.value()));
        }
        for (SlotView slot : slots) {
            AdSlot row = new AdSlot();
            row.setSlotKey(slot.slotKey());
            row.setStatus(slot.status());
            row.setLogoUrl(slot.logoUrl());
            row.setTitle(slot.title());
            row.setSubtitle(slot.subtitle());
            row.setButtonText(slot.buttonText());
            row.setLinkUrl(slot.linkUrl());
            if (slotMapper.selectById(slot.slotKey()) == null) {
                slotMapper.insert(row);
            } else {
                slotMapper.update(null, new LambdaUpdateWrapper<AdSlot>()
                        .eq(AdSlot::getSlotKey, slot.slotKey())
                        .set(AdSlot::getStatus, slot.status())
                        .set(AdSlot::getLogoUrl, slot.logoUrl())
                        .set(AdSlot::getTitle, slot.title())
                        .set(AdSlot::getSubtitle, slot.subtitle())
                        .set(AdSlot::getButtonText, slot.buttonText())
                        .set(AdSlot::getLinkUrl, slot.linkUrl()));
            }
        }
        return adminView();
    }

    private AdsView view(boolean publicOnly) {
        AdSetting setting = settingMapper.selectById(1L);
        ContactView contact = setting == null
                ? new ContactView(DEFAULT_CONTACT_TYPE, null)
                : new ContactView(normalize(setting.getContactType(), DEFAULT_CONTACT_TYPE), trimToNull(setting.getContactValue()));
        Map<String, AdSlot> stored = new LinkedHashMap<>();
        slotMapper.selectList(null).forEach(slot -> stored.put(slot.getSlotKey(), slot));
        List<SlotView> slots = new ArrayList<>(SLOT_KEYS.size());
        for (String key : SLOT_KEYS) {
            AdSlot row = stored.get(key);
            SlotView view = row == null
                    ? new SlotView(key, DEFAULT_STATUS, null, null, null, null, null)
                    : new SlotView(key, normalize(row.getStatus(), DEFAULT_STATUS), row.getLogoUrl(), row.getTitle(),
                            row.getSubtitle(), row.getButtonText(), row.getLinkUrl());
            if (publicOnly && "CLOSED".equals(view.status())) continue;
            slots.add(view);
        }
        return new AdsView(contact, List.copyOf(slots));
    }

    private List<SlotView> validateSlots(List<SlotInput> requested) {
        Map<String, SlotInput> byKey = new LinkedHashMap<>();
        if (requested != null) {
            for (SlotInput input : requested) {
                if (input == null || !StringUtils.hasText(input.slotKey())) continue;
                String key = input.slotKey().trim().toUpperCase(Locale.ROOT);
                if (!SLOT_KEYS.contains(key)) {
                    throw BizException.badRequest("ad_unknown_slot", "未知的广告位: " + input.slotKey());
                }
                byKey.put(key, input);
            }
        }
        List<SlotView> slots = new ArrayList<>(SLOT_KEYS.size());
        for (String key : SLOT_KEYS) {
            SlotInput input = byKey.get(key);
            if (input == null) {
                slots.add(new SlotView(key, DEFAULT_STATUS, null, null, null, null, null));
                continue;
            }
            String status = normalize(input.status(), DEFAULT_STATUS);
            if (!STATUSES.contains(status)) {
                throw BizException.badRequest("ad_invalid_status", "未知的广告位状态: " + input.status());
            }
            String title = text(input.title(), "主标题", MAX_TITLE_LENGTH);
            String subtitle = text(input.subtitle(), "副标题", MAX_SUBTITLE_LENGTH);
            String buttonText = text(input.buttonText(), "按钮文字", MAX_BUTTON_LENGTH);
            String logoUrl = safeUrl(input.logoUrl(), "广告主 Logo");
            String linkUrl = safeUrl(input.linkUrl(), "跳转链接");
            if ("ACTIVE".equals(status) && title == null) {
                throw BizException.badRequest("ad_title_required", "已投放的广告位必须填写主标题");
            }
            slots.add(new SlotView(key, status, logoUrl, title, subtitle, buttonText, linkUrl));
        }
        return List.copyOf(slots);
    }

    private ContactView validateContact(ContactInput input) {
        String type = input == null ? null : normalize(input.type(), DEFAULT_CONTACT_TYPE);
        if (!CONTACT_TYPES.contains(type)) {
            throw BizException.badRequest("ad_invalid_contact_type", "未知的招商联系方式类型: " + (input == null ? null : input.type()));
        }
        String value = input == null ? null : trimToNull(input.value());
        if (value != null) {
            if (value.length() > MAX_URL_LENGTH) {
                throw BizException.badRequest("ad_invalid_contact", "招商联系方式不能超过 " + MAX_URL_LENGTH + " 个字符");
            }
            if (("LINK".equals(type) || "QR".equals(type)) && !isHttpUrl(value) && !isSitePath(value)) {
                throw BizException.badRequest("ad_invalid_contact",
                        "招商" + ("QR".equals(type) ? "二维码图片地址" : "联系链接") + "必须是 http:// 或 https:// 开头的地址，或以 / 开头的站内路径");
            }
            if ("EMAIL".equals(type) && !value.contains("@")) {
                throw BizException.badRequest("ad_invalid_contact", "招商邮箱格式不正确");
            }
        }
        return new ContactView(type, value);
    }

    private String text(String value, String label, int maxLength) {
        String trimmed = trimToNull(value);
        if (trimmed != null && trimmed.length() > maxLength) {
            throw BizException.badRequest("ad_text_too_long", label + "不能超过 " + maxLength + " 个字符");
        }
        return trimmed;
    }

    /** Allows only http(s) URLs or site-relative paths, so configured values can never become javascript: URIs. */
    private String safeUrl(String value, String label) {
        String trimmed = trimToNull(value);
        if (trimmed == null) return null;
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw BizException.badRequest("ad_invalid_url", label + "不能超过 " + MAX_URL_LENGTH + " 个字符");
        }
        if (isHttpUrl(trimmed) || isSitePath(trimmed)) return trimmed;
        throw BizException.badRequest("ad_invalid_url", label + "必须是 http:// 或 https:// 开头的地址，或以 / 开头的站内路径");
    }

    private boolean isHttpUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        try {
            return StringUtils.hasText(URI.create(value).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSitePath(String value) { return value.startsWith("/") && !value.startsWith("//"); }

    private String normalize(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : fallback;
    }

    private String trimToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }

    public record AdsView(ContactView contact, List<SlotView> slots) { }

    public record ContactView(String type, String value) { }

    public record SlotView(String slotKey, String status, String logoUrl, String title, String subtitle,
                           String buttonText, String linkUrl) { }

    public record UpdateRequest(ContactInput contact, List<SlotInput> slots) { }

    public record ContactInput(String type, String value) { }

    public record SlotInput(String slotKey, String status, String logoUrl, String title, String subtitle,
                            String buttonText, String linkUrl) { }
}