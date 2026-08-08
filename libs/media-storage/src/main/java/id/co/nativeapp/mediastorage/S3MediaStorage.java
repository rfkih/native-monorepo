package id.co.nativeapp.mediastorage;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * {@link MediaStorage} over the generic S3 API (AWS SDK v2) — MinIO in every environment today, any
 * S3-compatible store tomorrow (ADR 0048).
 *
 * <p><strong>Path-style access</strong> is forced: MinIO's default addressing puts the bucket in
 * the PATH ({@code http://minio:9000/native-media/key}), not a virtual subdomain — virtual-host
 * style would demand per-bucket DNS games no compose network plays.
 *
 * <p>Whole-object bytes in memory (no streaming): every media surface caps uploads at ≤ 5 MiB at
 * the edge, so buffering is simpler and safe. Revisit only if a surface ever accepts large files.
 */
public final class S3MediaStorage implements MediaStorage {

  private final S3Client s3;
  private final String bucket;

  public S3MediaStorage(MediaStorageProperties properties) {
    this(
        S3Client.builder()
            .endpointOverride(properties.endpoint())
            .region(Region.of(properties.region()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
            .forcePathStyle(true)
            .build(),
        properties.bucket());
  }

  /** Test seam: inject a raw client (the MinIO Testcontainers round-trip builds its own). */
  S3MediaStorage(S3Client s3, String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  @Override
  public void put(String key, byte[] data, String contentType) {
    s3.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
        RequestBody.fromBytes(data));
  }

  @Override
  public StoredObject get(String key) {
    try {
      ResponseBytes<GetObjectResponse> bytes =
          s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build());
      return new StoredObject(bytes.asByteArray(), bytes.response().contentType());
    } catch (NoSuchKeyException e) {
      throw new MediaObjectNotFoundException(key, e);
    }
  }

  @Override
  public void delete(String key) {
    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
  }

  @Override
  public void close() {
    s3.close();
  }
}
