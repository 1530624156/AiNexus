package com.ainexus.gateway.admin;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.Account;
import com.ainexus.gateway.entity.ModelGroup;
import com.ainexus.gateway.entity.UserGroup;
import com.ainexus.gateway.entity.UserGroupMember;
import com.ainexus.gateway.entity.UserGroupModelGroup;
import com.ainexus.gateway.mapper.AccountMapper;
import com.ainexus.gateway.mapper.ModelGroupMapper;
import com.ainexus.gateway.mapper.UserGroupMapper;
import com.ainexus.gateway.mapper.UserGroupMemberMapper;
import com.ainexus.gateway.mapper.UserGroupModelGroupMapper;
import com.ainexus.gateway.service.UserGroupTokenQuotaService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.time.LocalTime;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** CRUD for group policy, member assignment and model-group authorization. */
@RestController
@RequestMapping("/api/admin/user-groups")
@RequiredArgsConstructor
public class AdminUserGroupController {
    private static final java.util.regex.Pattern ALLOW_TIME = java.util.regex.Pattern.compile("^([0-9]{1,2}):([0-5][0-9])$");
    private final UserGroupMapper userGroupMapper;
    private final UserGroupMemberMapper memberMapper;
    private final UserGroupModelGroupMapper modelGroupMapper;
    private final AccountMapper accountMapper;
    private final ModelGroupMapper groupMapper;
    private final UserGroupTokenQuotaService tokenQuotaService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public List<UserGroupView> list() { return userGroupMapper.selectList(new LambdaQueryWrapper<UserGroup>().orderByAsc(UserGroup::getId)).stream().map(this::view).toList(); }

    @GetMapping("/available-members")
    public List<OptionView> availableMembers() {
        return accountMapper.selectList(new LambdaQueryWrapper<Account>().eq(Account::getRole, "USER").eq(Account::getStatus, 1).orderByAsc(Account::getUsername))
                .stream().map(account -> new OptionView(account.getId(), account.getUsername())).toList();
    }

    @GetMapping("/available-model-groups")
    public List<OptionView> availableModelGroups() {
        return groupMapper.selectList(new LambdaQueryWrapper<ModelGroup>().orderByAsc(ModelGroup::getName))
                .stream().map(group -> new OptionView(group.getId(), group.getName())).toList();
    }

    @PostMapping
    public UserGroupView create(@Valid @RequestBody UserGroupRequest request) {
        UserGroup group = toEntity(request);
        try { userGroupMapper.insert(group); } catch (DuplicateKeyException e) { throw BizException.badRequest("user_group_name_exists", "User group name already exists"); }
        replaceRelations(group.getId(), request.memberIds(), request.modelGroupIds());
        return view(required(group.getId()));
    }

