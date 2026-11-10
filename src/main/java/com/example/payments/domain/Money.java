package com.example.payments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Supported currencies all have exactly two minor-unit digits. No floating-point arithmetic. */
public final class Money {
  private static final BigDecimal MAX_AMOUNT = BigDecimal.valueOf(Long.MAX_VALUE, 2);

  private Money() {}

  public static long toMinorUnits(BigDecimal amount) {
    if (amount == null
        || amount.signum() <= 0
        || amount.scale() > 2
        || amount.compareTo(MAX_AMOUNT) > 0) {
      throw new TransferException(ErrorCode.INVALID_AMOUNT);
    }
    try {
      return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    } catch (ArithmeticException exception) {
      throw new TransferException(ErrorCode.INVALID_AMOUNT);
    }
  }

  public static BigDecimal fromMinorUnits(long minorUnits) {
    return BigDecimal.valueOf(minorUnits, 2);
  }
}
