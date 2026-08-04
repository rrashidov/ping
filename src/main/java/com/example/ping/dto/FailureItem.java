package com.example.ping.dto;

import java.time.LocalDateTime;

public record FailureItem(Long id, LocalDateTime pingedAt) {}
