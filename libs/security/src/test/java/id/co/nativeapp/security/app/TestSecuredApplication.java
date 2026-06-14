package id.co.nativeapp.security.app;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A minimal but faithful stand-in for a real Native business service, used to prove #16 end to end.
 *
 * <p>It boots the SAME shape every service carries — JPA over PostgreSQL, the {@code libs/tenant}
 * RLS plumbing, and the auto-RLS aspect that sets {@code app.current_tenant} on every
 * {@code @Transactional} unit of work — but adds NO infrastructure code of its own. The RLS beans +
 * aspect (and the pinned tx-advisor order + JPA auditing) come from {@code libs/tenant}'s {@code
 * TenantRlsAutoConfiguration}, and the local JWT validation + tenant binding from {@code
 * libs/security}'s {@code NativeSecurityAutoConfiguration} — both auto-configurations on the
 * classpath, which is the whole point: a service gets the behaviour by depending on the libraries,
 * with no copied config. This matches production exactly (no per-app {@code RlsConfig} clone).
 */
@SpringBootApplication
public class TestSecuredApplication {}
