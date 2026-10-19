package com.ainexus.gateway.admin;

import com.ainexus.gateway.service.GatewayRuntimeSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/gateway-settings")
@RequiredArgsConstructor
public class AdminGatewaySettingsController {
    private final GatewayRuntimeSettingsService settingsService;

    @GetMapping
    public GatewayRuntimeSettingsService.Settings get() { return settingsService.get(); }

    @PutMapping
    public GatewayRuntimeSettingsService.Settings update(@Valid @RequestBody GatewayRuntimeSettingsService.Update request) {
        return settingsService.update(request);
    }
}
