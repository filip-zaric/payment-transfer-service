package com.example.payments;

import static org.junit.jupiter.api.Assertions.*;

import com.example.payments.domain.Currency;
import com.example.payments.domain.ErrorCode;
import com.example.payments.domain.TransferCommand;
import com.example.payments.domain.TransferException;
import com.example.payments.domain.TransferOutcome;
import com.example.payments.domain.TransferReceipt;
import com.example.payments.persistence.JdbcTransactions;
import com.example.payments.persistence.PaymentRepository;
import com.example.payments.service.TransferService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import io.micronaut.runtime.server.EmbeddedServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
class PaymentIntegrationTest {
  private static final UUID CHECKING = new UUID(0, 1);
  private static final UUID RECIPIENT = new UUID(0, 2);
  private static final UUID SAVINGS = new UUID(0, 3);
  private static final UUID EUROS = new UUID(0, 4);
  private static final String SECRET = "test-only-secret-32-bytes-minimum-never-use-in-production";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
  private static EmbeddedPostgres postgres;
  private static EmbeddedServer server;
  private static DataSource dataSource;
  private static TransferService service;
  private static JsonMapper mapper;
  private static HttpClient http;

  @BeforeAll
  static void start() throws Exception {
    postgres = EmbeddedPostgres.builder().setPort(0).start();
    Map<String, Object> properties = new HashMap<>();
    properties.put("datasources.default.url", postgres.getJdbcUrl("postgres", "postgres"));
    properties.put("datasources.default.username", "postgres");
    properties.put("datasources.default.password", "");
    properties.put("micronaut.security.token.jwt.signatures.secret.bank.secret", SECRET);
    properties.put("micronaut.server.port", -1);
    server = ApplicationContext.run(EmbeddedServer.class, properties, "test");
    dataSource = server.getApplicationContext().getBean(DataSource.class);
    service = newService();
    mapper = server.getApplicationContext().getBean(JsonMapper.class);
    http = HttpClient.newHttpClient();
  }

  @AfterAll
  static void stop() throws Exception {
    if (http != null) http.close();
    if (server != null) server.close();
    if (postgres != null) postgres.close();
  }