    @PutMapping("/{id}")
    public UserGroupView update(@PathVariable Long id, @Valid @RequestBody UserGroupRequest request) {
        required(id);
        UserGroup group = toEntity(request); group.setId(id);
        try { userGroupMapper.updateById(group); } catch (DuplicateKeyException e) { throw BizException.badRequest("user_group_name_exists", "User group name already exists"); }
        replaceRelations(id, request.memberIds(), request.modelGroupIds());
        return view(required(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        required(id); memberMapper.deleteByGroupId(id); modelGroupMapper.deleteByGroupId(id); userGroupMapper.deleteById(id);
    }

    private UserGroup toEntity(UserGroupRequest request) {
        UserGroup group = new UserGroup();
        group.setName(request.name().trim());
        group.setMaxConcurrent(nonNegative(request.maxConcurrent(), "maxConcurrent"));
        group.setApiKeyMaxConcurrent(nonNegative(request.apiKeyMaxConcurrent(), "apiKeyMaxConcurrent"));
        group.setTokenQuota(nonNegativeLong(request.tokenQuota(), "tokenQuota"));
        group.setQuotaPeriod(period(request.quotaPeriod()));
        group.setQuotaSchedule(quotaSchedule(request.quotaSchedule(), group.getQuotaPeriod()));
        group.setMemberTokenQuota(nonNegativeLong(request.memberTokenQuota(), "memberTokenQuota"));
        group.setMemberMaxConcurrent(nonNegative(request.memberMaxConcurrent(), "memberMaxConcurrent"));
        String allowTimeStart = allowTime(request.allowTimeStart(), "allowTimeStart");
        String allowTimeEnd = allowTime(request.allowTimeEnd(), "allowTimeEnd");
        if ((allowTimeStart == null) != (allowTimeEnd == null)) {
            throw BizException.badRequest("invalid_allow_time", "allowTimeStart and allowTimeEnd must both be set or both be empty");
        }
        group.setAllowTimeStart(allowTimeStart);
        group.setAllowTimeEnd(allowTimeEnd);
        group.setRemark(request.remark());
        return group;
    }

    private void replaceRelations(Long groupId, List<Long> requestedMembers, List<Long> requestedModelGroups) {
        Set<Long> memberIds = unique(requestedMembers); Set<Long> modelGroupIds = unique(requestedModelGroups);
        validateMembers(memberIds); validateModelGroups(modelGroupIds);
        memberMapper.deleteByGroupId(groupId); modelGroupMapper.deleteByGroupId(groupId);
        memberIds.forEach(id -> { UserGroupMember member = new UserGroupMember(); member.setUserGroupId(groupId); member.setAccountId(id); memberMapper.insert(member); });
        modelGroupIds.forEach(id -> { UserGroupModelGroup binding = new UserGroupModelGroup(); binding.setUserGroupId(groupId); binding.setModelGroupId(id); modelGroupMapper.insert(binding); });
    }

    private void validateMembers(Set<Long> ids) {
        if (ids.isEmpty()) return;
        long count = accountMapper.selectCount(new LambdaQueryWrapper<Account>().in(Account::getId, ids).eq(Account::getRole, "USER"));
        if (count != ids.size()) throw BizException.badRequest("invalid_user_group_members", "Members must be existing USER accounts");
    }
    private void validateModelGroups(Set<Long> ids) {
        if (ids.isEmpty()) return;
        long count = groupMapper.selectCount(new LambdaQueryWrapper<ModelGroup>().in(ModelGroup::getId, ids));
        if (count != ids.size()) throw BizException.badRequest("invalid_model_group_authorization", "Authorized model groups must exist");
    }
    private Set<Long> unique(List<Long> ids) { return ids == null ? Set.of() : new LinkedHashSet<>(ids.stream().filter(id -> id != null && id > 0).toList()); }
    private int nonNegative(Integer value, String field) { int result = value == null ? 0 : value; if (result < 0) throw BizException.badRequest("invalid_quota", field + " must be zero or positive"); return result; }
    private long nonNegativeLong(Long value, String field) { long result = value == null ? 0L : value; if (result < 0) throw BizException.badRequest("invalid_quota", field + " must be zero or positive"); return result; }
    private String period(String value) { String normalized = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "MONTH"; if (!Set.of("DAY", "WEEK", "MONTH", "YEAR").contains(normalized)) throw BizException.badRequest("invalid_quota_period", "quotaPeriod must be DAY, WEEK, MONTH or YEAR"); return normalized; }
    private String quotaSchedule(List<QuotaSlotRequest> slots, String quotaPeriod) {
        if (slots == null || slots.isEmpty()) return null;
        if (!"DAY".equals(quotaPeriod)) throw BizException.badRequest("invalid_quota_schedule", "Time-based quota increments require a DAY quota period");
        Set<String> times = new java.util.HashSet<>();
        List<QuotaSlotRequest> normalized = slots.stream().map(slot -> {
            if (slot == null || slot.time() == null || !ALLOW_TIME.matcher(slot.time().trim()).matches()) throw BizException.badRequest("invalid_quota_schedule", "Each quota slot needs a valid HH:mm time");
            String time = allowTime(slot.time(), "quotaSchedule.time");
            if (!times.add(time)) throw BizException.badRequest("invalid_quota_schedule", "Quota slot times must be unique");
            long amount = nonNegativeLong(slot.amount(), "quotaSchedule.amount");
            return new QuotaSlotRequest(time, amount);
        }).sorted(java.util.Comparator.comparing(slot -> LocalTime.parse(slot.time()))).toList();
        try { return objectMapper.writeValueAsString(normalized); }
        catch (Exception e) { throw BizException.badRequest("invalid_quota_schedule", "Unable to serialize quota schedule"); }
    }
    /** Normalizes an allowed-call-window boundary to {@code HH:mm}; a blank value means "no window". */
    private String allowTime(String value, String field) {
        if (!StringUtils.hasText(value)) return null;
        java.util.regex.Matcher matcher = ALLOW_TIME.matcher(value.trim());
        if (!matcher.matches() || Integer.parseInt(matcher.group(1)) > 23) {
            throw BizException.badRequest("invalid_allow_time", field + " must be HH:mm between 00:00 and 23:59");
        }
        return String.format("%02d:%02d", Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }
    private UserGroup required(Long id) { UserGroup group = userGroupMapper.selectById(id); if (group == null) throw BizException.badRequest("user_group_not_found", "User group not found: " + id); return group; }

    private UserGroupView view(UserGroup group) {
        List<Long> memberIds = memberMapper.selectList(new LambdaQueryWrapper<UserGroupMember>().eq(UserGroupMember::getUserGroupId, group.getId())).stream().map(UserGroupMember::getAccountId).toList();
        List<Long> modelGroupIds = modelGroupMapper.selectList(new LambdaQueryWrapper<UserGroupModelGroup>().eq(UserGroupModelGroup::getUserGroupId, group.getId())).stream().map(UserGroupModelGroup::getModelGroupId).toList();
        Map<Long, String> members = memberIds.isEmpty() ? Map.of() : accountMapper.selectBatchIds(memberIds).stream().collect(java.util.stream.Collectors.toMap(Account::getId, Account::getUsername));
        Map<Long, String> modelGroups = modelGroupIds.isEmpty() ? Map.of() : groupMapper.selectBatchIds(modelGroupIds).stream().collect(java.util.stream.Collectors.toMap(ModelGroup::getId, ModelGroup::getName));
        long effectiveQuota = tokenQuotaService.effectiveGroupQuota(group, java.time.LocalDateTime.now());
        long usedPromptTokens = effectiveQuota == 0 ? 0L : tokenQuotaService.usedByGroup(group.getId(), tokenQuotaService.periodStart(group.getQuotaPeriod()));
        Long remainingTokenQuota = effectiveQuota == 0 ? null
                : Math.max(0L, effectiveQuota - usedPromptTokens);
        return new UserGroupView(group.getId(), group.getName(), group.getMaxConcurrent(), group.getApiKeyMaxConcurrent(), effectiveQuota, group.getQuotaPeriod(), parseQuotaSchedule(group.getQuotaSchedule()), group.getMemberTokenQuota(), group.getMemberMaxConcurrent(), group.getAllowTimeStart(), group.getAllowTimeEnd(), group.getRemark(), memberIds, memberIds.stream().map(members::get).filter(java.util.Objects::nonNull).toList(), modelGroupIds, modelGroupIds.stream().map(modelGroups::get).filter(java.util.Objects::nonNull).toList(), usedPromptTokens, remainingTokenQuota, group.getCreateTime(), group.getUpdateTime());
    }

    private List<QuotaSlotRequest> parseQuotaSchedule(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        try { return objectMapper.readValue(value, new TypeReference<List<QuotaSlotRequest>>() {}); }
        catch (Exception e) { return List.of(); }
    }
    public record QuotaSlotRequest(String time, Long amount) { }
    public record UserGroupRequest(@NotBlank String name, Integer maxConcurrent, Integer apiKeyMaxConcurrent, Long tokenQuota, String quotaPeriod, List<QuotaSlotRequest> quotaSchedule, Long memberTokenQuota, Integer memberMaxConcurrent, String allowTimeStart, String allowTimeEnd, String remark, List<Long> memberIds, List<Long> modelGroupIds) { }
    public record UserGroupView(Long id, String name, Integer maxConcurrent, Integer apiKeyMaxConcurrent, Long tokenQuota, String quotaPeriod, List<QuotaSlotRequest> quotaSchedule, Long memberTokenQuota, Integer memberMaxConcurrent, String allowTimeStart, String allowTimeEnd, String remark, List<Long> memberIds, List<String> memberNames, List<Long> modelGroupIds, List<String> modelGroupNames, Long usedPromptTokens, Long remainingTokenQuota, Object createTime, Object updateTime) { }
    public record OptionView(Long id, String name) { }
}
