package id.co.nativeapp.restaurant.channel.domain;

/**
 * Thrown when a sales channel is created with a {@code code} that already exists for the tenant
 * (the {@code uq_sales_channel_code (company_id, code)} constraint, V24). Mapped to {@code 409
 * Conflict} ({@code sales-channel-code-conflict}) by {@code config.ChannelAdvice}.
 */
public class SalesChannelCodeConflictException extends RuntimeException {

  private final String code;

  public SalesChannelCodeConflictException(String code) {
    super("A sales channel with code '" + code + "' already exists");
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
