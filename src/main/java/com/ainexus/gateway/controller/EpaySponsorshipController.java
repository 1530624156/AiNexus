package com.ainexus.gateway.controller;

import com.ainexus.gateway.auth.ConsoleAuthInterceptor;
import com.ainexus.gateway.auth.ConsoleSessionService;
import com.ainexus.gateway.service.EpaySponsorshipService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class EpaySponsorshipController {
    private final EpaySponsorshipService service;

    @PostMapping("/console/sponsorship-orders")
    public EpaySponsorshipService.PaymentPage create(HttpServletRequest request, @RequestBody CreateRequest body) {
        ConsoleSessionService.SessionAccount account = account(request);
        return service.createPayment(account.id(), account.username(), body.amount(), body.paymentType());
    }

    @GetMapping("/console/sponsorship-orders/status")
    public EpaySponsorshipService.OrderView status(HttpServletRequest request, @RequestParam String outTradeNo) {
        return service.orderForAccount(account(request).id(), outTradeNo);
    }

    @GetMapping("/console/sponsorship-ranking")
    public java.util.List<EpaySponsorshipService.RankingView> ranking() { return service.ranking(); }

    @PostMapping(value = "/payments/epay/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public String notify(@RequestParam Map<String, String> params) { return service.handleNotification(params) ? "success" : "fail"; }

    private ConsoleSessionService.SessionAccount account(HttpServletRequest request) { return (ConsoleSessionService.SessionAccount) request.getAttribute(ConsoleAuthInterceptor.ATTRIBUTE); }
    public record CreateRequest(BigDecimal amount, String paymentType) { }
}
