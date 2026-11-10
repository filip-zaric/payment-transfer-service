package com.example.payments.domain;

public final class TransferException extends RuntimeException {
  private final ErrorCode code;

  public TransferException(ErrorCode code) {
    super(code.message());
    this.code = code;
  }

  public TransferException(ErrorCode code, Throwable cause) {
    super(code.message(), cause);
    this.code = code;
  }

  public ErrorCode code() {
    return code;
  }
}
