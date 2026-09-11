package id.co.nativeapp.finance.config;

import id.co.nativeapp.mediastorage.MediaObjectNotFoundException;
import id.co.nativeapp.mediastorage.MediaStorage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Test-only {@link MediaStorage}: an in-memory map standing in for MinIO, so the attachment
 * writer/reader run in every {@code @SpringBootTest} without an object store (the restaurant
 * precedent). {@code @Primary} wins over the auto-configured S3 client.
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
