package com.ainexus.gateway.controller;

import com.ainexus.gateway.service.AdvertisementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public advertisement payload consumed by the unauthenticated login page and the user workspace.
 * Only non-closed slots are exposed; no other gateway setting is included.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicAdvertisementController {
    private final AdvertisementService advertisementService;

    @GetMapping("/ads")
    public AdvertisementService.AdsView ads() { return advertisementService.publicView(); }
}