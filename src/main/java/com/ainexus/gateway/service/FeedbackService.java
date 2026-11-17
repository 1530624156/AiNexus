package com.ainexus.gateway.service;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.UserFeedback;
import com.ainexus.gateway.mapper.UserFeedbackMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 控制台用户反馈: 用户提交、查看自己的反馈，管理员查看全部并回复。
 * 每条反馈只保留最近一次管理员回复，回复后状态从 PENDING 变为 REPLIED。
 */
@Service
@RequiredArgsConstructor
public class FeedbackService {
    private static final Set<String> CATEGORIES = Set.of("SUGGESTION", "BUG", "PERFORMANCE", "OTHER");
    private static final Set<String> STATUSES = Set.of("PENDING", "REPLIED");
    private static final int CONTENT_MIN_LENGTH = 2;
    private static final int CONTENT_MAX_LENGTH = 2000;
    private static final int CONTACT_MAX_LENGTH = 128;
    private static final String DEFAULT_CATEGORY = "OTHER";

    private final UserFeedbackMapper feedbackMapper;

    public FeedbackView submit(Long accountId, String username, SubmitRequest request) {
        if (request == null) throw BizException.badRequest("feedback_body_required", "缺少反馈内容");
        String content = request.content() == null ? "" : request.content().trim();
        if (content.length() < CONTENT_MIN_LENGTH) {
            throw BizException.badRequest("feedback_content_too_short",
                    "反馈内容至少需要 " + CONTENT_MIN_LENGTH + " 个字符");
        }
        if (content.length() > CONTENT_MAX_LENGTH) {
            throw BizException.badRequest("feedback_content_too_long",
                    "反馈内容不能超过 " + CONTENT_MAX_LENGTH + " 个字符");
        }
        UserFeedback feedback = new UserFeedback();
        feedback.setAccountId(accountId);
        feedback.setUsername(username);
        feedback.setCategory(category(request.category()));
        feedback.setContent(content);
        feedback.setContact(contact(request.contact()));
        feedback.setStatus("PENDING");
        feedbackMapper.insert(feedback);
        return view(feedbackMapper.selectById(feedback.getId()));
    }

    /** Newest first; a user only ever sees feedback submitted by the same account. */
    public List<FeedbackView> accountFeedback(Long accountId) {
        return feedbackMapper.selectList(new LambdaQueryWrapper<UserFeedback>()
                        .eq(UserFeedback::getAccountId, accountId)
                        .orderByDesc(UserFeedback::getCreateTime)
                        .orderByDesc(UserFeedback::getId))
                .stream().map(this::view).toList();
    }

    /**
     * Pending feedback first (alphabetically PENDING &lt; REPLIED) so unreplied items stay on top,
     * then newest first. A blank status means no filter.
     */
    public List<FeedbackView> adminFeedback(String status) {
        String filter = normalize(status);
        if (filter != null && !STATUSES.contains(filter)) {
            throw BizException.badRequest("invalid_feedback_status", "未知的反馈状态: " + status);
        }
        return feedbackMapper.selectList(new LambdaQueryWrapper<UserFeedback>()
                        .eq(filter != null, UserFeedback::getStatus, filter)
                        .orderByAsc(UserFeedback::getStatus)
                        .orderByDesc(UserFeedback::getCreateTime)
                        .orderByDesc(UserFeedback::getId))
                .stream().map(this::view).toList();
    }

    /** Creates or overwrites the administrator reply and marks the feedback as REPLIED. */
    public FeedbackView reply(Long id, String adminUsername, ReplyRequest request) {
        if (request == null) throw BizException.badRequest("feedback_reply_required", "回复内容不能为空");
        UserFeedback feedback = feedbackMapper.selectById(id);
        if (feedback == null) {
            throw BizException.badRequest("feedback_not_found", "反馈不存在: " + id);
        }
        String reply = request.reply() == null ? "" : request.reply().trim();
        if (reply.isEmpty()) {
            throw BizException.badRequest("feedback_reply_required", "回复内容不能为空");
        }
        if (reply.length() > CONTENT_MAX_LENGTH) {
            throw BizException.badRequest("feedback_reply_too_long",
                    "回复内容不能超过 " + CONTENT_MAX_LENGTH + " 个字符");
        }
        feedback.setReplyContent(reply);
        feedback.setRepliedBy(StringUtils.hasText(adminUsername) ? adminUsername : "admin");
        feedback.setRepliedAt(LocalDateTime.now());
        feedback.setStatus("REPLIED");
        feedbackMapper.updateById(feedback);
        return view(feedbackMapper.selectById(id));
    }

    private String category(String requested) {
        String value = normalize(requested);
        if (value == null) return DEFAULT_CATEGORY;
        if (!CATEGORIES.contains(value)) {
            throw BizException.badRequest("invalid_feedback_category", "未知的反馈类型: " + requested);
        }
        return value;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private String contact(String requested) {
        String value = StringUtils.hasText(requested) ? requested.trim() : null;
        if (value != null && value.length() > CONTACT_MAX_LENGTH) {
            throw BizException.badRequest("feedback_contact_too_long",
                    "联系方式不能超过 " + CONTACT_MAX_LENGTH + " 个字符");
        }
        return value;
    }

    private FeedbackView view(UserFeedback feedback) {
        return new FeedbackView(feedback.getId(), feedback.getAccountId(), feedback.getUsername(),
                feedback.getCategory(), feedback.getContent(), feedback.getContact(), feedback.getStatus(),
                feedback.getReplyContent(), feedback.getRepliedBy(), feedback.getRepliedAt(),
                feedback.getCreateTime(), feedback.getUpdateTime());
    }

    /**
     * 校验全部放在服务层: 记录/字节约束若交给 Bean Validation，失败会落到 500，
     * 这里统一抛出 BizException，用户端拿到的是可读的中文 400 提示。
     */
    public record SubmitRequest(String category, String content, String contact) { }

    public record ReplyRequest(String reply) { }

    public record FeedbackView(Long id, Long accountId, String username, String category, String content,
                               String contact, String status, String replyContent, String repliedBy,
                               LocalDateTime repliedAt, LocalDateTime createTime, LocalDateTime updateTime) { }
}