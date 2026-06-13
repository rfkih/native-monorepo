package id.co.nativeapp.money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency IDR = Currency.getInstance("IDR");
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Locale ID_ID = Locale.of("id", "ID");

    // ------------------------------------------------------------------
    // Factories: minor units
    // ------------------------------------------------------------------

    @Test
    void ofMinorByCode() {
        Money m = Money.ofMinor(12_345L, "USD");
        assertEquals(12_345L, m.amountMinor());
        assertEquals(USD, m.currency());
    }

    @Test
    void ofMinorByCurrency() {
        Money m = Money.ofMinor(-50L, IDR);
        assertEquals(-50L, m.amountMinor());
        assertEquals(IDR, m.currency());
    }

    @Test
    void ofMinorRejectsUnknownCurrencyCode() {
        assertThrows(IllegalArgumentException.class, () -> Money.ofMinor(1L, "ZZZ"));
    }

    @Test
    void zeroIsZeroInGivenCurrency() {
        Money z = Money.zero(USD);
        assertTrue(z.isZero());
        assertEquals(0L, z.amountMinor());
        assertEquals(USD, z.currency());
    }

    // ------------------------------------------------------------------
    // Factories: major units & decimal-scale rejection (the "no float" guard)
    // ------------------------------------------------------------------

    @Test
    void ofMajorScalesByCurrencyFractionDigits() {
        // USD has 2 fraction digits: 12.34 major -> 1234 minor.
        assertEquals(1_234L, Money.ofMajor(new BigDecimal("12.34"), USD).amountMinor());
        // Trailing-zero exactness: 1.50 USD -> 150 minor.
        assertEquals(150L, Money.ofMajor(new BigDecimal("1.50"), USD).amountMinor());
        // Integer major with no decimals is fine.
        assertEquals(700L, Money.ofMajor(new BigDecimal("7"), USD).amountMinor());
    }

    @Test
    void ofMajorAcceptsWholeRupiah() {
        // IDR resolves to 0 fraction digits, so whole-rupiah majors map 1:1.
        assertEquals(1_234_567L,
                Money.ofMajor(new BigDecimal("1234567"), IDR).amountMinor());
    }

    @Test
    void ofMajorRejectsTooManyDecimalsForZeroFractionCurrency() {
        // IDR allows 0 decimals; 1.50 must be rejected.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Money.ofMajor(new BigDecimal("1.50"), IDR));
        assertTrue(ex.getMessage().contains("IDR"));
    }

    @Test
    void ofMajorRejectsTooManyDecimalsForTwoFractionCurrency() {
        // USD allows 2 decimals; 1.005 must be rejected (would silently lose 0.005).
        assertThrows(IllegalArgumentException.class,
                () -> Money.ofMajor(new BigDecimal("1.005"), USD));
    }

    @Test
    void ofMajorRejectsAmountTooLargeForLong() {
        // The scaled minor value (major x 100 for USD) must not silently wrap a long;
        // an overflow is reported as IllegalArgumentException (the conversion guard).
        BigDecimal huge = new BigDecimal("100000000000000000.00"); // 1e17 -> 1e19 minor > Long.MAX
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Money.ofMajor(huge, USD));
        assertTrue(ex.getMessage().contains("USD"));
    }

    @Test
    void fractionDigitsOverridesIdrToZeroForIntlAlignment() {
        // The JDK's ISO-4217 table reports IDR=2, but CLDR / Intl.NumberFormat (and the React
        // frontend) treat rupiah as 0-decimal. Money.fractionDigits must apply that override so
        // ofMajor validation and format() stay aligned with the UI. Asserting it directly means
        // a future JDK that closes this gap is caught here rather than via a formatting surprise.
        assertEquals(2, IDR.getDefaultFractionDigits(), "JDK ISO-4217 table still reports IDR=2");
        assertEquals(0, Money.fractionDigits(IDR), "Money must override IDR to 0 (CLDR/Intl)");
        assertEquals(2, Money.fractionDigits(USD), "currencies matching CLDR pass through");
    }

    // ------------------------------------------------------------------
    // Arithmetic
    // ------------------------------------------------------------------

    @Test
    void plusAddsSameCurrency() {
        Money sum = Money.ofMinor(1_000L, USD).plus(Money.ofMinor(250L, USD));
        assertEquals(Money.ofMinor(1_250L, USD), sum);
    }

    @Test
    void minusSubtractsSameCurrency() {
        Money diff = Money.ofMinor(1_000L, USD).minus(Money.ofMinor(250L, USD));
        assertEquals(Money.ofMinor(750L, USD), diff);
    }

    @Test
    void negateFlipsSign() {
        assertEquals(Money.ofMinor(-750L, USD), Money.ofMinor(750L, USD).negate());
        assertEquals(Money.ofMinor(750L, USD), Money.ofMinor(-750L, USD).negate());
    }

    @Test
    void multiplyByFactor() {
        assertEquals(Money.ofMinor(3_000L, USD), Money.ofMinor(1_000L, USD).multiply(3L));
        assertEquals(Money.zero(USD), Money.ofMinor(1_000L, USD).multiply(0L));
        assertEquals(Money.ofMinor(-2_000L, USD), Money.ofMinor(1_000L, USD).multiply(-2L));
    }

    @Test
    void plusRejectsMixedCurrencies() {
        Money usd = Money.ofMinor(1_000L, USD);
        Money eur = Money.ofMinor(1_000L, EUR);
        MismatchedCurrencyException ex =
                assertThrows(MismatchedCurrencyException.class, () -> usd.plus(eur));
        assertEquals(USD, ex.left());
        assertEquals(EUR, ex.right());
    }

    @Test
    void minusRejectsMixedCurrencies() {
        Money usd = Money.ofMinor(1_000L, USD);
        Money idr = Money.ofMinor(1_000L, IDR);
        assertThrows(MismatchedCurrencyException.class, () -> usd.minus(idr));
    }

    @Test
    void compareToRejectsMixedCurrencies() {
        Money usd = Money.ofMinor(1_000L, USD);
        Money eur = Money.ofMinor(1_000L, EUR);
        assertThrows(MismatchedCurrencyException.class, () -> usd.compareTo(eur));
    }

    @Test
    void overflowingPlusThrows() {
        Money max = Money.ofMinor(Long.MAX_VALUE, USD);
        assertThrows(ArithmeticException.class, () -> max.plus(Money.ofMinor(1L, USD)));
    }

    // ------------------------------------------------------------------
    // Predicates & comparison
    // ------------------------------------------------------------------

    @Test
    void predicates() {
        assertTrue(Money.ofMinor(5L, USD).isPositive());
        assertFalse(Money.ofMinor(5L, USD).isNegative());
        assertFalse(Money.ofMinor(5L, USD).isZero());

        assertTrue(Money.ofMinor(-5L, USD).isNegative());
        assertFalse(Money.ofMinor(-5L, USD).isPositive());

        assertTrue(Money.zero(USD).isZero());
        assertFalse(Money.zero(USD).isPositive());
        assertFalse(Money.zero(USD).isNegative());
    }

    @Test
    void compareToOrdersSameCurrency() {
        Money small = Money.ofMinor(100L, USD);
        Money big = Money.ofMinor(200L, USD);
        assertTrue(small.compareTo(big) < 0);
        assertTrue(big.compareTo(small) > 0);
        assertEquals(0, small.compareTo(Money.ofMinor(100L, USD)));
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    @Test
    void equalsAndHashCodeAreValueBased() {
        Money a = Money.ofMinor(1_000L, USD);
        Money b = Money.ofMinor(1_000L, USD);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        assertNotEquals(a, Money.ofMinor(1_001L, USD)); // different amount
        assertNotEquals(a, Money.ofMinor(1_000L, EUR)); // different currency
        assertNotEquals(a, null);
        assertNotEquals(a, "1000 USD");
    }

    @Test
    void toStringIsReadable() {
        assertEquals("123450 USD", Money.ofMinor(123_450L, USD).toString());
        assertEquals("0 IDR", Money.zero(IDR).toString());
    }

    // ------------------------------------------------------------------
    // Formatting (must match Intl.NumberFormat output)
    // ------------------------------------------------------------------

    @Test
    void formatUsdInUsLocale() {
        // 123450 minor USD = 1234.50 major.
        String out = Money.ofMinor(123_450L, USD).format(Locale.US);
        assertEquals("$1,234.50", out);
    }

    @Test
    void formatIdrInIndonesianLocale() {
        // IDR is treated as 0 fraction digits (CLDR/Intl): 1234567 minor =
        // 1234567 major, grouped with '.', symbol "Rp", NO decimal part.
        // Matches Intl.NumberFormat('id-ID', {style:'currency', currency:'IDR'}).
        String out = Money.ofMinor(1_234_567L, IDR).format(ID_ID);
        assertEquals("Rp1.234.567", out);
    }

    @Test
    void formatIdrHasNoDecimalPart() {
        // 1500 minor IDR formats as Rp1.500, never Rp1.500,00.
        String out = Money.ofMinor(1_500L, IDR).format(ID_ID);
        assertEquals("Rp1.500", out);
        assertFalse(out.contains(","), () -> "IDR must not carry a decimal part: " + out);
    }

    // ------------------------------------------------------------------
    // Immutability / no-float contract (reflection guards)
    // ------------------------------------------------------------------

    @Test
    void classIsFinal() {
        assertTrue(Modifier.isFinal(Money.class.getModifiers()),
                "Money must be final");
    }

    @Test
    void allInstanceFieldsAreFinal() {
        for (Field f : Money.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                assertTrue(Modifier.isFinal(f.getModifiers()),
                        () -> "Field must be final: " + f.getName());
            }
        }
    }

    @Test
    void exposesNoFloatingPointConstructorOrFactory() {
        // No public constructor at all (only static factories).
        for (Constructor<?> c : Money.class.getConstructors()) {
            assertFalse(Modifier.isPublic(c.getModifiers()),
                    "Money must not expose a public constructor");
        }
        // No public method accepts or returns a float/double.
        for (Method m : Money.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            for (Class<?> p : m.getParameterTypes()) {
                assertNotEquals(double.class, p,
                        () -> "Method must not accept double: " + m.getName());
                assertNotEquals(float.class, p,
                        () -> "Method must not accept float: " + m.getName());
            }
            assertNotEquals(double.class, m.getReturnType(),
                    () -> "Method must not return double: " + m.getName());
            assertNotEquals(float.class, m.getReturnType(),
                    () -> "Method must not return float: " + m.getName());
        }
    }

    @Test
    void zeroReturnsEqualValues() {
        Money z1 = Money.zero(USD);
        Money z2 = Money.zero(USD);
        assertEquals(z1, z2);
        assertSame(z1.getClass(), z2.getClass());
        assertEquals(z1, z1.plus(Money.zero(USD)));
    }
}
