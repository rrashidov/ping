package com.example.ping.web;

import com.example.ping.dto.FailuresResponse;
import com.example.ping.dto.StatsResponse;
import com.example.ping.service.PingStatsService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pings")
public class PingController {

    private final PingStatsService pingStatsService;

    public PingController(PingStatsService pingStatsService) {
        this.pingStatsService = pingStatsService;
    }

    @GetMapping("/stats")
    public StatsResponse stats(@RequestParam(defaultValue = "minute") String granularity) {
        return pingStatsService.getStats(granularity);
    }

    @GetMapping("/failures")
    public FailuresResponse failures() {
        return pingStatsService.getFailures();
    }
}
