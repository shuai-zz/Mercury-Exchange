package com.itranswarp.exchange;

import static org.junit.jupiter.api.Assertions.*;

import com.itranswarp.exchange.assets.AssetService;
import com.itranswarp.exchange.assets.Transfer;
import com.itranswarp.exchange.enums.AssetEnum;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.enums.OrderStatus;
import com.itranswarp.exchange.model.trade.OrderEntity;
import com.itranswarp.exchange.order.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentMap;

/**
 * Tests for {@link OrderService}.
 *
 * Assets are issued from the system debt account in {@link #init()}, and every test is followed
 * by the same global ledger verification as AssetServiceTest: the per-asset sum of
 * available + frozen over all accounts must stay zero (creating/removing orders only moves
 * funds between available and frozen, never creates or destroys them).
 */
public class OrderServiceTest{
    static final Long DEBT=1L;
    static final Long USER_A=2000L;
    static final Long USER_B=3000L;

    AssetService assetService;
    OrderService orderService;

    @BeforeEach
    public void setup(){
        assetService=new AssetService();
        orderService=new OrderService(assetService);
        init();
    }

    @AfterEach
    public void tearDown(){
        verify();
    }

    /**
     * A BUY order with sufficient USD is created successfully:
     * the worst-case cost price*quantity is frozen, all fields are initialized,
     * and the same order object is registered in both activeOrders and userOrders.
     */
    @Test
    void createBuyOrder(){
        // USER_A buys 2 BTC at 5000 -> freeze 10000 USD:
        OrderEntity order=orderService.createOrder(100L, 1000L, 9001L, USER_A, Direction.BUY,
                new BigDecimal("5000"), new BigDecimal("2"));
        assertNotNull(order);

        // all fields initialized:
        assertEquals(9001L, order.id);
        assertEquals(100L, order.sequenceId);
        assertEquals(USER_A, order.userId);
        assertEquals(Direction.BUY, order.direction);
        assertBDEquals(5000, order.price);
        assertBDEquals(2, order.quantity);
        assertBDEquals(2, order.unfilledQuantity);
        assertEquals(OrderStatus.PENDING, order.status);
        assertEquals(1000L, order.createdAt);
        assertEquals(1000L, order.updatedAt);

        // USD frozen, BTC untouched:
        assertBDEquals(2300, assetService.getAsset(USER_A, AssetEnum.USD).getAvailable());
        assertBDEquals(10000, assetService.getAsset(USER_A, AssetEnum.USD).getFrozen());
        assertBDEquals(12, assetService.getAsset(USER_A, AssetEnum.BTC).getAvailable());
        assertBDEquals(0, assetService.getAsset(USER_A, AssetEnum.BTC).getFrozen());

        // registered in both maps, pointing at the SAME object:
        assertSame(order, orderService.getOrder(9001L));
        assertSame(order, orderService.getUserOrders(USER_A).get(9001L));
    }

    /**
     * A BUY order failing the freeze (insufficient USD) returns null and leaves no trace:
     * balances untouched, nothing registered in either map.
     */
    @Test
    void createBuyOrderInsufficientBalance(){
        // USER_A has 12300 USD, buying 2 BTC at 7000 costs 14000 -> fail:
        OrderEntity order=orderService.createOrder(101L, 1000L, 9002L, USER_A, Direction.BUY,
                new BigDecimal("7000"), new BigDecimal("2"));
        assertNull(order);

        // balances unchanged:
        assertBDEquals(12300, assetService.getAsset(USER_A, AssetEnum.USD).getAvailable());
        assertBDEquals(0, assetService.getAsset(USER_A, AssetEnum.USD).getFrozen());

        // not registered anywhere:
        assertNull(orderService.getOrder(9002L));
        assertNull(orderService.getUserOrders(USER_A));
    }

    /**
     * A SELL order freezes exactly quantity BTC regardless of price:
     * price only decides how much USD comes IN, which needs no freezing.
     */
    @Test
    void createSellOrder(){
        // USER_A sells 5 BTC at 8000:
        OrderEntity order=orderService.createOrder(102L, 1000L, 9003L, USER_A, Direction.SELL,
                new BigDecimal("8000"), new BigDecimal("5"));
        assertNotNull(order);
        assertEquals(Direction.SELL, order.direction);
        assertBDEquals(5, order.unfilledQuantity);
        assertEquals(OrderStatus.PENDING, order.status);

        // BTC frozen, USD untouched:
        assertBDEquals(7, assetService.getAsset(USER_A, AssetEnum.BTC).getAvailable());
        assertBDEquals(5, assetService.getAsset(USER_A, AssetEnum.BTC).getFrozen());
        assertBDEquals(12300, assetService.getAsset(USER_A, AssetEnum.USD).getAvailable());
        assertBDEquals(0, assetService.getAsset(USER_A, AssetEnum.USD).getFrozen());

        // same quantity at a very different price freezes the same BTC amount:
        OrderEntity order2=orderService.createOrder(103L, 1000L, 9004L, USER_A, Direction.SELL,
                new BigDecimal("1"), new BigDecimal("5"));
        assertNotNull(order2);
        assertBDEquals(2, assetService.getAsset(USER_A, AssetEnum.BTC).getAvailable());
        assertBDEquals(10, assetService.getAsset(USER_A, AssetEnum.BTC).getFrozen());
    }

