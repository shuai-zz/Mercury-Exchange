package com.itranswarp.exchange.match;

import com.itranswarp.exchange.bean.OrderBookItemBean;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.model.trade.OrderEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class OrderBook {
    public final Direction direction;
    public final TreeMap<OrderKey, OrderEntity> book;

    public OrderBook(Direction direction) {
        this.direction = direction;
        this.book = new TreeMap<>(direction == Direction.BUY
                ? SORT_BUY : SORT_SELL);
    }

    public OrderEntity getFirst() {
        return this.book.isEmpty() ? null : this.book.firstEntry().getValue();
    }

    public boolean remove(OrderEntity order) {
        return this.book.remove(new OrderKey(order.sequenceId, order.price)) != null;
    }

    public boolean add(OrderEntity order) {
        return this.book.put(new OrderKey(order.sequenceId, order.price), order) == null;
    }

    public boolean exist(OrderEntity order){
        return this.book.containsKey(new OrderKey(order.sequenceId, order.price));
    }

    public int size(){
        return this.book.size();
    }

    /**
     * Aggregate open orders into price levels for order book display (e.g. market depth).
     * Iteration follows this book's comparator, so levels come out best-price-first.
     * Orders sharing a price are merged into one level with summed unfilledQuantity;
     * at most maxDepth levels are returned. Read-only: the underlying book is not modified.
     */
    public List<OrderBookItemBean> getOrderBook(int maxDepth) {
        List<OrderBookItemBean> items = new ArrayList<>(maxDepth);
        // current price level being accumulated
        OrderBookItemBean prevItem = null;
        for (OrderKey key : this.book.keySet()) {
            OrderEntity order = this.book.get(key);
            if (prevItem == null) {
                prevItem = new OrderBookItemBean(order.price, order.unfilledQuantity);
                items.add(prevItem);
            } else {
                if (prevItem.price.compareTo(order.price) == 0) {
                    // same price: merge into the current level
                    prevItem.addQuantity(order.unfilledQuantity);
                } else {
                    // new price: stop once maxDepth levels are filled
                    if (items.size() >= maxDepth) {
                        break;
                    }
                    prevItem = new OrderBookItemBean(order.price, order.unfilledQuantity);
                    items.add(prevItem);
                }
            }
        }
        return items;
    }

    /**
     * Render the book like an exchange depth display: best price first for BUY,
     * but reversed for SELL so the best ask sits on the last line, right above
     * the spread when both books are printed together.
     */
    @Override
    public String toString() {
        if (this.book.isEmpty()) {
            return this.direction.name() + " book: (empty)";
        }
        List<String> lines = new ArrayList<>(this.book.size());
        for (Map.Entry<OrderKey, OrderEntity> entry : this.book.entrySet()) {
            OrderEntity order = entry.getValue();
            lines.add("  " + order.price + " x " + order.unfilledQuantity + " (seq=" + order.sequenceId + ")");
        }
        if (this.direction == Direction.SELL) {
            Collections.reverse(lines);
        }
        return this.direction.name() + " book:\n" + String.join("\n", lines);
    }

    // lowest price first
    // same price: smaller sequenceId (earlier order) first
    private static final Comparator<OrderKey> SORT_SELL = Comparator.comparing(OrderKey::price).thenComparingLong(OrderKey::sequenceId);

    // highest price first
    // same price: smaller sequenceId (earlier order) first
    private static final Comparator<OrderKey> SORT_BUY =
            Comparator.comparing(OrderKey::price).reversed().thenComparingLong(OrderKey::sequenceId);

}
