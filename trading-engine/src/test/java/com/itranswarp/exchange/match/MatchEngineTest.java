package com.itranswarp.exchange.match;

import com.itranswarp.exchange.bean.OrderBookBean;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.enums.OrderStatus;
import com.itranswarp.exchange.model.trade.OrderEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MatchEngineTest {

    private static final long USER_A = 12345L;

    private long sequenceId;
    private MatchEngine engine;

    @BeforeEach
    void setUp() {
        this.engine = new MatchEngine();
    }

    @Test
    void processOrders() {
        List<OrderEntity> orders = List.of(
                createOrder(Direction.BUY, "12300.21", "1.02"),
                createOrder(Direction.BUY, "12305.39", "0.33"),
                createOrder(Direction.SELL, "12305.39", "0.11"),
                createOrder(Direction.SELL, "12300.01", "0.33"),
                createOrder(Direction.SELL, "12400.00", "0.10"),
                createOrder(Direction.SELL, "12400.00", "0.20"),
                createOrder(Direction.SELL, "12390.00", "0.15"),
                createOrder(Direction.BUY, "12400.01", "0.55"),
                createOrder(Direction.BUY, "12300.00", "0.77"));
        List<MatchDetailRecord> matches = new ArrayList<>();
        for (OrderEntity order : orders) {
            MatchResult result = this.engine.processOrder(order.sequenceId, order);
            matches.addAll(result.matchDetails);
        }

        assertArrayEquals(new MatchDetailRecord[]{
                new MatchDetailRecord(bd("12305.39"), bd("0.11"), orders.get(2), orders.get(1)),
                new MatchDetailRecord(bd("12305.39"), bd("0.22"), orders.get(3), orders.get(1)),
                new MatchDetailRecord(bd("12300.21"), bd("0.11"), orders.get(3), orders.get(0)),
                new MatchDetailRecord(bd("12390.00"), bd("0.15"), orders.get(7), orders.get(6)),
                new MatchDetailRecord(bd("12400.00"), bd("0.10"), orders.get(7), orders.get(4)),
                new MatchDetailRecord(bd("12400.00"), bd("0.20"), orders.get(7), orders.get(5))
        }, matches.toArray(MatchDetailRecord[]::new));
        assertBDEquals("12400.00", this.engine.marketPrice);
        assertEquals(orders.get(8).sequenceId, this.engine.getOrderBook(10).sequenceId);
        assertBDEquals("0.77", orders.get(8).unfilledQuantity);
        assertEquals(OrderStatus.PENDING, orders.get(8).status);

        BigDecimal inputQuantity = orders.stream().map(order -> order.quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal matchedQuantity = matches.stream().map(MatchDetailRecord::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal restingQuantity = Stream.concat(this.engine.buyBook.book.values().stream(),
                        this.engine.sellBook.book.values().stream())
                .map(order -> order.unfilledQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Each execution consumes the same quantity from one buy and one sell order.
        assertEquals(0, inputQuantity.compareTo(matchedQuantity.multiply(BigDecimal.valueOf(2)).add(restingQuantity)));
        for (OrderEntity order : orders) {
            BigDecimal filled = matches.stream()
                    .filter(match -> match.takerOrder() == order || match.makerOrder() == order)
                    .map(MatchDetailRecord::quantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, order.quantity.compareTo(filled.add(order.unfilledQuantity)),
                    () -> "quantity mismatch for order " + order.id);
        }
        if (this.engine.buyBook.getFirst() != null && this.engine.sellBook.getFirst() != null) {
            assertTrue(this.engine.buyBook.getFirst().price.compareTo(this.engine.sellBook.getFirst().price) < 0);
        }
    }

    @Test
    void getOrderBookAggregatesAndSortsPriceLevels() {
        createAndProcess(Direction.BUY, "100", "2");
        createAndProcess(Direction.BUY, "100", "3");
        createAndProcess(Direction.BUY, "99", "4");
        createAndProcess(Direction.SELL, "105", "1");
        createAndProcess(Direction.SELL, "104", "2");
        createAndProcess(Direction.SELL, "104", "3");

        OrderBookBean snapshot = this.engine.getOrderBook(2);

        assertEquals(2, snapshot.buy.size());
        assertBDEquals("100", snapshot.buy.get(0).price);
        assertBDEquals("5", snapshot.buy.get(0).quantity);
        assertBDEquals("99", snapshot.buy.get(1).price);
        assertBDEquals("4", snapshot.buy.get(1).quantity);
        assertEquals(2, snapshot.sell.size());
        assertBDEquals("104", snapshot.sell.get(0).price);
        assertBDEquals("5", snapshot.sell.get(0).quantity);
        assertBDEquals("105", snapshot.sell.get(1).price);
        assertBDEquals("1", snapshot.sell.get(1).quantity);
        assertBDEquals("0", snapshot.price);
        assertEquals(1, this.engine.getOrderBook(1).buy.size());
        assertEquals(1, this.engine.getOrderBook(1).sell.size());
    }

    @Test
    void partialFillsRemainInBookAndCanceledOrderKeepsUnfilledQuantity() {
        OrderEntity maker = createAndProcess(Direction.SELL, "100", "5");
        OrderEntity firstTaker = createAndProcess(Direction.BUY, "100", "2");

        assertEquals(OrderStatus.FULLY_FILLED, firstTaker.status);
        assertBDEquals("0", firstTaker.unfilledQuantity);
        assertEquals(OrderStatus.PARTIAL_FILLED, maker.status);
        assertBDEquals("3", maker.unfilledQuantity);
        assertSame(maker, this.engine.sellBook.getFirst());

        OrderEntity secondTaker = createAndProcess(Direction.BUY, "101", "4");

        assertEquals(OrderStatus.FULLY_FILLED, maker.status);
        assertBDEquals("0", maker.unfilledQuantity);
        assertFalse(this.engine.sellBook.exist(maker));
        assertEquals(OrderStatus.PARTIAL_FILLED, secondTaker.status);
        assertBDEquals("1", secondTaker.unfilledQuantity);
        assertTrue(this.engine.buyBook.exist(secondTaker));
        assertBDEquals("100", this.engine.marketPrice);

        this.engine.cancel(2000L, secondTaker);

        assertEquals(OrderStatus.PARTIAL_CANCELED, secondTaker.status);
        assertBDEquals("1", secondTaker.unfilledQuantity);
        assertEquals(2000L, secondTaker.updatedAt);
        assertFalse(this.engine.buyBook.exist(secondTaker));
        assertThrows(IllegalArgumentException.class, () -> this.engine.cancel(2001L, secondTaker));
    }

    @Test
    void cancelUnfilledOrder() {
        OrderEntity order = createAndProcess(Direction.BUY, "100", "2");

        this.engine.cancel(2000L, order);

        assertEquals(OrderStatus.FULLY_CANCELED, order.status);
        assertBDEquals("2", order.unfilledQuantity);
        assertFalse(this.engine.buyBook.exist(order));
    }

    private OrderEntity createAndProcess(Direction direction, String price, String quantity) {
        OrderEntity order = createOrder(direction, price, quantity);
        this.engine.processOrder(order.sequenceId, order);
        return order;
    }

    private OrderEntity createOrder(Direction direction, String price, String quantity) {
        this.sequenceId++;
        OrderEntity order = new OrderEntity();
        order.id = this.sequenceId << 4;
        order.sequenceId = this.sequenceId;
        order.direction = direction;
        order.price = bd(price);
        order.quantity = order.unfilledQuantity = bd(quantity);
        order.status = OrderStatus.PENDING;
        order.userId = USER_A;
        order.createdAt = order.updatedAt = 1234567890000L + this.sequenceId;
        return order;
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private void assertBDEquals(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual),
                () -> "Expected " + expected + " but got " + actual.toPlainString());
    }
}
