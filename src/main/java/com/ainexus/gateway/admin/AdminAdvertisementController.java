package com.ainexus.gateway.admin;

import com.ainexus.gateway.service.AdvertisementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrator configuration for the commercial advertisement slots. */
@RestController
@RequestMapping("/api/admin/ads")
@RequiredArgsConstructor
public class AdminAdvertisementController {
    private final AdvertisementService advertisementService;

    @GetMapping
    public AdvertisementService.AdsView get() { return advertisementService.adminView(); }

    @PutMapping
    public AdvertisementService.AdsView update(@RequestBody AdvertisementService.UpdateRequest request) {
        return advertisementService.update(request);
    }
}