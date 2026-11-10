package com.example.payments.persistence;

import com.example.payments.domain.ErrorCode;
import com.example.payments.domain.TransferException;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the connection and transaction. All repository calls use this exact connection. */
@Singleton
public class JdbcTransactions {
  private static final Logger LOG = LoggerFactory.getLogger(JdbcTransactions.class);
  private final DataSource dataSource;

  public JdbcTransactions(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @FunctionalInterface
  public interface Work<T> {
    T execute(Connection connection) throws SQLException;
  }

  public <T> T execute(Work<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        try (var statement = connection.createStatement()) {
          statement.execute("SET LOCAL lock_timeout = '3s'");
          statement.execute("SET LOCAL statement_timeout = '5s'");
        }
        T result = work.execute(connection);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException | Error failure) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
        throw failure;
      }
    } catch (SQLException failure) {
      // Do not log parameters, account IDs, balances, tokens, or raw SQL exception messages.
      LOG.error("Payment database operation failed; SQLSTATE={}", failure.getSQLState());
      throw new TransferException(ErrorCode.SERVICE_UNAVAILABLE, failure);
    }
  }
}
