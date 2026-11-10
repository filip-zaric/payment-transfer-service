package com.example.payments.domain;

public record TransferOutcome(TransferReceipt receipt, boolean replayed) {}
