package com.example.ping.dto;

import java.util.List;

public record StatsResponse(String granularity, List<StatsBucket> data) {}
