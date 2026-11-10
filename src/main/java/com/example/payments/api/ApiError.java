package com.example.payments.api;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public record ApiError(String code, String message) {}
