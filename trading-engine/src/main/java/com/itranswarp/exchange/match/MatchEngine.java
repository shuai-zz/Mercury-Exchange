package com.itranswarp.exchange.match;

import com.itranswarp.exchange.bean.OrderBookBean;
import com.itranswarp.exchange.bean.OrderBookItemBean;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.enums.OrderStatus;
import com.itranswarp.exchange.model.trade.OrderEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class MatchEngine {

    public final OrderBook buyBook = new OrderBook(Direction.BUY);
    public final OrderBook sellBook = new OrderBook(Direction.SELL);
    // latest market price
    public BigDecimal marketPrice = BigDecimal.ZERO;
    private long sequenceId;

    public MatchResult processOrder(long sequenceId, OrderEntity order) {
        return switch (order.direction) {
            case BUY -> processOrder(sequenceId, order, this.sellBook, this.buyBook);
            case SELL -> processOrder(sequenceId, order, this.buyBook, this.sellBook);
            default -> throw new IllegalArgumentException("Invalid direction.");
        };
    }

    /**
     * Matches a taker order against the opposite side of the order book.
     *
     * @param sequenceId  the sequence ID assigned to this order event
     * @param takerOrder  the incoming order that initiates matching
     * @param makerBook   the opposite-side order book containing potential makers
     * @param anotherBook the taker's own order book, used for any unfilled remainder
     * @return the result containing all matches produced for the taker order
     */
    private MatchResult processOrder(long sequenceId, OrderEntity takerOrder,
                                     OrderBook makerBook,
                                     OrderBook anotherBook) {
        this.sequenceId = sequenceId;
        long ts = takerOrder.createdAt;
        MatchResult matchResult = new MatchResult(takerOrder);
        BigDecimal takerUnfilledQuantity = takerOrder.quantity;

        for (; ; ) {
            // Always match against the best available maker first.
            OrderEntity makerOrder = makerBook.getFirst();
            if (makerOrder == null) {
                // Stop when the opposite side of the order book is empty.
                break;
            }
            if (takerOrder.direction == Direction.BUY && takerOrder.price.compareTo(makerOrder.price) < 0) {
                // A buy order cannot match when its price is below the best ask.
                break;
            } else if (takerOrder.direction == Direction.SELL && takerOrder.price.compareTo(makerOrder.price) > 0) {
                // A sell order cannot match when its price is above the best bid.
                break;
            }

            // Execute at the maker's price and update the latest market price.
            this.marketPrice = makerOrder.price;
            // Match only the quantity that both sides can fill.
            BigDecimal matchedQuantity = takerUnfilledQuantity.min(makerOrder.unfilledQuantity);
            // Record the execution against the current maker.
            matchResult.add(makerOrder.price, matchedQuantity, makerOrder);
            // Reduce both sides' unfilled quantities by the executed amount.
            takerUnfilledQuantity = takerUnfilledQuantity.subtract(matchedQuantity);
            BigDecimal makerUnfilledQuantity = makerOrder.unfilledQuantity.subtract(matchedQuantity);
            // Remove a fully filled maker; otherwise, keep its remainder in the book.
            if (makerUnfilledQuantity.signum() == 0) {
                makerOrder.updateOrder(makerUnfilledQuantity, OrderStatus.FULLY_FILLED, ts);
                makerBook.remove(makerOrder);
            } else {
                makerOrder.updateOrder(makerUnfilledQuantity, OrderStatus.PARTIAL_FILLED, ts);
            }

            // Stop as soon as the taker is fully filled.
            if (takerUnfilledQuantity.signum() == 0) {
                takerOrder.updateOrder(takerUnfilledQuantity, OrderStatus.FULLY_FILLED, ts);
                break;
            }
        }
        // After matching stops, add any taker remainder to its own side of the book.
        if (takerUnfilledQuantity.signum() > 0) {
            takerOrder.updateOrder(takerUnfilledQuantity,
                    takerUnfilledQuantity.compareTo(takerOrder.quantity) == 0 ? OrderStatus.PENDING
                            : OrderStatus.PARTIAL_FILLED,
                    ts);
            anotherBook.add(takerOrder);
        }
        return matchResult;
    }

    /**
     * Cancels an open order by removing it from its side of the order book.
     * Previously executed quantities remain unchanged.
     *
     * @param ts the cancellation timestamp
     * @param order the open order to cancel
     * @throws IllegalArgumentException if the order is not present in the order book
     */
    public void cancel(long ts, OrderEntity order) {
        OrderBook book = order.direction == Direction.BUY ? this.buyBook : this.sellBook;
        if (!book.remove(order)) {
            throw new IllegalArgumentException("Order not found in order book");
        }
        // Distinguish an unfilled order from one that was partially executed before cancellation.
        OrderStatus status = order.unfilledQuantity.compareTo(order.quantity) == 0 ? OrderStatus.FULLY_CANCELED : OrderStatus.PARTIAL_CANCELED;
        order.updateOrder(order.unfilledQuantity, status, ts);
    }

    /**
     * Creates a read-only snapshot of aggregated bid and ask price levels.
     * The snapshot also includes the latest sequence ID and market price.
     *
     * @param maxDepth the maximum number of price levels returned on each side
     * @return the order book snapshot for market data queries or publication
     */
    public OrderBookBean getOrderBook(int maxDepth) {
        return new OrderBookBean(this.sequenceId, this.marketPrice, this.buyBook.getOrderBook(maxDepth), this.sellBook.getOrderBook(maxDepth));
    }

    public void debug() {
        System.out.println("---------- match engine ----------");
        System.out.println(this.sellBook);
        System.out.println("  ----------");
        System.out.println("  " + this.marketPrice);
        System.out.println("  ----------");
        System.out.println(this.buyBook);
        System.out.println("---------- // match engine ----------");
    }


}
