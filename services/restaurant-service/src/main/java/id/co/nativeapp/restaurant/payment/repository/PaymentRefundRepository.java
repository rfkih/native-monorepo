package id.co.nativeapp.restaurant.payment.repository;

import id.co.nativeapp.restaurant.payment.domain.PaymentRefund;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Append-only refund-event ledger (V22, ADR 0036 review C3). Write path only — {@code
 * VoidRefundWriter} appends one row per refund; the register close reads it via its own native
 * window query. RLS scopes every access (rule 5).
 */
public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, UUID> {}
