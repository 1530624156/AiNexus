package com.ainexus.gateway.service;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.UserGroup;
import com.ainexus.gateway.entity.UserGroupMember;
import com.ainexus.gateway.mapper.UserGroupMapper;
import com.ainexus.gateway.mapper.UserGroupMemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;

/**
 * Enforces the per-user-group allowed call window before a gateway request is routed upstream.
 *
 * <p>Semantics:
 * <ul>
 *   <li>Both {@code allowTimeStart} and {@code allowTimeEnd} empty => the group is callable all day.</li>
 *   <li>{@code end <= start} => the window crosses midnight (e.g. 22:00-06:00 allows 22:00-23:59 and 00:00-05:59).</li>
 *   <li>{@code start == end} => the whole day is allowed (a zero-width window would otherwise be a trap).</li>
 *   <li>The boundaries are half-open: a call is allowed when {@code start <= now < end}.</li>
 *   <li>Multi-group membership is fail-closed: every group that defines a window must allow the call.
 *       A group without a window does not constrain its members.</li>
 *   <li>The clock is the gateway server's local time zone.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserGroupCallWindowService {
    private final UserGroupMemberMapper memberMapper;
    private final UserGroupMapper groupMapper;

    /** Throws 403 when the account belongs to a group whose allowed call window excludes the current time. */
    public void check(Long accountId) {
        if (accountId == null) return;
        LocalTime now = LocalTime.now();
        for (UserGroup group : groups(accountId)) {
            if (!allows(group, now)) {
                throw BizException.forbidden("user_group_call_time_restricted",
                        "User group '" + group.getName() + "' allows calls only between "
                                + group.getAllowTimeStart() + " and " + group.getAllowTimeEnd()
                                + " (server time zone); current time is " + now.withNano(0));
            }
        }
    }

    /** {@code true} when the group defines no window, or the given time falls inside it. */
    public boolean allows(UserGroup group, LocalTime now) {
        LocalTime start = parse(group.getAllowTimeStart(), group);
        LocalTime end = parse(group.getAllowTimeEnd(), group);
        if (start == null || end == null || start.equals(end)) return true;
        if (end.isAfter(start)) return !now.isBefore(start) && now.isBefore(end);
        // Overnight window: 22:00-06:00
        return !now.isBefore(start) || now.isBefore(end);
    }

    /**
     * Parses {@code HH:mm}. A blank value means "no window". Malformed values are treated as unrestricted so a bad
     * row can never lock an entire group out of the gateway; the admin API validates input before writing.
     */
    private LocalTime parse(String value, UserGroup group) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        int separator = trimmed.indexOf(':');
        if (separator < 1) {
            log.warn("[user-group] invalid allow_time value '{}' on group {}, ignoring time restriction", trimmed, group.getName());
            return null;
        }
        try {
            LocalTime parsed = LocalTime.of(Integer.parseInt(trimmed.substring(0, separator)),
                    Integer.parseInt(trimmed.substring(separator + 1)));
            return parsed.withSecond(0).withNano(0);
        } catch (RuntimeException e) {
            log.warn("[user-group] invalid allow_time value '{}' on group {}, ignoring time restriction", trimmed, group.getName());
            return null;
        }
    }

    private List<UserGroup> groups(Long accountId) {
        List<Long> ids = memberMapper.selectList(new LambdaQueryWrapper<UserGroupMember>()
                        .eq(UserGroupMember::getAccountId, accountId))
                .stream().map(UserGroupMember::getUserGroupId).toList();
        return ids.isEmpty() ? List.of() : groupMapper.selectBatchIds(ids);
    }
}
