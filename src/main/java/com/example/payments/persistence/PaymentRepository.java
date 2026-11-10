package com.example.payments.persistence;

import com.example.payments.domain.Currency;
import com.example.payments.domain.Money;
import com.example.payments.domain.TransferReceipt;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Stateless JDBC adapter; never opens a second connection inside a transaction. */
@Singleton
public class PaymentRepository {
  private static final String RECEIPT_COLUMNS =
      """
        id, idempotency_key, source_id, destination_id, amount_minor,
        currency, created_at, source_balance_after
        """;

  public void lockRequest(Connection connection, String principal, UUID key) throws SQLException {
    // Transaction-scoped lock works across threads, processes and service instances.
    // A hash collision only serializes unrelated requests; the unique constraint is authoritative.
    try (var statement =
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      statement.setString(1, principal + ":" + key);
      statement.execute();
    }
  }

  public Optional<TransferReceipt> findByKey(Connection connection, String principal, UUID key)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT "
                + RECEIPT_COLUMNS
                + " FROM transfers WHERE principal_id = ? AND idempotency_key = ?")) {
      statement.setString(1, principal);
      statement.setObject(2, key);
      try (var result = statement.executeQuery()) {
        return result.next() ? Optional.of(receipt(result)) : Optional.empty();
      }
    }
  }

  public Optional<TransferReceipt> findById(Connection connection, String principal, UUID id)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT " + RECEIPT_COLUMNS + " FROM transfers WHERE principal_id = ? AND id = ?")) {
      statement.setString(1, principal);
      statement.setObject(2, id);
      try (var result = statement.executeQuery()) {
        return result.next() ? Optional.of(receipt(result)) : Optional.empty();
      }
    }
  }

  public Map<UUID, Account> lockAccounts(Connection connection, UUID source, UUID destination)
      throws SQLException {
    // PostgreSQL locks rows in this common order even for transfers in opposite directions.
    try (var statement =
        connection.prepareStatement(
            """
                SELECT id, owner_id, balance_minor, currency FROM accounts
                WHERE id IN (?, ?) ORDER BY id FOR UPDATE
                """)) {
      statement.setObject(1, source);
      statement.setObject(2, destination);
      try (var result = statement.executeQuery()) {
        Map<UUID, Account> accounts = new HashMap<>();
        while (result.next()) {
          UUID id = result.getObject("id", UUID.class);
          accounts.put(
              id,
              new Account(
                  id,
                  result.getString("owner_id"),
                  result.getLong("balance_minor"),
                  Currency.valueOf(result.getString("currency"))));
        }
        return accounts;
      }
    }
  }

  public void updateBalance(Connection connection, UUID account, long balance) throws SQLException {
    try (var statement =
        connection.prepareStatement("UPDATE accounts SET balance_minor = ? WHERE id = ?")) {
      statement.setLong(1, balance);
      statement.setObject(2, account);
      if (statement.executeUpdate() != 1) {
        throw new SQLException("Expected one account update", "P0001");
      }
    }
  }

  public void insertTransfer(
      Connection connection,
      String principal,
      TransferReceipt receipt,
      long amountMinor,
      long sourceAfter,
      long destinationAfter)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
                INSERT INTO transfers (id, principal_id, idempotency_key, source_id, destination_id,
                    amount_minor, currency, created_at, source_balance_after, destination_balance_after)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
      statement.setObject(1, receipt.id());
      statement.setString(2, principal);
      statement.setObject(3, receipt.idempotencyKey());
      statement.setObject(4, receipt.sourceAccountId());
      statement.setObject(5, receipt.destinationAccountId());
      statement.setLong(6, amountMinor);
      statement.setString(7, receipt.currency().name());
      statement.setObject(8, OffsetDateTime.ofInstant(receipt.createdAt(), ZoneOffset.UTC));
      statement.setLong(9, sourceAfter);
      statement.setLong(10, destinationAfter);
      statement.executeUpdate();
    }
  }

  public void insertPosting(
      Connection connection, UUID transfer, UUID account, long signedAmount, Currency currency)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
                INSERT INTO ledger_entries (transfer_id, account_id, signed_amount_minor, currency)
                VALUES (?, ?, ?, ?)
                """)) {
      statement.setObject(1, transfer);
      statement.setObject(2, account);
      statement.setLong(3, signedAmount);
      statement.setString(4, currency.name());
      statement.executeUpdate();
    }
  }

  private TransferReceipt receipt(ResultSet result) throws SQLException {
    return new TransferReceipt(
        result.getObject("id", UUID.class),
        result.getObject("idempotency_key", UUID.class),
        result.getObject("source_id", UUID.class),
        result.getObject("destination_id", UUID.class),
        Money.fromMinorUnits(result.getLong("amount_minor")),
        Currency.valueOf(result.getString("currency")),
        result.getObject("created_at", OffsetDateTime.class).toInstant(),
        Money.fromMinorUnits(result.getLong("source_balance_after")));
  }
}
