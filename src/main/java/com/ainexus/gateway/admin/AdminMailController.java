package com.ainexus.gateway.admin;

import com.ainexus.gateway.service.MailSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/mail")
@RequiredArgsConstructor
public class AdminMailController {
    private final MailSettingsService mailSettingsService;

    @GetMapping("/settings")
    public MailSettingsService.MailSettings settings() { return mailSettingsService.settings(); }

    @PutMapping("/settings")
    public MailSettingsService.MailSettings update(@RequestBody MailSettingsService.Update request) {
        return mailSettingsService.update(request);
    }

    @PostMapping("/test")
    public void test(@RequestBody MailSettingsService.TestRequest request) { mailSettingsService.sendTest(request); }
}
