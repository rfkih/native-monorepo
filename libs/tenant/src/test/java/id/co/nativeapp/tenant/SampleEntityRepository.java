package id.co.nativeapp.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

/** Test-only repository for {@link SampleEntity}. */
interface SampleEntityRepository extends JpaRepository<SampleEntity, Long> {
}
