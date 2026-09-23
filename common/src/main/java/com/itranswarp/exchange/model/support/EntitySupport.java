package com.itranswarp.exchange.model.support;

/**
 * Define entity support
 * <p>
 * NOTE: precision vs scale, and why storage-side SCALE=18 differs from input-side AssetEnum.SCALE=2.
 * precision is the total number of digits (integer + fractional); scale is the digits after the
 * decimal point, so integer digits = precision - scale. DECIMAL(36, 18) means 18 integer digits
 * plus 18 fractional digits.
 * The ledger and order storage keep full precision: intermediate math (partial fills, fees) is
 * never rounded, so errors cannot accumulate and break reconciliation. SCALE=2 is an input-side
 * contract for user orders only (real exchanges configure tick size / step size per trading pair;
 * this project simplifies it to a constant for the single BTC/USD pair) and applies at the API
 * boundary, never inside the engine.
 */
public interface EntitySupport {

    /**
     * Default big decimal storage type: DECIMAL(PRECISION, SCALE)
     * Range = +/- 999999999999999999.999999999999999999
     */
    int PRECISION = 36;

    /**
     * Default big decimal storage scale. Minimum is 0.000000000000000001.
     */
    int SCALE = 18;

    int VAR_ENUM = 32;
    int VAR_CHAR_50 = 50;
    int VAR_CHAR_100 = 100;
    int VAR_CHAR_200 = 200;
    int VAR_CHAR_1000 = 1_000;
    int VAR_CHAR_10000 = 10_000;
}
