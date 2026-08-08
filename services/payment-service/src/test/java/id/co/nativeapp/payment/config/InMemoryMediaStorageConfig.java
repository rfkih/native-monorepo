package id.co.nativeapp.payment.config;

import id.co.nativeapp.mediastorage.MediaObjectNotFoundException;
import id.co.nativeapp.mediastorage.MediaStorage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Test-classpath {@link MediaStorage}: an in-memory map instead of a MinIO container (the
 * restaurant/employee-service idiom). Lives under the application's component-scan root on the TEST
 * classpath, so every {@code @SpringBootTest} context gets it automatically; {@code @Primary} makes
 * the auto-configured S3 client back off. The real S3 wire protocol is proven once in
 * libs/media-storage's own Testcontainers round-trip.
 */
@Configuration
public class InMemoryMediaStorageConfig {

  @Bean
  @Primary
  MediaStorage inMemoryMediaStorage() {
    return new MediaStorage() {
      private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();

      @Override
      public void put(String key, byte[] data, String contentType) {
        objects.put(key, new StoredObject(data, contentType));
      }

      @Override
      public StoredObject get(String key) {
        StoredObject stored = objects.get(key);
        if (stored == null) {
          throw new MediaObjectNotFoundException(key, null);
        }
        return stored;
      }

      @Override
      public void delete(String key) {
        objects.remove(key);
      }

      @Override
      public void close() {}
    };
  }
}
