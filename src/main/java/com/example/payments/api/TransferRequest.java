package com.example.payments.api;

import com.example.payments.domain.Currency;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

@Serdeable
public record TransferRequest(
    @NotNull UUID sourceAccountId,
    @NotNull UUID destinationAccountId,
    @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
    @NotNull Currency currency) {}
