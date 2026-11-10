package com.example.payments.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferCommand(
    UUID idempotencyKey,
    UUID sourceAccountId,
    UUID destinationAccountId,
    BigDecimal amount,
    Currency currency) {
  public long validateAndGetMinorUnits() {
    if (idempotencyKey == null
        || sourceAccountId == null
        || destinationAccountId == null
        || currency == null) {
      throw new TransferException(ErrorCode.INVALID_REQUEST);
    }
    if (sourceAccountId.equals(destinationAccountId)) {
      throw new TransferException(ErrorCode.SAME_ACCOUNT);
    }
    return Money.toMinorUnits(amount);
  }
}
