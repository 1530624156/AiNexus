package com.ainexus.gateway.admin;

import com.ainexus.gateway.service.AlipaySponsorshipService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/alipay")
@RequiredArgsConstructor
public class AdminAlipayController {
    private final AlipaySponsorshipService service;

    @GetMapping("/settings") public AlipaySponsorshipService.AdminSettingView settings() { return service.settings(); }
    @PutMapping("/settings") public AlipaySponsorshipService.AdminSettingView update(@RequestBody AlipaySponsorshipService.UpdateSetting request) { return service.updateSettings(request); }
    @GetMapping("/sponsorship-orders") public List<AlipaySponsorshipService.OrderView> orders() { return service.adminOrders(); }
}
