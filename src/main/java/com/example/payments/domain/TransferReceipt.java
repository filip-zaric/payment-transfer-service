package com.example.payments.domain;

import io.micronaut.serde.annotation.Serdeable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The recipient's balance and identity are intentionally excluded from the public receipt. */
@Serdeable
public record TransferReceipt(
    UUID id,
    UUID idempotencyKey,
    UUID sourceAccountId,
    UUID destinationAccountId,
    BigDecimal amount,
    Currency currency,
    Instant createdAt,
    BigDecimal sourceBalanceAfter) {
  public boolean matches(TransferCommand command) {
    return idempotencyKey.equals(command.idempotencyKey())
        && sourceAccountId.equals(command.sourceAccountId())
        && destinationAccountId.equals(command.destinationAccountId())
        && amount.compareTo(command.amount()) == 0
        && currency == command.currency();
  }
}
