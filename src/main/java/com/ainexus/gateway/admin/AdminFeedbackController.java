package com.ainexus.gateway.admin;

import com.ainexus.gateway.auth.ConsoleAuthInterceptor;
import com.ainexus.gateway.auth.ConsoleSessionService;
import com.ainexus.gateway.service.FeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Administrator view of console feedback with reply support. */
@RestController
@RequestMapping("/api/admin/feedback")
@RequiredArgsConstructor
public class AdminFeedbackController {
    private final FeedbackService feedbackService;

    @GetMapping
    public List<FeedbackService.FeedbackView> list(@RequestParam(required = false) String status) {
        return feedbackService.adminFeedback(status);
    }

    @PutMapping("/{id}/reply")
    public FeedbackService.FeedbackView reply(HttpServletRequest request, @PathVariable Long id,
                                              @RequestBody FeedbackService.ReplyRequest body) {
        return feedbackService.reply(id, adminUsername(request), body);
    }

    private String adminUsername(HttpServletRequest request) {
        Object value = request.getAttribute(ConsoleAuthInterceptor.ATTRIBUTE);
        return value instanceof ConsoleSessionService.SessionAccount account ? account.username() : "admin";
    }
}