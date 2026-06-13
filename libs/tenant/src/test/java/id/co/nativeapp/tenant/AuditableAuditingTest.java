package id.co.nativeapp.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.AuditorAware;
import java.util.Optional;

/**
 * Acceptance: "audit columns populate from a mocked security context."
 *
 * <p>Persists a {@link SampleEntity} and asserts that Spring Data JPA auditing
 * populated {@code created_at}/{@code updated_at} and that {@code created_by}/
 * {@code updated_by} came from the actor.
 *
 * <p>Two complementary checks share this class:
 * <ul>
 *   <li>{@link #populatesActorFromTenantScope()} uses the <em>real</em>
 *       {@link TenantContext} scope (the production path: the actor flows from the
 *       bound scoped value through {@link TenantAuditorAware}); and</li>
 *   <li>{@link #populatesActorFromMockedSecurityContext()} swaps in a
 *       <em>mocked</em> {@link AuditorAware} to prove the wiring reads whatever the
 *       security context supplies — the literal "mocked security context" the
 *       build plan calls for.</li>
 * </ul>
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class AuditableAuditingTest {

    private static final String COMPANY = "company-A";
    private static final String ACTOR = "user-42";

    @Autowired
    private SampleEntityRepository repository;

    @Test
    void populatesActorFromTenantScope() throws Exception {
        SampleEntity saved = TenantContext.callAs(COMPANY, ACTOR, () -> {
            SampleEntity entity = new SampleEntity("widget");
            entity.setCompanyId(COMPANY);
            return repository.saveAndFlush(entity);
        });

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getCreatedBy()).isEqualTo(ACTOR);
        assertThat(saved.getUpdatedBy()).isEqualTo(ACTOR);
        assertThat(saved.getCompanyId()).isEqualTo(COMPANY);
        assertThat(saved.getVersion()).isZero();
    }

    @Test
    void fallsBackToSystemActorOutsideAnyScope() {
        // No TenantContext scope: the auditor must fall back to "system" rather
        // than writing a null into the NOT NULL created_by column.
        SampleEntity entity = new SampleEntity("system-widget");
        entity.setCompanyId(COMPANY);
        SampleEntity saved = repository.saveAndFlush(entity);

        assertThat(saved.getCreatedBy()).isEqualTo(TenantAuditorAware.SYSTEM_ACTOR);
        assertThat(saved.getUpdatedBy()).isEqualTo(TenantAuditorAware.SYSTEM_ACTOR);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    /**
     * Nested test that replaces the {@link AuditorAware} bean with a Mockito mock
     * — the explicit "mocked security context" from the acceptance criteria — to
     * prove the auditing pipeline writes exactly the principal the security layer
     * reports, independent of {@link TenantContext}.
     */
    @DataJpaTest
    @Import(JpaAuditingConfig.class)
    static class WithMockedSecurityContext {

        @MockitoBean
        private AuditorAware<String> auditorAware;

        @Autowired
        private SampleEntityRepository repository;

        @Test
        void populatesActorFromMockedSecurityContext() {
            org.mockito.Mockito.when(auditorAware.getCurrentAuditor())
                    .thenReturn(Optional.of("mocked-principal"));

            SampleEntity entity = new SampleEntity("mock-widget");
            entity.setCompanyId(COMPANY);
            SampleEntity saved = repository.saveAndFlush(entity);

            assertThat(saved.getCreatedBy()).isEqualTo("mocked-principal");
            assertThat(saved.getUpdatedBy()).isEqualTo("mocked-principal");
            assertThat(saved.getCreatedAt()).isNotNull();
        }
    }
}
