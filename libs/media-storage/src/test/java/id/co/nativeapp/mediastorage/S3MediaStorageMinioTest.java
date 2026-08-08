package id.co.nativeapp.mediastorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Round-trip proof against a REAL MinIO container (the same pinned image the dev/UAT stacks run):
 * put/get/delete through the actual S3 wire protocol, bytes and canonical content type preserved.
 * The fleet's singleton-container idiom ({@code PostgresRlsTestBase}), via {@link GenericContainer}
 * because Testcontainers 2.x ships no dedicated MinIO module.
 */
class S3MediaStorageMinioTest {

  private static final String MINIO_IMAGE = "minio/minio:RELEASE.2025-09-07T16-13-09Z";
  private static final String BUCKET = "native-media";

  @SuppressWarnings("resource") // singleton for the class, stopped in @AfterAll
  private static final GenericContainer<?> MINIO =
      new GenericContainer<>(MINIO_IMAGE)
          .withEnv("MINIO_ROOT_USER", "minioadmin")
          .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
          .withCommand("server", "/data")
          .withExposedPorts(9000)
          .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

  private static S3MediaStorage storage;

  @BeforeAll
  static void startMinioAndCreateBucket() {
    MINIO.start();
    S3Client s3 =
        S3Client.builder()
            .endpointOverride(
                URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000)))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("minioadmin", "minioadmin")))
            .forcePathStyle(true)
            .build();
    s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    storage = new S3MediaStorage(s3, BUCKET);
  }

  @AfterAll
  static void stopMinio() {
    if (storage != null) {
      storage.close();
    }
    MINIO.stop();
  }

  @Test
  void putGetRoundTripPreservesBytesAndContentType() {
    byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 42, 7, 0, 99};
    String key =
        MediaKeys.imageKey("restaurant", "test-company-1", "menu", Sha256.hex(jpeg), "image/jpeg");

    storage.put(key, jpeg, "image/jpeg");
    MediaStorage.StoredObject loaded = storage.get(key);

    assertThat(loaded.data()).isEqualTo(jpeg);
    assertThat(loaded.contentType()).isEqualTo("image/jpeg");
  }

  @Test
  void overwritingTheSameContentAddressedKeyIsIdempotent() {
    byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 5};
    String key =
        MediaKeys.imageKey("payment", "test-company-1", "qr", Sha256.hex(png), "image/png");

    storage.put(key, png, "image/png");
    storage.put(key, png, "image/png");

    assertThat(storage.get(key).data()).isEqualTo(png);
  }

  @Test
  void missingKeyThrowsNotFoundAndDeleteIsIdempotent() {
    byte[] webp = {'R', 'I', 'F', 'F', 1, 0, 0, 0, 'W', 'E', 'B', 'P', 3};
    String key =
        MediaKeys.imageKey("employee", "test-company-2", "receipt", Sha256.hex(webp), "image/webp");
    storage.put(key, webp, "image/webp");

    storage.delete(key);
    storage.delete(key); // deleting a missing key is a no-op (S3 semantics)

    assertThatThrownBy(() -> storage.get(key))
        .isInstanceOf(MediaObjectNotFoundException.class)
        .hasMessageContaining(key);
  }
}
