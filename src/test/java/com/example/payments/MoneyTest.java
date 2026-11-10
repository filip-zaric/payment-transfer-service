package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.domain.ErrorCode;
import com.example.payments.domain.Money;
import com.example.payments.domain.TransferException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {
  @ParameterizedTest
  @CsvSource({
    "0.01,1",
    "12,1200",
    "12.3,1230",
    "12.30,1230",
    "92233720368547758.07,9223372036854775807"
  })
  void parsesExactAmounts(String input, long expected) {
    assertEquals(expected, Money.toMinorUnits(new BigDecimal(input)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0",
        "-1",
        "0.001",
        "1.001",
        "1.000",
        "92233720368547758.08",
        "999999999999999999999999",
        "1e100000000"
      })
  void rejectsInvalidAmounts(String input) {
    assertEquals(
        ErrorCode.INVALID_AMOUNT,
        assertThrows(TransferException.class, () -> Money.toMinorUnits(new BigDecimal(input)))
            .code());
  }

  @Test
  void rejectsNull() {
    assertThrows(TransferException.class, () -> Money.toMinorUnits(null));
  }

  @Test
  void preservesExactBoundaryOnRoundTrip() {
    assertEquals(Long.MAX_VALUE, Money.toMinorUnits(Money.fromMinorUnits(Long.MAX_VALUE)));
  }
}
