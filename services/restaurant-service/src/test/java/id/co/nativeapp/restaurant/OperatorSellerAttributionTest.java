package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.config.ActorTypeProvider;
import id.co.nativeapp.restaurant.metric.messaging.MetricPublishedSchema;
import id.co.nativeapp.restaurant.sale.domain.OperatorMismatchException;
import id.co.nativeapp.restaurant.sale.domain.OperatorRequiredException;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.service.SaleService;
import id.co.nativeapp.security.OperatorSessionFilter;
import id.co.nativeapp.security.OperatorTokenCodec;
import id.co.nativeapp.security.OperatorTokenPayload;
import id.co.nativeapp.security.OperatorTokenSigningKey;
import id.co.nativeapp.tenant.TenantContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * ADR 0049 P2 — proves restaurant-service's wiring end to end, through the REAL registered
 * collaborators: the {@link OperatorSessionFilter} instantiated with the service's own {@link
 * OperatorTokenSigningKey} bean (built by {@code OperatorTokenSigningConfig} from {@code
 * native.operator-token.signing-key}), and {@code SaleWriter} reading the resulting {@code
 * OperatorPrincipal} via {@code OperatorContextProvider}.
 *
 * <p>Mirrors {@link SaleMetricEmissionTest} (subject-attribution assertions via the {@code outbox}
 * table — {@code outbox} carries NO row-level security, so a plain {@code jdbcTemplate} read is
 * fine unscoped) and restaurant-service's {@code SelfOrderTokenFilterTest} (constructing the filter
 * directly and running {@code doFilter} against a {@link MockHttpServletRequest}) — combined,
 * because ADR 0049 P2 sits exactly at that seam: a token-verifying filter feeding a money-path
 * service call. The {@code sale} table itself IS {@code FORCE} row-level security (unlike {@code
 * outbox}), so any assertion reading it back — {@link #soldByUserIdAsAdmin} / {@link
 * #saleRowCountAsAdmin} — goes over the admin (BYPASSRLS) connection, mirroring {@code
 * RecordSaleConcurrencyTest}: the tenant GUC {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} sets
 * is transaction-scoped ({@code SET LOCAL}) and reverts at commit, so a plain post-hoc {@code
 * jdbcTemplate} read against an RLS-forced table — even one wrapped in {@link TenantContext#callAs}
 * — sees NOTHING (the GUC binding a Java-side {@code ScopedValue} is not the same thing as the
 * Postgres session GUC, which only a live {@code @Transactional} unit of work sets).
 */
@SpringBootTest
class OperatorSellerAttributionTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "44444444-4444-4444-4444-444444444444";
  private static final UUID OUTLET_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID OUTLET_B = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final String DEVICE_ACTOR = "cashier-device-01@example.co.id";

  @Autowired private SaleService saleService;
  @Autowired private OperatorTokenSigningKey signingKey;
  @Autowired private Clock clock;
  @Autowired private JdbcTemplate jdbcTemplate;

  private OperatorSessionFilter filter() {
    return new OperatorSessionFilter(signingKey.secretBytes(), clock);
  }

  private String mintToken(String companyId, UUID businessId, String operatorUserId) {
    OperatorTokenPayload payload =
        new OperatorTokenPayload(
            OperatorTokenPayload.CURRENT_VERSION,
            companyId,
            businessId,
            operatorUserId,
            UUID.randomUUID(),
            "Budi Santoso",
            "cashier",
            clock.instant().minusSeconds(60).getEpochSecond(),
            clock.instant().plusSeconds(3600).getEpochSecond(),
            UUID.randomUUID().toString());
    return OperatorTokenCodec.encode(payload, signingKey.secretBytes());
  }

  /**
   * Runs {@code saleService.recordSale(command)} through the REAL {@link OperatorSessionFilter}
   * (bound to TENANT_A / DEVICE_ACTOR — mirrors an outlet-credential device carrying a normal
   * tenant), with the given {@code X-Operator-Session} header value (or none, when {@code
   * operatorToken} is {@code null}). Throws {@link AssertionError} if the filter itself rejects the
   * token with a {@code 401} (so a malformed/expired-token scenario fails loudly here rather than
   * silently returning a null result); any {@link RuntimeException} SaleWriter itself throws (e.g.
   * {@link OperatorMismatchException}) propagates to the caller unwrapped.
   */
  private RecordSaleResult recordSaleWithOperatorToken(
      RecordSaleCommand command, String operatorToken) throws Exception {
    return recordSaleWithOperatorToken(command, operatorToken, null);
  }

  /**
   * ADR 0049 P4 variant of {@link #recordSaleWithOperatorToken(RecordSaleCommand, String)} that
   * ALSO sets {@code X-Actor-Type} (mirroring the gateway-injected header) when {@code actorType}
   * is non-null — used to prove the {@code OperatorRequiredGuard} device-guard behaviour.
   */
  private RecordSaleResult recordSaleWithOperatorToken(
      RecordSaleCommand command, String operatorToken, String actorType) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sales");
    if (actorType != null) {
      request.addHeader(ActorTypeProvider.ACTOR_TYPE_HEADER, actorType);
    }
    if (operatorToken != null) {
      request.addHeader(OperatorSessionFilter.OPERATOR_SESSION_HEADER, operatorToken);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    RecordSaleResult[] holder = new RecordSaleResult[1];

    filter()
        .doFilter(
            request,
            response,
            (req, res) -> {
              RequestContextHolder.setRequestAttributes(
                  new ServletRequestAttributes((HttpServletRequest) req));
              try {
                holder[0] =
                    TenantContext.callAs(
                        TENANT_A, DEVICE_ACTOR, () -> saleService.recordSale(command));
              } catch (RuntimeException e) {
                throw e;
              } catch (Exception e) {
                throw new ServletException(e);
              } finally {
                RequestContextHolder.resetRequestAttributes();
              }
            });

    if (response.getStatus() == 401) {
      throw new AssertionError("operator session token rejected by the filter (401)");
    }
    return holder[0];
  }

  /**
   * {@code sale.sold_by_user_id} for the given sale id, read over the admin (BYPASSRLS) connection
   * — {@code sale} is {@code FORCE} row-level security, so a plain unscoped {@code jdbcTemplate}
   * read would see nothing (see class javadoc).
   */
  private String soldByUserIdAsAdmin(UUID saleId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT sold_by_user_id FROM sale WHERE id = ?")) {
      ps.setObject(1, saleId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new AssertionError("no sale row found for id " + saleId);
        }
        return rs.getString(1);
      }
    }
  }

  /** {@code sale} row count for an idempotency key, over the admin (BYPASSRLS) connection. */
  private long saleRowCountAsAdmin(String idempotencyKey) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT count(*) FROM sale WHERE idempotency_key = ?")) {
      ps.setString(1, idempotencyKey);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  @Test
  void anOperatorPresentSaleCreditsTheOperatorAsTheMetricSubjectAndStampsSoldByUserId()
      throws Exception {
    String operatorUserId = UUID.randomUUID().toString();
    String token = mintToken(TENANT_A, OUTLET_A, operatorUserId);
    RecordSaleCommand command =
        new RecordSaleCommand(
            OUTLET_A, 250_000L, "IDR", Instant.now(), UUID.randomUUID().toString());

    RecordSaleResult result = recordSaleWithOperatorToken(command, token);
    assertThat(result.created()).isTrue();

    // sold_by_user_id is stamped with the OPERATOR's id, never the device actor.
    assertThat(soldByUserIdAsAdmin(result.sale().id())).isEqualTo(operatorUserId);

    // The sales_amount @ employee MetricPublished carries the OPERATOR's id as subject_id.
    List<Map<String, Object>> metricRows =
        jdbcTemplate.queryForList(
            "SELECT payload FROM outbox WHERE event_type = 'MetricPublished' AND aggregate_id = ?",
            result.sale().id().toString());
    assertThat(metricRows).hasSize(1);
    GenericRecord metric =
        AvroSerde.deserialize(
            (byte[]) metricRows.getFirst().get("payload"), MetricPublishedSchema.schema());
    assertThat(metric.get("subject_id").toString()).isEqualTo(operatorUserId);
  }

  @Test
  void anOperatorAbsentSaleIsUnchangedActorSubjectAndNullSeller() throws Exception {
    RecordSaleCommand command =
        new RecordSaleCommand(
            OUTLET_A, 150_000L, "IDR", Instant.now(), UUID.randomUUID().toString());

    RecordSaleResult result = recordSaleWithOperatorToken(command, null);
    assertThat(result.created()).isTrue();

    assertThat(soldByUserIdAsAdmin(result.sale().id())).isNull();

    // DEVICE_ACTOR is not a UUID sub, so — unchanged pre-ADR-0049 behaviour — no metric row is
    // written (the documented dev-recipe non-UUID-actor skip).
    Long metricCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE event_type = 'MetricPublished' AND aggregate_id = ?",
            Long.class,
            result.sale().id().toString());
    assertThat(metricCount).isZero();
  }

  @Test
  void aWrongTenantOperatorTokenIsRejected() throws Exception {
    // Minted for TENANT_B, presented on a request bound to TENANT_A.
    String token = mintToken(TENANT_B, OUTLET_A, UUID.randomUUID().toString());
    RecordSaleCommand command =
        new RecordSaleCommand(
            OUTLET_A, 100_000L, "IDR", Instant.now(), UUID.randomUUID().toString());

    assertThatThrownBy(() -> recordSaleWithOperatorToken(command, token))
        .isInstanceOf(OperatorMismatchException.class);

    // No sale was recorded (the transactional write rolled back before saveAndFlush).
    assertThat(saleRowCountAsAdmin(command.idempotencyKey())).isZero();
  }

  @Test
  void aWrongOutletOperatorTokenIsRejected() throws Exception {
    // Minted for OUTLET_B, presented for a sale recorded against OUTLET_A.
    String token = mintToken(TENANT_A, OUTLET_B, UUID.randomUUID().toString());
    RecordSaleCommand command =
        new RecordSaleCommand(
            OUTLET_A, 100_000L, "IDR", Instant.now(), UUID.randomUUID().toString());

    assertThatThrownBy(() -> recordSaleWithOperatorToken(command, token))
        .isInstanceOf(OperatorMismatchException.class);
  }

  @Test
  void aMalformedOperatorSessionHeaderIsRejectedBeforeTheSaleIsEverAttempted() throws Exception {
    RecordSaleCommand command =
        new RecordSaleCommand(
            OUTLET_A, 100_000L, "IDR", Instant.now(), UUID.randomUUID().toString());

    assertThatThrownBy(() -> recordSaleWithOperatorToken(command, "garbage-not-a-token"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("401");

    assertThat(saleRowCountAsAdmin(command.idempotencyKey())).isZero();
  }

  // ---------------------------------------------------------------- ADR 0049 P4: device guard

  /**
   * The load-bearing P4 rejection: an {@code X-Actor-Type: device} request with NO {@code
   * X-Operator-Session} must never record a sale — it would otherwise attribute the sale to the
   * device itself (or to nobody), silently breaking commission.
   */
  @Test
  void aDeviceActorWithNoOperatorSessionIsRejectedWithOperatorRequired() throws Exception {
    RecordSaleCommand command =
        new RecordSaleCommand(
            OUTLET_A, 100_000L, "IDR", Instant.now(), UUID.randomUUID().toString());

    assertThatThrownBy(() -> recordSaleWithOperatorToken(command, null, ActorTypeProvider.DEVICE))
        .isInstanceOf(OperatorRequiredException.class);

    // No sale was recorded (the transactional write rolled back before saveAndFlush).
    assertThat(saleRowCountAsAdmin(command.idempotencyKey())).isZero();
  }

  /**
   * A device actor WITH a verified operator session is admitted and credited to the operator —
   * proving the P4 guard does not block the legitimate PIN-rung device sale.
   */
  @Test
  void aDeviceActorWithAVerifiedOperatorSessionIsAdmittedAndCreditedToTheOperator()
      throws Exception {
    String operatorUserId = UUID.randomUUID().toString();
    String token = mintToken(TENANT_A, OUTLET_A, operatorUserId);
    RecordSaleCommand command =
        new RecordSaleCommand(
            OUTLET_A, 250_000L, "IDR", Instant.now(), UUID.randomUUID().toString());

    RecordSaleResult result = recordSaleWithOperatorToken(command, token, ActorTypeProvider.DEVICE);
    assertThat(result.created()).isTrue();
    assertThat(soldByUserIdAsAdmin(result.sale().id())).isEqualTo(operatorUserId);
  }

  /**
   * A normal {@code actor_type=user} login (owner/manager/cashier ringing directly, no outlet
   * device involved) with no operator session is completely unaffected by the P4 guard — the exact
   * pre-P4/P2 behaviour ({@link #anOperatorAbsentSaleIsUnchangedActorSubjectAndNullSeller} verifies
   * the metric-skip half of this; this pins that no {@code X-Actor-Type} header at all behaves
   * identically to an explicit {@code X-Actor-Type: user}).
   */
  @Test
  void anExplicitUserActorTypeWithNoOperatorSessionIsUnaffectedByTheDeviceGuard() throws Exception {
    RecordSaleCommand command =
        new RecordSaleCommand(
            OUTLET_A, 175_000L, "IDR", Instant.now(), UUID.randomUUID().toString());

    RecordSaleResult result = recordSaleWithOperatorToken(command, null, ActorTypeProvider.USER);
    assertThat(result.created()).isTrue();
    assertThat(soldByUserIdAsAdmin(result.sale().id())).isNull();
  }
}
