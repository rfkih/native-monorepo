package id.co.nativeapp.barbershop.ticket.service;

import id.co.nativeapp.barbershop.catalog.projection.CatalogItemView;
import id.co.nativeapp.barbershop.catalog.repository.ServiceAddonRepository;
import id.co.nativeapp.barbershop.catalog.repository.ServiceItemRepository;
import id.co.nativeapp.barbershop.ticket.domain.ItemType;
import id.co.nativeapp.barbershop.ticket.domain.MixedCurrencyException;
import id.co.nativeapp.barbershop.ticket.dto.TicketLineInput;
import id.co.nativeapp.money.Money;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Shared catalog item resolution + pricing helper used by BOTH {@link TicketWriter} (checkout /
 * capture) and {@link TicketReader} (quote) so they cannot diverge — mirrors carwash-service's
 * {@code TicketItemReader}. Named {@code *Reader} (rather than e.g. {@code *Resolver}) specifically
 * so it satisfies the {@code LayeredArchitectureTest.repositoriesAreAccessedOnlyByTheServiceLayer}
 * naming convention — every class that depends on a repository must be a {@code *Service}/{@code
 * *Writer}/{@code *Reader} (or another repository).
 *
 * <p>For a requested cart, this component:
 *
 * <ol>
 *   <li>Batch-loads the referenced {@code service_item}/{@code service_addon} rows (RLS-scoped,
 *       chunked to &le; 1000 ids — CLAUDE.md IN-clause convention);
 *   <li>Validates every line's item exists, is {@code active}, and belongs to the request's {@code
 *       businessId} (400 otherwise — {@link IllegalArgumentException});
 *   <li>Enforces currency homogeneity across every resolved item ({@link MixedCurrencyException} →
 *       422 otherwise);
 *   <li>Computes each line's total and the cart subtotal via {@link Money} arithmetic
 *       (overflow-safe, integer minor units — never a float, rule 8).
 * </ol>
 *
 * <p><strong>Deliberate difference from carwash-service.</strong> carwash's equivalent also
 * accumulates an addon-only total (feeding the {@code upsell_amount} metric). barbershop declares
 * no such metric (ADR 0024), so {@link ResolvedCart} carries no addon total — nothing to compute.
 *
 * <p>Stateless: the bean carries only repository references. Never trusts a client-supplied amount
 * — every price comes from the resolved catalog row.
 */
@Component
public class TicketItemReader {

  private final ServiceItemRepository serviceItemRepository;
  private final ServiceAddonRepository addonRepository;

  public TicketItemReader(
      ServiceItemRepository serviceItemRepository, ServiceAddonRepository addonRepository) {
    this.serviceItemRepository = serviceItemRepository;
    this.addonRepository = addonRepository;
  }

  /**
   * Resolves + validates a requested cart against {@code businessId}, returning the priced lines,
   * the shared currency code, and the subtotal (Σ line totals).
   *
   * @throws IllegalArgumentException if any item is unknown, inactive, or belongs to another
   *     business (→ 400)
   * @throws MixedCurrencyException if the resolved items span more than one currency (→ 422)
   */
  public ResolvedCart resolve(UUID businessId, List<TicketLineInput> lines) {
    List<UUID> serviceIds =
        lines.stream()
            .filter(l -> l.itemType() == ItemType.SERVICE)
            .map(TicketLineInput::itemId)
            .distinct()
            .toList();
    List<UUID> addonIds =
        lines.stream()
            .filter(l -> l.itemType() == ItemType.ADDON)
            .map(TicketLineInput::itemId)
            .distinct()
            .toList();

    Map<UUID, CatalogItemView> serviceMap =
        loadChunked(serviceIds, serviceItemRepository::findViewsByIds);
    Map<UUID, CatalogItemView> addonMap = loadChunked(addonIds, addonRepository::findViewsByIds);

    List<ResolvedLine> resolved = new ArrayList<>(lines.size());
    Set<String> currencies = new LinkedHashSet<>();
    for (TicketLineInput line : lines) {
      Map<UUID, CatalogItemView> map = line.itemType() == ItemType.SERVICE ? serviceMap : addonMap;
      CatalogItemView view = map.get(line.itemId());
      if (view == null) {
        throw new IllegalArgumentException(
            "Catalog item not found: " + line.itemType() + " " + line.itemId());
      }
      if (!view.isActive()) {
        throw new IllegalArgumentException(
            "Catalog item is inactive: " + line.itemType() + " " + line.itemId());
      }
      if (!businessId.equals(view.getBusinessId())) {
        throw new IllegalArgumentException(
            "Catalog item "
                + line.itemId()
                + " belongs to business "
                + view.getBusinessId()
                + ", not "
                + businessId);
      }
      String currencyCode = view.getCurrency().strip();
      currencies.add(currencyCode);
      resolved.add(
          new ResolvedLine(
              line.itemType(),
              line.itemId(),
              view.getName(),
              view.getPriceMinor(),
              currencyCode,
              line.qty()));
    }

    if (currencies.size() > 1) {
      throw new MixedCurrencyException(currencies);
    }
    String currencyCode = currencies.iterator().next();
    Currency currency = Currency.getInstance(currencyCode);

    Money subtotal = Money.zero(currency);
    for (ResolvedLine rl : resolved) {
      Money unit = Money.ofMinor(rl.priceMinor(), currencyCode);
      Money lineTotal = unit.multiply(rl.qty());
      subtotal = subtotal.plus(lineTotal);
    }

    return new ResolvedCart(currencyCode, resolved, subtotal);
  }

  private Map<UUID, CatalogItemView> loadChunked(
      List<UUID> ids, java.util.function.Function<List<UUID>, List<CatalogItemView>> loader) {
    Map<UUID, CatalogItemView> map = new HashMap<>();
    for (int i = 0; i < ids.size(); i += 1000) {
      List<UUID> chunk = ids.subList(i, Math.min(i + 1000, ids.size()));
      for (CatalogItemView view : loader.apply(chunk)) {
        map.put(view.getId(), view);
      }
    }
    return map;
  }

  /** One resolved, priced line (server-side unit price snapshot). */
  public record ResolvedLine(
      ItemType itemType, UUID itemId, String name, long priceMinor, String currency, int qty) {}

  /** The fully resolved + priced cart. */
  public record ResolvedCart(String currencyCode, List<ResolvedLine> lines, Money subtotal) {}
}
