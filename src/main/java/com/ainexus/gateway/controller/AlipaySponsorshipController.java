package com.ainexus.gateway.controller;

import com.ainexus.gateway.auth.ConsoleAuthInterceptor;
import com.ainexus.gateway.auth.ConsoleSessionService;
import com.ainexus.gateway.service.AlipaySponsorshipService;
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
public class AlipaySponsorshipController {
    private final AlipaySponsorshipService service;

    @PostMapping("/console/sponsorship-orders")
    public AlipaySponsorshipService.PaymentPage create(HttpServletRequest request, @RequestBody CreateRequest body) {
        ConsoleSessionService.SessionAccount account = account(request);
        return service.createPayment(account.id(), account.username(), body.amount());
    }

    @GetMapping("/console/sponsorship-orders/status")
    public AlipaySponsorshipService.OrderView status(HttpServletRequest request, @RequestParam String outTradeNo) {
        return service.orderForAccount(account(request).id(), outTradeNo);
    }

    @PostMapping(value = "/payments/alipay/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public String notify(@RequestParam Map<String, String> params) {
        return service.handleNotification(params) ? "success" : "failure";
    }

    private ConsoleSessionService.SessionAccount account(HttpServletRequest request) {
        return (ConsoleSessionService.SessionAccount) request.getAttribute(ConsoleAuthInterceptor.ATTRIBUTE);
    }

    public record CreateRequest(BigDecimal amount) { }
}
