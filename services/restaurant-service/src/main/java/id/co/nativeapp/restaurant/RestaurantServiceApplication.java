package id.co.nativeapp.restaurant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for restaurant-service (M1.4) — the first vertical and the
 * validation-slice goal: record a sale, emit {@code SaleRecorded} via the outbox.
 *
 * <p>The cross-cutting persistence wiring (Spring Data JPA auditing from
 * {@code libs/tenant} and the auto-RLS beans/aspect) lives in
 * {@link PersistenceConfig} rather than on this bootstrap class. Keeping it off
 * the {@code @SpringBootApplication} class means web slice tests
 * ({@code @WebMvcTest}) do not drag in JPA infrastructure they have no datasource
 * for, while the full application still loads it via component scanning.
 */
@SpringBootApplication
public class RestaurantServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RestaurantServiceApplication.class, args);
    }
}
