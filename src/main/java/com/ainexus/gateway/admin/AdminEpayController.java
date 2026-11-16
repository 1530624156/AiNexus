package com.ainexus.gateway.admin;

import com.ainexus.gateway.service.EpaySponsorshipService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/epay")
@RequiredArgsConstructor
public class AdminEpayController {
    private final EpaySponsorshipService service;
    @GetMapping("/settings") public EpaySponsorshipService.AdminSettingView settings() { return service.settings(); }
    @PutMapping("/settings") public EpaySponsorshipService.AdminSettingView update(@RequestBody EpaySponsorshipService.UpdateSetting request) { return service.updateSettings(request); }
    @GetMapping("/sponsorship-orders") public List<EpaySponsorshipService.OrderView> orders() { return service.adminOrders(); }
}
