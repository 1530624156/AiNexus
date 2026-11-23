package com.ainexus.gateway.service;

import com.ainexus.gateway.common.BizException;
import com.ainexus.gateway.entity.ReleaseNote;
import com.ainexus.gateway.mapper.ReleaseNoteMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Release note lifecycle: administrators maintain drafts and publish them to console users. */
@Service
@RequiredArgsConstructor
public class ReleaseNoteService {
    private static final String DRAFT = "DRAFT";
    private static final String PUBLISHED = "PUBLISHED";
    private static final Set<String> STATUSES = Set.of(DRAFT, PUBLISHED);
    private static final int VERSION_MAX_LENGTH = 64;
    private static final int TITLE_MAX_LENGTH = 160;
    private static final int CONTENT_MAX_LENGTH = 10_000;

    private final ReleaseNoteMapper releaseNoteMapper;

    public List<ReleaseNoteView> adminList() {
        return releaseNoteMapper.selectList(new LambdaQueryWrapper<ReleaseNote>()
                        .orderByDesc(ReleaseNote::getPublishedAt)
                        .orderByDesc(ReleaseNote::getCreateTime)
                        .orderByDesc(ReleaseNote::getId))
                .stream().map(this::view).toList();
    }

    public List<ReleaseNoteView> publishedList() {
        return releaseNoteMapper.selectList(new LambdaQueryWrapper<ReleaseNote>()
                        .eq(ReleaseNote::getStatus, PUBLISHED)
                        .orderByDesc(ReleaseNote::getPublishedAt)
                        .orderByDesc(ReleaseNote::getId))
                .stream().map(this::view).toList();
    }

    public ReleaseNoteView create(UpsertRequest request, String adminUsername) {
        ReleaseNote note = apply(new ReleaseNote(), request);
        note.setCreatedBy(StringUtils.hasText(adminUsername) ? adminUsername.trim() : "admin");
        if (PUBLISHED.equals(note.getStatus())) note.setPublishedAt(LocalDateTime.now());
        releaseNoteMapper.insert(note);
        return view(releaseNoteMapper.selectById(note.getId()));
    }

    public ReleaseNoteView update(Long id, UpsertRequest request) {
        ReleaseNote note = required(id);
        String previousStatus = note.getStatus();
        apply(note, request);
        if (PUBLISHED.equals(note.getStatus()) && !PUBLISHED.equals(previousStatus)) {
            note.setPublishedAt(LocalDateTime.now());
        }
        if (DRAFT.equals(note.getStatus())) {
            releaseNoteMapper.update(null, new LambdaUpdateWrapper<ReleaseNote>()
                    .eq(ReleaseNote::getId, id)
                    .set(ReleaseNote::getVersion, note.getVersion())
                    .set(ReleaseNote::getTitle, note.getTitle())
                    .set(ReleaseNote::getContent, note.getContent())
                    .set(ReleaseNote::getStatus, DRAFT)
                    .set(ReleaseNote::getPublishedAt, null));
        } else {
            releaseNoteMapper.updateById(note);
        }
        return view(releaseNoteMapper.selectById(id));
    }

    public void delete(Long id) {
        required(id);
        releaseNoteMapper.deleteById(id);
    }

    private ReleaseNote apply(ReleaseNote note, UpsertRequest request) {
        if (request == null) throw BizException.badRequest("release_note_body_required", "缺少更新日志内容");
        note.setVersion(requiredText(request.version(), "版本号", VERSION_MAX_LENGTH));
        note.setTitle(requiredText(request.title(), "标题", TITLE_MAX_LENGTH));
        note.setContent(requiredText(request.content(), "内容", CONTENT_MAX_LENGTH));
        String status = StringUtils.hasText(request.status()) ? request.status().trim().toUpperCase(Locale.ROOT) : DRAFT;
        if (!STATUSES.contains(status)) throw BizException.badRequest("release_note_invalid_status", "未知的发布状态: " + request.status());
        note.setStatus(status);
        return note;
    }

    private ReleaseNote required(Long id) {
        ReleaseNote note = releaseNoteMapper.selectById(id);
        if (note == null) throw BizException.badRequest("release_note_not_found", "更新日志不存在: " + id);
        return note;
    }

    private String requiredText(String value, String label, int maxLength) {
        String text = StringUtils.hasText(value) ? value.trim() : "";
        if (text.isEmpty()) throw BizException.badRequest("release_note_" + label + "_required", label + "不能为空");
        if (text.length() > maxLength) throw BizException.badRequest("release_note_text_too_long", label + "不能超过 " + maxLength + " 个字符");
        return text;
    }

    private ReleaseNoteView view(ReleaseNote note) {
        return new ReleaseNoteView(note.getId(), note.getVersion(), note.getTitle(), note.getContent(), note.getStatus(),
                note.getPublishedAt(), note.getCreatedBy(), note.getCreateTime(), note.getUpdateTime());
    }

    public record UpsertRequest(String version, String title, String content, String status) { }

    public record ReleaseNoteView(Long id, String version, String title, String content, String status,
                                  LocalDateTime publishedAt, String createdBy, LocalDateTime createTime,
                                  LocalDateTime updateTime) { }
}
