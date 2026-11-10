package com.example.payments.domain;

public enum ErrorCode {
  INVALID_REQUEST(400, "Provide valid account IDs, currency and an idempotency key."),
  INVALID_AMOUNT(
      400,
      "Amount must be positive, have at most two decimal places, and fit the supported range."),
  SAME_ACCOUNT(400, "Source and destination must be different accounts."),
  SOURCE_UNAVAILABLE(404, "Source account is unavailable."),
  DESTINATION_UNAVAILABLE(422, "Destination account cannot receive this transfer."),
  CURRENCY_MISMATCH(422, "Both accounts and the transfer must use the same currency."),
  INSUFFICIENT_FUNDS(422, "Source account has insufficient funds."),
  BALANCE_LIMIT_EXCEEDED(422, "Transfer would exceed the destination balance limit."),
  IDEMPOTENCY_CONFLICT(
      409, "The idempotency key was already used with different transfer details."),
  TRANSFER_NOT_FOUND(404, "Transfer was not found."),
  SERVICE_UNAVAILABLE(
      503,
      "Transfer outcome could not be confirmed. Retry with the same idempotency key and details.");

  private final int status;
  private final String message;

  ErrorCode(int status, String message) {
    this.status = status;
    this.message = message;
  }

  public int status() {
    return status;
  }

  public String message() {
    return message;
  }
}
