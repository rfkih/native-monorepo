package id.co.nativeapp.tenant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A minimal {@link Auditable} entity used only by the audit-column test. It adds
 * a single business column on top of the inherited audit + tenancy columns so we
 * can verify that persisting through Spring Data JPA auditing populates
 * {@code created_at}/{@code created_by} (and that {@code company_id} survives the
 * round trip).
 */
@Entity
@Table(name = "sample_entity")
public class SampleEntity extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    protected SampleEntity() {
        // for JPA
    }

    public SampleEntity(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
