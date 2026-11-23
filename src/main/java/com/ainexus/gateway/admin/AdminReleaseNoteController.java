package com.ainexus.gateway.admin;

import com.ainexus.gateway.auth.ConsoleAuthInterceptor;
import com.ainexus.gateway.auth.ConsoleSessionService;
import com.ainexus.gateway.service.ReleaseNoteService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Administrator CRUD endpoints for release notes. */
@RestController
@RequestMapping("/api/admin/release-notes")
@RequiredArgsConstructor
public class AdminReleaseNoteController {
    private final ReleaseNoteService releaseNoteService;

    @GetMapping
    public List<ReleaseNoteService.ReleaseNoteView> list() { return releaseNoteService.adminList(); }

    @PostMapping
    public ReleaseNoteService.ReleaseNoteView create(HttpServletRequest request,
                                                     @RequestBody ReleaseNoteService.UpsertRequest body) {
        return releaseNoteService.create(body, adminUsername(request));
    }

    @PutMapping("/{id}")
    public ReleaseNoteService.ReleaseNoteView update(@PathVariable Long id,
                                                     @RequestBody ReleaseNoteService.UpsertRequest body) {
        return releaseNoteService.update(id, body);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) { releaseNoteService.delete(id); }

    private String adminUsername(HttpServletRequest request) {
        Object value = request.getAttribute(ConsoleAuthInterceptor.ATTRIBUTE);
        return value instanceof ConsoleSessionService.SessionAccount account ? account.username() : "admin";
    }
}
