package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.restaurant.payment.projection.PaymentView;
import id.co.nativeapp.restaurant.payment.repository.PaymentRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A tiny RLS-safe read used ONLY to DISPATCH a payment id to the right capture writer (order vs
 * bill, V38) from {@link PaymentCaptureService#capture}.
 *
 * <p>A distinct bean — not a private method, and not an inline repository call, on {@link
 * PaymentCaptureService} — because {@code PaymentCaptureService} is deliberately NOT itself
 * {@code @Transactional} (its javadoc), so a repository call made directly inside one of its
 * methods carries NO {@code @Transactional} boundary of its own: {@link RlsAutoApplyAspect} only
 * sets the {@code app.current_tenant} GUC around a method it can intercept (a real
 * {@code @Transactional} method on a Spring-proxied bean), and self-invocation — or, as here, a
 * bare repository call with no surrounding boundary at all — bypasses that advice entirely, so the
 * row-level-security policy sees no tenant GUC and fails CLOSED (an empty read, not an error) —
 * exactly the trap {@code docs/adr}'s "RLS GUC is @Transactional-only" note describes. Routing the
 * read through this separate, properly {@code @Transactional} bean makes the Spring AOP proxy
 * engage for real.
 */
@Component
public class PaymentDispatchReader {

  private final PaymentRepository repository;

  public PaymentDispatchReader(PaymentRepository repository) {
    this.repository = repository;
  }

  /**
   * The narrow read-path view for a payment, RLS-scoped to the current tenant — used solely to
   * decide whether {@link PaymentCaptureService#capture}/{@code #abandon} should dispatch to the
   * bill-only or order-only writer.
   */
  @Transactional(readOnly = true)
  public Optional<PaymentView> findView(UUID paymentId) {
    return repository.findViewById(paymentId);
  }
}
