package com.example.payments.service;

import com.example.payments.domain.ErrorCode;
import com.example.payments.domain.Money;
import com.example.payments.domain.TransferCommand;
import com.example.payments.domain.TransferException;
import com.example.payments.domain.TransferOutcome;
import com.example.payments.domain.TransferReceipt;
import com.example.payments.persistence.Account;
import com.example.payments.persistence.JdbcTransactions;
import com.example.payments.persistence.PaymentRepository;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Singleton
public class TransferService {
  private final JdbcTransactions transactions;
  private final PaymentRepository repository;
  private final Clock clock;

  public TransferService(JdbcTransactions transactions, PaymentRepository repository, Clock clock) {
    this.transactions = transactions;
    this.repository = repository;
    this.clock = clock;
  }

  /** principal must be derived from verified authentication, never from a request body. */
  public TransferOutcome transfer(String principal, TransferCommand command) {
    validatePrincipal(principal);
    if (command == null) throw new TransferException(ErrorCode.INVALID_REQUEST);
    long amount = command.validateAndGetMinorUnits();
    return transactions.execute(
        connection -> {
          repository.lockRequest(connection, principal, command.idempotencyKey());
          var existing = repository.findByKey(connection, principal, command.idempotencyKey());
          if (existing.isPresent()) {
            if (!existing.get().matches(command))
              throw new TransferException(ErrorCode.IDEMPOTENCY_CONFLICT);
            return new TransferOutcome(existing.get(), true);
          }
          var accounts =
              repository.lockAccounts(
                  connection, command.sourceAccountId(), command.destinationAccountId());
          Account source = accounts.get(command.sourceAccountId());
          Account destination = accounts.get(command.destinationAccountId());
          if (source == null || !source.ownerId().equals(principal)) {
            throw new TransferException(ErrorCode.SOURCE_UNAVAILABLE);
          }
          if (destination == null) throw new TransferException(ErrorCode.DESTINATION_UNAVAILABLE);
          if (source.currency() != command.currency()
              || destination.currency() != command.currency()) {
            throw new TransferException(ErrorCode.CURRENCY_MISMATCH);
          }
          if (source.balanceMinor() < amount)
            throw new TransferException(ErrorCode.INSUFFICIENT_FUNDS);
          long destinationAfter;
          try {
            destinationAfter = Math.addExact(destination.balanceMinor(), amount);
          } catch (ArithmeticException exception) {
            throw new TransferException(ErrorCode.BALANCE_LIMIT_EXCEEDED);
          }
          long sourceAfter = source.balanceMinor() - amount;
          var receipt =
              new TransferReceipt(
                  UUID.randomUUID(),
                  command.idempotencyKey(),
                  source.id(),
                  destination.id(),
                  Money.fromMinorUnits(amount),
                  command.currency(),
                  clock.instant().truncatedTo(ChronoUnit.MICROS),
                  Money.fromMinorUnits(sourceAfter));
          repository.updateBalance(connection, source.id(), sourceAfter);
          repository.updateBalance(connection, destination.id(), destinationAfter);
          repository.insertTransfer(
              connection, principal, receipt, amount, sourceAfter, destinationAfter);
          repository.insertPosting(
              connection, receipt.id(), source.id(), -amount, command.currency());
          repository.insertPosting(
              connection, receipt.id(), destination.id(), amount, command.currency());
          return new TransferOutcome(receipt, false);
        });
  }

  public TransferReceipt find(String principal, UUID id) {
    validatePrincipal(principal);
    if (id == null) throw new TransferException(ErrorCode.INVALID_REQUEST);
    return transactions.execute(
        connection ->
            repository
                .findById(connection, principal, id)
                .orElseThrow(() -> new TransferException(ErrorCode.TRANSFER_NOT_FOUND)));
  }

  private void validatePrincipal(String principal) {
    if (principal == null || principal.isBlank() || principal.length() > 128) {
      throw new TransferException(ErrorCode.SOURCE_UNAVAILABLE);
    }
  }
}
