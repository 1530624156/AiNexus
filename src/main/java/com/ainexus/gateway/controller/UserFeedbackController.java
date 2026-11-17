package com.ainexus.gateway.controller;

import com.ainexus.gateway.auth.ConsoleAuthInterceptor;
import com.ainexus.gateway.auth.ConsoleSessionService;
import com.ainexus.gateway.service.FeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** USER portal feedback: submit new feedback and read own submission history with admin replies. */
@RestController
@RequestMapping("/api/console")
@RequiredArgsConstructor
public class UserFeedbackController {
    private final FeedbackService feedbackService;

    @GetMapping("/feedback")
    public List<FeedbackService.FeedbackView> list(HttpServletRequest request) {
        return feedbackService.accountFeedback(account(request).id());
    }

    @PostMapping("/feedback")
    public FeedbackService.FeedbackView submit(HttpServletRequest request,
                                               @RequestBody FeedbackService.SubmitRequest body) {
        ConsoleSessionService.SessionAccount account = account(request);
        return feedbackService.submit(account.id(), account.username(), body);
    }

    private ConsoleSessionService.SessionAccount account(HttpServletRequest request) {
        return (ConsoleSessionService.SessionAccount) request.getAttribute(ConsoleAuthInterceptor.ATTRIBUTE);
    }
}