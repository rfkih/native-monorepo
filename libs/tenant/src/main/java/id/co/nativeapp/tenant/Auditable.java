package id.co.nativeapp.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The mandatory audit + tenancy base class for <em>every</em> persistent entity
 * in Native (CLAUDE.md rule 4). Every table carries:
 *
 * <ul>
 *   <li>{@code created_at} / {@code created_by} — set once on first persist;</li>
 *   <li>{@code updated_at} / {@code updated_by} — refreshed on every update;</li>
 *   <li>{@code version} — JPA optimistic-locking counter;</li>
 *   <li>{@code company_id} — the owning tenant, which every query is scoped by
 *       and which PostgreSQL row-level security keys on a second time.</li>
 * </ul>
 *
 * <p>The {@code created_by}/{@code updated_by} actor and the {@code created_at}/
 * {@code updated_at} timestamps are populated automatically by Spring Data JPA
 * auditing ({@link AuditingEntityListener}) — the actor comes from
 * {@link TenantAuditorAware}, which reads {@link TenantContext}. Wiring this up
 * requires {@link JpaAuditingConfig} (or the host application's own
 * {@code @EnableJpaAuditing}) to be on the context.
 *
 * <p>This is deliberately <em>not</em> a hand-rolled audit trail: full
 * before/after history is captured out-of-band by Debezium CDC. These columns
 * are the lightweight "who/when last touched this row" stamp plus the tenant
 * key; they are not a substitute for the CDC audit store.
 *
 * <p>{@code company_id} is intentionally left for subclasses / the auditing
 * pipeline to populate from {@link TenantContext} at the application layer and
 * is enforced at the database layer by RLS; it is declared here so the column
 * exists uniformly on every table and is {@code updatable = false} (a row never
 * migrates between tenants).
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class Auditable {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false, length = 255)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by", nullable = false, length = 255)
    private String updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * The owning tenant. Never null, never changes once set (a row cannot move
     * between companies), and is the key both for application-level scoping and
     * for PostgreSQL row-level security.
     */
    @Column(name = "company_id", nullable = false, updatable = false, length = 64)
    private String companyId;

    /** The instant this row was first persisted (UTC). */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** The actor that first persisted this row. */
    public String getCreatedBy() {
        return createdBy;
    }

    /** The instant this row was last updated (UTC). */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** The actor that last updated this row. */
    public String getUpdatedBy() {
        return updatedBy;
    }

    /** The optimistic-locking version counter. */
    public long getVersion() {
        return version;
    }

    /** The owning tenant ({@code company_id}). */
    public String getCompanyId() {
        return companyId;
    }

    /**
     * Sets the owning tenant. Intended to be called once, before first persist —
     * typically from the bound {@link TenantContext} at the application layer.
     * RLS rejects any attempt to write a row whose {@code company_id} does not
     * match the session tenant, so this cannot be used to smuggle a row into
     * another company.
     */
    public void setCompanyId(String companyId) {
        this.companyId = companyId;
    }
}
