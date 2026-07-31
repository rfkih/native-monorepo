package id.co.nativeapp.restaurant.selforderaccess.dto;

import java.util.List;

/**
 * The full self-order QR token set for one outlet: the currently-active {@code kid} (for display /
 * audit — never a secret), a minted token for each of the outlet's ACTIVE tables, and a kiosk token
 * (no table — {@code tableLabel = null}).
 *
 * @param kid the currently-active {@code self_order_access} row's short id
 * @param kioskToken a token with no table claim, for a stand-alone ordering kiosk
 * @param tables one minted token per active table
 */
public record SelfOrderAccessResponse(
    String kid, String kioskToken, List<SelfOrderTableToken> tables) {}
