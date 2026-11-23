package com.ainexus.gateway.controller;

import com.ainexus.gateway.service.ReleaseNoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Authenticated console feed containing only published release notes. */
@RestController
@RequestMapping("/api/console/release-notes")
@RequiredArgsConstructor
public class ReleaseNoteController {
    private final ReleaseNoteService releaseNoteService;

    @GetMapping
    public List<ReleaseNoteService.ReleaseNoteView> list() { return releaseNoteService.publishedList(); }
}
