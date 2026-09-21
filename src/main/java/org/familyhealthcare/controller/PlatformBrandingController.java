package org.familyhealthcare.controller;

import org.familyhealthcare.common.Result;
import org.familyhealthcare.service.PlatformBrandingService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/platform-branding")
public class PlatformBrandingController {
    private final PlatformBrandingService service;
    public PlatformBrandingController(PlatformBrandingService service) { this.service = service; }

    @GetMapping("/public")
    public Result<Map<String, Object>> publicBranding() { return Result.ok(service.get()); }

    @PostMapping
    public Result<Map<String, Object>> save(@RequestBody Map<String, Object> input) { return service.save(input); }
}
