package com.example.ping.dto;

public record StatsBucket(String bucket, Double avgResponseMs, long count, long failures) {}
