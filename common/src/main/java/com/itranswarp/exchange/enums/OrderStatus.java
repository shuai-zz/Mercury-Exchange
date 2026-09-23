package com.itranswarp.exchange.enums;

/**
 * Define order status constants
 */
public enum OrderStatus {
    /**
     * Waiting to be filled (unfilledQuantity == quantity)
     */
    PENDING(false),

    /**
     * Fully filled (unfilledQuantity == 0)
     */
    FULLY_FILLED(true),

    /**
     * Partially filled (quantity > unfilledQuantity > 0)
     */
    PARTIAL_FILLED(false),

    /**
     * Canceled after being partially filled (quantity > unfilledQuantity > 0)
     */
    PARTIAL_CANCELED(true),

    /**
     * Canceled without any fill (unfilledQuantity == quantity)
     */
    FULLY_CANCELED(true);

    /**
     * Whether this status is terminal: a final order leaves the active-order maps,
     * gets its remaining frozen assets unfrozen, and is persisted as history.
     */
    public final boolean isFinalStatus;

    OrderStatus(boolean isFinalStatus) {
        this.isFinalStatus = isFinalStatus;
    }
}
