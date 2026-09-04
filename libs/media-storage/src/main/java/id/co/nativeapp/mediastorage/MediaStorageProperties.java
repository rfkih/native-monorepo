package id.co.nativeapp.mediastorage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Object-store knobs ({@code native.media.*} — externalized, ENGINEERING-STANDARDS §7). Bound by
 * {@link MediaStorageAutoConfiguration}; the required knobs fail fast at startup so a service that
 * depends on media storage can never boot half-wired.
 *
 * @param endpoint the S3 endpoint (dev/UAT: the MinIO container — dev {@code
 *     http://localhost:9000}, compose networks {@code http://minio:9000})
 * @param accessKey the service's OWN prefix-scoped access key (provisioned by {@code
 *     docker/minio/init.sh} — never the MinIO root credential)
 * @param secretKey the secret for {@code accessKey}
 * @param bucket the single per-environment bucket (default {@code native-media})
 * @param servicePrefix the owning service's fixed key prefix ({@code restaurant}, {@code employee},
 *     {@code payment}) — MUST match the prefix the service's credentials are policy-scoped to
 * @param publicBaseUrl OPTIONAL — the public base the gateway serves anonymous media under (e.g.
 *     {@code https://…/api/media}); only set by services whose media are publicly served
 *     (restaurant menu images). Services that stream media through authenticated endpoints
 *     (receipts, QR) leave it unset.
 * @param region S3 region name — MinIO ignores it but the SDK requires one; default {@code
 *     us-east-1}
 * @param connectTimeout TCP connect timeout for the S3 client (§4 Resilience: every outbound client
 *     owns explicit timeouts — the SDK's 30s-with-retries default let a hung MinIO pin callers for
 *     minutes; flaw-audit C1)
 * @param socketTimeout per-read socket timeout for the S3 client
 * @param apiCallTimeout HARD ceiling on one whole S3 call including SDK retries — the figure that
 *     actually bounds how long a serve path can stall on a sick object store
 */
@Validated
@ConfigurationProperties("native.media")
public record MediaStorageProperties(
    @NotNull URI endpoint,
    @NotBlank String accessKey,
    @NotBlank String secretKey,
    @DefaultValue("native-media") @NotBlank String bucket,
    @NotBlank String servicePrefix,
    String publicBaseUrl,
    @DefaultValue("us-east-1") @NotBlank String region,
    @DefaultValue("2s") @NotNull Duration connectTimeout,
    @DefaultValue("10s") @NotNull Duration socketTimeout,
    @DefaultValue("15s") @NotNull Duration apiCallTimeout) {

  /**
   * The public URL for an object key, for services that embed media URLs in API responses.
   *
   * @throws IllegalStateException if {@code public-base-url} is not configured — calling this from
   *     a service that never set it is a wiring bug, not a runtime condition to tolerate
   */
  public String publicUrlFor(String key) {
    if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
      throw new IllegalStateException(
          "native.media.public-base-url is not configured but a public media URL was requested");
    }
    return publicBaseUrl.endsWith("/") ? publicBaseUrl + key : publicBaseUrl + "/" + key;
  }
}