    /**
     * A SELL order failing the freeze (no BTC at all) returns null with no side effects.
     */
    @Test
    void createSellOrderInsufficientBalance(){
        // USER_B holds no BTC:
        OrderEntity order=orderService.createOrder(104L, 1000L, 9005L, USER_B, Direction.SELL,
                new BigDecimal("8000"), new BigDecimal("1"));
        assertNull(order);

        // nothing registered:
        assertNull(orderService.getOrder(9005L));
        assertNull(orderService.getUserOrders(USER_B));
    }

    /**
     * getUserOrders groups orders per user: multiple orders of one user share one map,
     * other users are isolated, and users without orders return null.
     */
    @Test
    void userOrdersGrouping(){
        orderService.createOrder(105L, 1000L, 9006L, USER_A, Direction.BUY,
                new BigDecimal("5000"), new BigDecimal("1"));
        orderService.createOrder(106L, 1000L, 9007L, USER_A, Direction.SELL,
                new BigDecimal("8000"), new BigDecimal("1"));

        ConcurrentMap<Long, OrderEntity> uOrdersA=orderService.getUserOrders(USER_A);
        assertEquals(2, uOrdersA.size());
        assertTrue(uOrdersA.containsKey(9006L));
        assertTrue(uOrdersA.containsKey(9007L));

        // other users are isolated / unknown users have no map:
        assertNull(orderService.getUserOrders(USER_B));
        assertNull(orderService.getUserOrders(9999L));

        // global view sees both:
        assertEquals(2, orderService.getActiveOrders().size());
    }

    /**
     * removeOrder deletes from both maps but does NOT unfreeze assets:
     * unfreezing is the clearing logic's job, not OrderService's.
     */
    @Test
    void removeOrder(){
        orderService.createOrder(107L, 1000L, 9008L, USER_A, Direction.BUY,
                new BigDecimal("5000"), new BigDecimal("2"));
        orderService.createOrder(108L, 1000L, 9009L, USER_A, Direction.BUY,
                new BigDecimal("100"), new BigDecimal("1"));

        orderService.removeOrder(9008L);

        // gone from both maps:
        assertNull(orderService.getOrder(9008L));
        assertFalse(orderService.getUserOrders(USER_A).containsKey(9008L));

        // the other order is unaffected:
        assertNotNull(orderService.getOrder(9009L));
        assertTrue(orderService.getUserOrders(USER_A).containsKey(9009L));

        // frozen assets are NOT unfrozen by removeOrder (5000*2 still frozen, plus 100*1):
        assertBDEquals(2200, assetService.getAsset(USER_A, AssetEnum.USD).getAvailable());
        assertBDEquals(10100, assetService.getAsset(USER_A, AssetEnum.USD).getFrozen());
    }

    /**
     * Removing an unknown order id throws IllegalArgumentException.
     */
    @Test
    void removeOrderNotFound(){
        assertThrows(IllegalArgumentException.class, () -> {
            orderService.removeOrder(9999L);
        });
    }

    /**
     * Defensive check for the inconsistent-state branch: if an order is in activeOrders
     * but missing from its user's map, removeOrder must throw instead of silently succeeding.
     * The corruption is injected through the live map returned by getUserOrders().
     */
    @Test
    void removeOrderInconsistentUserOrders(){
        orderService.createOrder(109L, 1000L, 9010L, USER_A, Direction.BUY,
                new BigDecimal("5000"), new BigDecimal("1"));

        // corrupt: drop the order from the user's map only:
        orderService.getUserOrders(USER_A).remove(9010L);

        assertThrows(IllegalArgumentException.class, () -> {
            orderService.removeOrder(9010L);
        });
        // NOTE: removeOrder removes from activeOrders BEFORE touching userOrders, so this
        // pathological half-removed state leaks: the order is gone from activeOrders even
        // though the call threw. Fine in practice (the inconsistency cannot occur through
        // the public API), but worth pinning down so a future refactor notices the change:
        assertNull(orderService.getOrder(9010L));
    }

    /**
     * getActiveOrders exposes the live internal map (the matching engine depends on this),
     * so two calls must return the same reference, not defensive copies.
     */
    @Test
    void getActiveOrdersIsLiveMap(){
        assertSame(orderService.getActiveOrders(), orderService.getActiveOrders());
    }