  @BeforeEach
  void seed() throws SQLException {
    execute("TRUNCATE ledger_entries, transfers, accounts CASCADE");
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "INSERT INTO accounts(id, owner_id, balance_minor, currency) VALUES (?, ?, ?, ?)")) {
      for (Object[] account :
          List.of(
              new Object[] {CHECKING, "alice", 10_000L, "USD"},
              new Object[] {RECIPIENT, "bob", 2000L, "USD"},
              new Object[] {SAVINGS, "alice", 1000L, "USD"},
              new Object[] {EUROS, "alice", 5000L, "EUR"})) {
        for (int i = 0; i < account.length; i++) statement.setObject(i + 1, account[i]);
        statement.executeUpdate();
      }
    }
  }

  @Test
  void persistsTransferAndBalancedAuditAtomically() throws Exception {
    var command = command("25.00");
    var outcome = service.transfer("alice", command);
    assertFalse(outcome.replayed());
    assertEquals(CLOCK.instant(), outcome.receipt().createdAt());
    assertEquals(new BigDecimal("75.00"), outcome.receipt().sourceBalanceAfter());
    assertEquals(7500, balance(CHECKING));
    assertEquals(4500, balance(RECIPIENT));
    assertEquals(1, scalar("SELECT COUNT(*) FROM transfers"));
    assertEquals(2, scalar("SELECT COUNT(*) FROM ledger_entries"));
    assertEquals(0, scalar("SELECT SUM(signed_amount_minor) FROM ledger_entries"));
    assertEquals(
        1, scalar("SELECT COUNT(*) FROM ledger_entries WHERE signed_amount_minor = -2500"));
    assertEquals(outcome.receipt(), service.find("alice", outcome.receipt().id()));
  }

  @Test
  void allowsExactBalanceAndThenRejectsOverdraft() throws Exception {
    service.transfer("alice", command("100.00"));
    assertEquals(0, balance(CHECKING));
    expectCode(ErrorCode.INSUFFICIENT_FUNDS, () -> service.transfer("alice", command("0.01")));
    assertEquals(1, scalar("SELECT COUNT(*) FROM transfers"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "0.001", "92233720368547758.08"})
  void serviceValidatesAmountWithoutRelyingOnHttp(String amount) throws Exception {
    expectCode(ErrorCode.INVALID_AMOUNT, () -> service.transfer("alice", command(amount)));
    assertUnchanged();
  }

  @Test
  void rejectsMissingFieldsAndSameAccount() throws Exception {
    expectCode(ErrorCode.INVALID_REQUEST, () -> service.transfer("alice", null));
    expectCode(
        ErrorCode.INVALID_REQUEST,
        () ->
            service.transfer(
                "alice",
                new TransferCommand(null, CHECKING, RECIPIENT, BigDecimal.ONE, Currency.USD)));
    expectCode(
        ErrorCode.SAME_ACCOUNT,
        () ->
            service.transfer(
                "alice",
                new TransferCommand(
                    UUID.randomUUID(), CHECKING, CHECKING, BigDecimal.ONE, Currency.USD)));
    assertUnchanged();
  }

  @Test
  void deniesUnauthorizedAndMissingSourceWithSameError() throws Exception {
    expectCode(ErrorCode.SOURCE_UNAVAILABLE, () -> service.transfer("bob", command("1")));
    expectCode(
        ErrorCode.SOURCE_UNAVAILABLE,
        () ->
            service.transfer(
                "bob",
                new TransferCommand(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    RECIPIENT,
                    BigDecimal.ONE,
                    Currency.USD)));
    assertUnchanged();
  }

  @Test
  void rejectsMissingDestinationAndCurrencyMismatch() throws Exception {
    expectCode(
        ErrorCode.DESTINATION_UNAVAILABLE,
        () ->
            service.transfer(
                "alice",
                new TransferCommand(
                    UUID.randomUUID(), CHECKING, UUID.randomUUID(), BigDecimal.ONE, Currency.USD)));
    expectCode(
        ErrorCode.CURRENCY_MISMATCH,
        () ->
            service.transfer(
                "alice",
                new TransferCommand(
                    UUID.randomUUID(), CHECKING, EUROS, BigDecimal.ONE, Currency.USD)));
    expectCode(
        ErrorCode.CURRENCY_MISMATCH,
        () ->
            service.transfer(
                "alice",
                new TransferCommand(
                    UUID.randomUUID(), CHECKING, RECIPIENT, BigDecimal.ONE, Currency.EUR)));
    assertUnchanged();
  }

  @Test
  void rejectsDestinationOverflowWithoutDebiting() throws Exception {
    execute(
        "UPDATE accounts SET balance_minor = 9223372036854775807 WHERE id = '" + RECIPIENT + "'");
    expectCode(ErrorCode.BALANCE_LIMIT_EXCEEDED, () -> service.transfer("alice", command("0.01")));
    assertEquals(10_000, balance(CHECKING));
    assertEquals(Long.MAX_VALUE, balance(RECIPIENT));
    assertEquals(0, scalar("SELECT COUNT(*) FROM transfers"));
  }

  @Test
  void replaySurvivesNewServiceInstanceAndInsufficientCurrentFunds() throws Exception {
    var command = command("100.00");
    var first = service.transfer("alice", command);
    var replay = newService().transfer("alice", command);
    assertTrue(replay.replayed());
    assertEquals(first.receipt(), replay.receipt());
    assertEquals(0, balance(CHECKING));
    assertEquals(12_000, balance(RECIPIENT));
    assertEquals(1, scalar("SELECT COUNT(*) FROM transfers"));
  }

  @Test
  void rejectsReusingKeyForDifferentPayload() throws Exception {
    var command = command("1");
    service.transfer("alice", command);
    expectCode(
        ErrorCode.IDEMPOTENCY_CONFLICT,
        () ->
            service.transfer(
                "alice",
                new TransferCommand(
                    command.idempotencyKey(),
                    CHECKING,
                    RECIPIENT,
                    new BigDecimal("2"),
                    Currency.USD)));
    expectCode(
        ErrorCode.IDEMPOTENCY_CONFLICT,
        () ->
            service.transfer(
                "alice",
                new TransferCommand(
                    command.idempotencyKey(),
                    CHECKING,
                    SAVINGS,
                    new BigDecimal("1"),
                    Currency.USD)));
    assertEquals(1, scalar("SELECT COUNT(*) FROM transfers"));
    assertEquals(9900, balance(CHECKING));
  }

  @Test
  void scopesIdempotencyAndReceiptAccessToPrincipal() throws Exception {
    var command = command("1");
    var first = service.transfer("alice", command);
    service.transfer(
        "bob",
        new TransferCommand(
            command.idempotencyKey(), RECIPIENT, SAVINGS, BigDecimal.ONE, Currency.USD));
    assertEquals(2, scalar("SELECT COUNT(*) FROM transfers"));
    expectCode(ErrorCode.TRANSFER_NOT_FOUND, () -> service.find("bob", first.receipt().id()));
  }

  @Test
  void secondPostingFailureRollsBackEverythingAndCanBeRetried() throws Exception {
    execute(
        """
            CREATE FUNCTION test_fail_credit() RETURNS TRIGGER LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'simulated storage failure'; END; $$;
            CREATE TRIGGER test_credit_failure BEFORE INSERT ON ledger_entries
            FOR EACH ROW WHEN (NEW.signed_amount_minor > 0) EXECUTE FUNCTION test_fail_credit();
            """);
    var command = command("25.00");
    try {
      expectCode(ErrorCode.SERVICE_UNAVAILABLE, () -> service.transfer("alice", command));
      assertUnchanged();
    } finally {
      execute(
          "DROP TRIGGER test_credit_failure ON ledger_entries; DROP FUNCTION test_fail_credit()");
    }
    service.transfer("alice", command);
    assertEquals(1, scalar("SELECT COUNT(*) FROM transfers"));
  }

  @Test
  void databaseRejectsIncompleteLedgerAtCommit() throws Exception {
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      var repository = new PaymentRepository();
      var receipt =
          new TransferReceipt(
              UUID.randomUUID(),
              UUID.randomUUID(),
              CHECKING,
              RECIPIENT,
              BigDecimal.ONE,
              Currency.USD,
              CLOCK.instant(),
              new BigDecimal("99.00"));
      repository.updateBalance(connection, CHECKING, 9900);
      repository.insertTransfer(connection, "alice", receipt, 100, 9900, 2100);
      repository.insertPosting(connection, receipt.id(), CHECKING, -100, Currency.USD);
      assertThrows(SQLException.class, connection::commit);
      connection.rollback();
    }
    assertUnchanged();
  }

  @Test
  void auditRowsAreAppendOnly() throws Exception {
    service.transfer("alice", command("1"));
    for (String sql :
        List.of(
            "DELETE FROM transfers",
            "UPDATE transfers SET amount_minor = 1",
            "DELETE FROM ledger_entries",
            "UPDATE ledger_entries SET signed_amount_minor = 1")) {
      assertThrows(SQLException.class, () -> execute(sql));
    }
    assertEquals(1, scalar("SELECT COUNT(*) FROM transfers"));
    assertEquals(2, scalar("SELECT COUNT(*) FROM ledger_entries"));
  }

  @Test
  void concurrentSpendingAcrossServiceInstancesNeverOverdraws() throws Exception {
    var services = List.of(newService(), newService());
    var jobs =
        IntStream.range(0, 40)
            .<Callable<ErrorCode>>mapToObj(
                index ->
                    () -> {
                      try {
                        services.get(index % 2).transfer("alice", command("5.00"));
                        return null;
                      } catch (TransferException exception) {
                        return exception.code();
                      }
                    })
            .toList();
    var outcomes = concurrently(jobs);
    assertEquals(20, outcomes.stream().filter(value -> value == null).count());
    assertEquals(
        20, outcomes.stream().filter(value -> value == ErrorCode.INSUFFICIENT_FUNDS).count());
    assertEquals(0, balance(CHECKING));
    assertEquals(12_000, balance(RECIPIENT));
    assertEquals(13_000, scalar("SELECT SUM(balance_minor) FROM accounts WHERE currency = 'USD'"));
    assertEquals(20, scalar("SELECT COUNT(*) FROM transfers"));
    assertEquals(40, scalar("SELECT COUNT(*) FROM ledger_entries"));
    assertEquals(0, scalar("SELECT SUM(signed_amount_minor) FROM ledger_entries"));
  }

  @Test
  void concurrentDuplicateRequestsCommitOnce() throws Exception {
    var services = List.of(newService(), newService());
    var command = command("25.00");
    var jobs =
        IntStream.range(0, 30)
            .<Callable<TransferOutcome>>mapToObj(
                index -> () -> services.get(index % 2).transfer("alice", command))
            .toList();
    var outcomes = concurrently(jobs);
    assertEquals(1, outcomes.stream().filter(value -> !value.replayed()).count());
    assertEquals(1, outcomes.stream().map(value -> value.receipt().id()).distinct().count());
    assertEquals(7500, balance(CHECKING));
    assertEquals(4500, balance(RECIPIENT));
    assertEquals(1, scalar("SELECT COUNT(*) FROM transfers"));
  }

  @Test
  void oppositeDirectionTransfersUseConsistentLockOrder() throws Exception {
    var services = List.of(newService(), newService());
    var jobs =
        IntStream.range(0, 40)
            .<Callable<TransferOutcome>>mapToObj(
                index ->
                    () -> {
                      boolean forward = index % 2 == 0;
                      var command =
                          new TransferCommand(
                              UUID.randomUUID(),
                              forward ? CHECKING : RECIPIENT,
                              forward ? RECIPIENT : CHECKING,
                              BigDecimal.ONE,
                              Currency.USD);
                      return services.get(index % 2).transfer(forward ? "alice" : "bob", command);
                    })
            .toList();
    assertEquals(40, concurrently(jobs).size());
    assertEquals(10_000, balance(CHECKING));
    assertEquals(2000, balance(RECIPIENT));
    assertEquals(40, scalar("SELECT COUNT(*) FROM transfers"));
  }

  @Test
  void concurrentConflictingIdempotencyKeysCannotCommitDifferentPayloads() throws Exception {
    UUID key = UUID.randomUUID();
    var jobs =
        IntStream.range(0, 20)
            .<Callable<ErrorCode>>mapToObj(
                index ->
                    () -> {
                      try {
                        newService()
                            .transfer(
                                "alice",
                                new TransferCommand(
                                    key,
                                    CHECKING,
                                    RECIPIENT,
                                    BigDecimal.valueOf(index % 2 + 1),
                                    Currency.USD));
                        return null;
                      } catch (TransferException exception) {
                        return exception.code();
                      }
                    })
            .toList();
    var outcomes = concurrently(jobs);
    assertEquals(10, outcomes.stream().filter(value -> value == null).count());
    assertEquals(
        10, outcomes.stream().filter(value -> value == ErrorCode.IDEMPOTENCY_CONFLICT).count());
    assertEquals(1, scalar("SELECT COUNT(*) FROM transfers"));
  }

  @Test
  void httpCreatesReplaysAndReadsSameReceipt() throws Exception {
    String token = token("alice", "valid");
    String key = UUID.randomUUID().toString();
    var first = post(body(CHECKING, RECIPIENT, "25.00"), key, token);
    assertEquals(201, first.statusCode(), first.body());
    var receipt = mapper.readValue(first.body(), TransferReceipt.class);
    assertEquals("false", first.headers().firstValue("Idempotency-Replayed").orElseThrow());
    assertEquals(
        "/transfers/" + receipt.id(), first.headers().firstValue("Location").orElseThrow());
    // The caller may have lost the first response. Reuse the original key and payload.
    var retry = post(body(CHECKING, RECIPIENT, "25.00"), key, token);
    assertEquals(200, retry.statusCode(), retry.body());
    assertEquals(receipt, mapper.readValue(retry.body(), TransferReceipt.class));
    assertEquals("true", retry.headers().firstValue("Idempotency-Replayed").orElseThrow());
    var read = get("/transfers/" + receipt.id(), token);
    assertEquals(200, read.statusCode(), read.body());
    assertEquals(receipt, mapper.readValue(read.body(), TransferReceipt.class));
    assertEquals(404, get("/transfers/" + receipt.id(), token("bob", "valid")).statusCode());
    assertFalse(first.body().contains("destinationBalance"));
    assertEquals(7500, balance(CHECKING));
    assertEquals(1, scalar("SELECT COUNT(*) FROM transfers"));
  }

  @Test
  void httpRequiresAuthentication() throws Exception {
    assertEquals(
        401, post(body(CHECKING, RECIPIENT, "1"), UUID.randomUUID().toString(), null).statusCode());
    assertEquals(401, get("/transfers/" + UUID.randomUUID(), null).statusCode());
    assertUnchanged();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "wrong-signature",
        "expired",
        "wrong-issuer",
        "wrong-audience",
        "missing-expiry",
        "blank-subject"
      })
  void httpRejectsInvalidJwt(String scenario) throws Exception {
    var result =
        post(
            body(CHECKING, RECIPIENT, "1"), UUID.randomUUID().toString(), token("alice", scenario));
    assertEquals(401, result.statusCode(), result.body());
    assertUnchanged();
  }

  @Test
  void httpCannotImpersonateOwnerThroughRequestBody() throws Exception {
    String payload = body(CHECKING, RECIPIENT, "1").replace("}", ",\"userId\":\"alice\"}");
    var result = post(payload, UUID.randomUUID().toString(), token("bob", "valid"));
    assertEquals(404, result.statusCode(), result.body());
    assertTrue(result.body().contains("SOURCE_UNAVAILABLE"));
    assertUnchanged();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "0.001", "92233720368547758.08", "null"})
  void httpRejectsInvalidAmount(String amount) throws Exception {
    var result =
        post(
            body(CHECKING, RECIPIENT, amount),
            UUID.randomUUID().toString(),
            token("alice", "valid"));
    assertEquals(400, result.statusCode(), result.body());
    assertUnchanged();
  }

  @Test
  void httpRejectsMalformedRequestsAndMissingIdempotencyKey() throws Exception {
    String token = token("alice", "valid");
    assertEquals(400, post("{", UUID.randomUUID().toString(), token).statusCode());
    assertEquals(400, post("{}", UUID.randomUUID().toString(), token).statusCode());
    assertEquals(400, post(body(CHECKING, RECIPIENT, "1"), null, token).statusCode());
    assertEquals(400, post(body(CHECKING, RECIPIENT, "1"), "invalid", token).statusCode());
    assertEquals(
        400,
        post(
                body(CHECKING, RECIPIENT, "1").replace("USD", "XYZ"),
                UUID.randomUUID().toString(),
                token)
            .statusCode());
    assertUnchanged();
  }

  @Test
  void httpMapsBusinessFailuresToStableErrors() throws Exception {
    var result =
        post(
            body(CHECKING, RECIPIENT, "100.01"),
            UUID.randomUUID().toString(),
            token("alice", "valid"));
    assertEquals(422, result.statusCode(), result.body());
    assertTrue(result.body().contains("INSUFFICIENT_FUNDS"));
    assertUnchanged();
  }

  @Test
  void httpMapsStorageFailuresWithoutExposingSql() throws Exception {
    execute(
        """
            CREATE FUNCTION test_fail_credit() RETURNS TRIGGER LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'sensitive-internal-database-detail'; END; $$;
            CREATE TRIGGER test_credit_failure BEFORE INSERT ON ledger_entries
            FOR EACH ROW WHEN (NEW.signed_amount_minor > 0) EXECUTE FUNCTION test_fail_credit();
            """);
    try {
      var result =
          post(
              body(CHECKING, RECIPIENT, "1"),
              UUID.randomUUID().toString(),
              token("alice", "valid"));
      assertEquals(503, result.statusCode(), result.body());
      assertTrue(result.body().contains("SERVICE_UNAVAILABLE"));
      assertFalse(result.body().contains("sensitive-internal-database-detail"));
      assertEquals("1", result.headers().firstValue("Retry-After").orElseThrow());
      assertUnchanged();
    } finally {
      execute(
          "DROP TRIGGER test_credit_failure ON ledger_entries; DROP FUNCTION test_fail_credit()");
    }
  }

  private static <T> List<T> concurrently(List<Callable<T>> jobs) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(16)) {
      var futures = new ArrayList<java.util.concurrent.Future<T>>();
      for (Callable<T> job : jobs) {
        futures.add(
            executor.submit(
                () -> {
                  if (!start.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("Test start timed out");
                  return job.call();
                }));
      }
      start.countDown();
      var values = new ArrayList<T>();
      for (var future : futures) values.add(future.get(15, TimeUnit.SECONDS));
      return values;
    }
  }

  private static String body(UUID source, UUID destination, String amount) {
    return """
            {"sourceAccountId":"%s","destinationAccountId":"%s","amount":%s,"currency":"USD"}
            """
        .formatted(source, destination, amount);
  }

  private static HttpResponse<String> post(String body, String key, String token) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(server.getURL() + "/transfers"))
            .timeout(java.time.Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (key != null) request.header("Idempotency-Key", key);
    if (token != null) request.header("Authorization", "Bearer " + token);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> get(String path, String token) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(server.getURL() + path))
            .timeout(java.time.Duration.ofSeconds(10))
            .GET();
    if (token != null) request.header("Authorization", "Bearer " + token);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String token(String subject, String scenario) throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .subject(scenario.equals("blank-subject") ? " " : subject)
            .issuer(scenario.equals("wrong-issuer") ? "attacker" : "payment-issuer")
            .audience(scenario.equals("wrong-audience") ? "other-service" : "payment-transfer")
            .issueTime(new Date());
    if (!scenario.equals("missing-expiry")) {
      claims.expirationTime(
          Date.from(Instant.now().plusSeconds(scenario.equals("expired") ? -120 : 300)));
    }
    var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
    jwt.sign(
        new MACSigner(
            scenario.equals("wrong-signature")
                ? "different-test-secret-of-at-least-32-bytes"
                : SECRET));
    return jwt.serialize();
  }

  private static TransferService newService() {
    return new TransferService(new JdbcTransactions(dataSource), new PaymentRepository(), CLOCK);
  }

  private static TransferCommand command(String amount) {
    return new TransferCommand(
        UUID.randomUUID(), CHECKING, RECIPIENT, new BigDecimal(amount), Currency.USD);
  }

  private static void expectCode(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
    assertEquals(code, assertThrows(TransferException.class, action).code());
  }

  private static void assertUnchanged() throws SQLException {
    assertEquals(10_000, balance(CHECKING));
    assertEquals(2000, balance(RECIPIENT));
    assertEquals(0, scalar("SELECT COUNT(*) FROM transfers"));
    assertEquals(0, scalar("SELECT COUNT(*) FROM ledger_entries"));
  }

  private static long balance(UUID account) throws SQLException {
    return scalar("SELECT balance_minor FROM accounts WHERE id = '" + account + "'");
  }

  private static long scalar(String sql) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      return result.getLong(1);
    }
  }

  private static void execute(String sql) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