    /**
     * Zero or negative quantity is a programming error, not a business failure:
     * createOrder rejects it with IllegalArgumentException before freezing anything,
     * leaving balances and both indexes untouched.
     */
    @Test
    void createOrderNonPositiveQuantity(){
        assertThrows(IllegalArgumentException.class, () -> {
            orderService.createOrder(111L, 1000L, 9011L, USER_A, Direction.BUY,
                    new BigDecimal("5000"), BigDecimal.ZERO);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            orderService.createOrder(112L, 1000L, 9012L, USER_A, Direction.SELL,
                    new BigDecimal("8000"), new BigDecimal("-1"));
        });

        // nothing frozen, nothing registered:
        assertBDEquals(12300, assetService.getAsset(USER_A, AssetEnum.USD).getAvailable());
        assertBDEquals(0, assetService.getAsset(USER_A, AssetEnum.USD).getFrozen());
        assertBDEquals(12, assetService.getAsset(USER_A, AssetEnum.BTC).getAvailable());
        assertBDEquals(0, assetService.getAsset(USER_A, AssetEnum.BTC).getFrozen());
        assertTrue(orderService.getActiveOrders().isEmpty());
        assertNull(orderService.getUserOrders(USER_A));
    }

    /**
     * A duplicate orderId is rejected instead of silently overwriting the resting order:
     * the original order stays intact and the second freeze never happens.
     */
    @Test
    void createOrderDuplicateOrderId(){
        OrderEntity order=orderService.createOrder(113L, 1000L, 9013L, USER_A, Direction.BUY,
                new BigDecimal("5000"), new BigDecimal("1"));
        assertNotNull(order);

        assertThrows(IllegalArgumentException.class, () -> {
            orderService.createOrder(114L, 1000L, 9013L, USER_B, Direction.BUY,
                    new BigDecimal("100"), new BigDecimal("1"));
        });

        // original order intact, second freeze not applied:
        assertSame(order, orderService.getOrder(9013L));
        assertEquals(1, orderService.getActiveOrders().size());
        assertBDEquals(45600, assetService.getAsset(USER_B, AssetEnum.USD).getAvailable());
        assertBDEquals(0, assetService.getAsset(USER_B, AssetEnum.USD).getFrozen());
    }

    /**
     * Initial ledger, funded by issuing assets from the system debt account:
     * USER_A: USD=12300, BTC=12
     * USER_B: USD=45600
     */
    void init(){
        assetService.tryTransfer(Transfer.AVAILABLE_TO_AVAILABLE, DEBT, USER_A, AssetEnum.USD, BigDecimal.valueOf(12300), false);
        assetService.tryTransfer(Transfer.AVAILABLE_TO_AVAILABLE, DEBT, USER_A, AssetEnum.BTC, BigDecimal.valueOf(12), false);
        assetService.tryTransfer(Transfer.AVAILABLE_TO_AVAILABLE, DEBT, USER_B, AssetEnum.USD, BigDecimal.valueOf(45600), false);
    }

    /**
     * Global invariants checked after every test:
     * the per-asset sum of available + frozen over all accounts is zero,
     * normal users never hold negative balances, and the debt account never holds frozen funds.
     */
    void verify(){
        BigDecimal totalUSD=BigDecimal.ZERO;
        BigDecimal totalBTC=BigDecimal.ZERO;
        for (Long userId : assetService.getUserAssets().keySet()) {
            var assetUSD=assetService.getAsset(userId, AssetEnum.USD);
            if(assetUSD!=null){
                totalUSD=totalUSD.add(assetUSD.getAvailable()).add(assetUSD.getFrozen());
            }
            var assetBTC=assetService.getAsset(userId, AssetEnum.BTC);
            if(assetBTC!=null){
                totalBTC=totalBTC.add(assetBTC.getAvailable()).add(assetBTC.getFrozen());
            }
            for (var asset : new com.itranswarp.exchange.assets.Asset[]{assetUSD, assetBTC}) {
                if (asset==null) {
                    continue;
                }
                if (userId.equals(DEBT)) {
                    // system debt account: available may go negative, frozen must stay zero:
                    assertBDEquals(0, asset.getFrozen());
                } else {
                    // normal users can never go negative:
                    assertTrue(asset.getAvailable().signum() >= 0, "negative available for user " + userId);
                    assertTrue(asset.getFrozen().signum() >= 0, "negative frozen for user " + userId);
                }
            }
        }
        assertBDEquals(0, totalBTC);
        assertBDEquals(0, totalUSD);
    }

    /**
     * Compares BigDecimal by value (compareTo), not by equals, so scale differences do not fail the assertion.
     */
    void assertBDEquals(long value, BigDecimal bd){
        assertBDEquals(String.valueOf(value), bd);
    }
    void assertBDEquals(String value, BigDecimal bd){
        assertEquals(0, new BigDecimal(value).compareTo(bd), String.format("Expected %s but actual %s.", value, bd.toPlainString()));
    }
}
