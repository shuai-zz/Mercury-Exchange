package com.itranswarp.exchange.clearing;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.itranswarp.exchange.assets.Asset;
import com.itranswarp.exchange.assets.AssetService;
import com.itranswarp.exchange.assets.Transfer;
import com.itranswarp.exchange.enums.AssetEnum;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.enums.OrderStatus;
import com.itranswarp.exchange.match.MatchEngine;
import com.itranswarp.exchange.match.MatchResult;
import com.itranswarp.exchange.model.trade.OrderEntity;
import com.itranswarp.exchange.order.OrderService;

class ClearingServiceTest {

    private static final long DEBT = 1L;
    private static final long BUYER = 2000L;
    private static final long SELLER = 3000L;

    private AssetService assetService;
    private OrderService orderService;
    private MatchEngine matchEngine;
    private ClearingService clearingService;
    private long sequenceId;

    @BeforeEach
    void setUp() {
        this.assetService = new AssetService();
        this.orderService = new OrderService(this.assetService);
        this.matchEngine = new MatchEngine();
        this.clearingService = new ClearingService(this.assetService, this.orderService);
        deposit(BUYER, AssetEnum.USD, "1000");
        deposit(SELLER, AssetEnum.BTC, "10");
    }

    @AfterEach
    void verifyLedger() {
        BigDecimal totalUsd = BigDecimal.ZERO;
        BigDecimal totalBtc = BigDecimal.ZERO;
        for (Long userId : this.assetService.getUserAssets().keySet()) {
            Asset usd = this.assetService.getAsset(userId, AssetEnum.USD);
            Asset btc = this.assetService.getAsset(userId, AssetEnum.BTC);
            if (usd != null) {
                totalUsd = totalUsd.add(usd.getTotal());
                assertNonNegativeBalance(userId, usd);
            }
            if (btc != null) {
                totalBtc = totalBtc.add(btc.getTotal());
                assertNonNegativeBalance(userId, btc);
            }
        }
        assertBDEquals("0", totalUsd);
        assertBDEquals("0", totalBtc);
    }

    @Test
    void buyTakerReceivesPriceImprovementAndFilledOrdersAreRemoved() {
        OrderEntity maker = createOrder(SELLER, Direction.SELL, "100", "2");
        settle(maker);
        OrderEntity taker = createOrder(BUYER, Direction.BUY, "110", "2");

        MatchResult result = settle(taker);

        assertEquals(1, result.matchDetails.size());
        assertBalance(BUYER, AssetEnum.USD, "800", "0");
        assertBalance(BUYER, AssetEnum.BTC, "2", "0");
        assertBalance(SELLER, AssetEnum.USD, "200", "0");
        assertBalance(SELLER, AssetEnum.BTC, "8", "0");
        assertNull(this.orderService.getOrder(taker.id));
        assertNull(this.orderService.getOrder(maker.id));
    }

    @Test
    void sellTakerUsesMakerPriceAndPartialMakerRemainsActive() {
        OrderEntity maker = createOrder(BUYER, Direction.BUY, "100", "5");
        settle(maker);
        OrderEntity taker = createOrder(SELLER, Direction.SELL, "90", "2");

        MatchResult result = settle(taker);

        assertEquals(1, result.matchDetails.size());
        assertBDEquals("100", result.matchDetails.get(0).price());
        assertEquals(OrderStatus.PARTIAL_FILLED, maker.status);
        assertBDEquals("3", maker.unfilledQuantity);
        assertBalance(BUYER, AssetEnum.USD, "500", "300");
        assertBalance(BUYER, AssetEnum.BTC, "2", "0");
        assertBalance(SELLER, AssetEnum.USD, "200", "0");
        assertBalance(SELLER, AssetEnum.BTC, "8", "0");
        assertSame(maker, this.orderService.getOrder(maker.id));
        assertNull(this.orderService.getOrder(taker.id));
    }

    @Test
    void partialBuyTakerKeepsRemainingFrozenFundsAndActiveOrder() {
        OrderEntity maker = createOrder(SELLER, Direction.SELL, "100", "1");
        settle(maker);
        OrderEntity taker = createOrder(BUYER, Direction.BUY, "110", "3");

        settle(taker);

        assertEquals(OrderStatus.PARTIAL_FILLED, taker.status);
        assertBDEquals("2", taker.unfilledQuantity);
        assertBalance(BUYER, AssetEnum.USD, "680", "220");
        assertBalance(BUYER, AssetEnum.BTC, "1", "0");
        assertBalance(SELLER, AssetEnum.USD, "100", "0");
        assertBalance(SELLER, AssetEnum.BTC, "9", "0");
        assertSame(taker, this.orderService.getOrder(taker.id));
        assertNull(this.orderService.getOrder(maker.id));
    }

    @Test
    void unmatchedOrderDoesNotTransferAssetsOrLeaveActiveIndex() {
        OrderEntity order = createOrder(BUYER, Direction.BUY, "100", "2");

        MatchResult result = settle(order);

        assertTrue(result.matchDetails.isEmpty());
        assertBalance(BUYER, AssetEnum.USD, "800", "200");
        assertSame(order, this.orderService.getOrder(order.id));
    }

    @Test
    void cancelUnfilledBuyOrderReleasesAllFrozenUsd() {
        OrderEntity order = createOrder(BUYER, Direction.BUY, "100", "2");
        settle(order);

        cancel(order);

        assertBalance(BUYER, AssetEnum.USD, "1000", "0");
        assertNull(this.orderService.getOrder(order.id));
    }

    @Test
    void cancelUnfilledSellOrderReleasesAllFrozenBtc() {
        OrderEntity order = createOrder(SELLER, Direction.SELL, "100", "2");
        settle(order);

        cancel(order);

        assertBalance(SELLER, AssetEnum.BTC, "10", "0");
        assertNull(this.orderService.getOrder(order.id));
    }

    @Test
    void cancelPartialBuyOrderReleasesOnlyRemainingFrozenUsd() {
        OrderEntity maker = createOrder(SELLER, Direction.SELL, "100", "1");
        settle(maker);
        OrderEntity taker = createOrder(BUYER, Direction.BUY, "100", "3");
        settle(taker);

        cancel(taker);

        assertEquals(OrderStatus.PARTIAL_CANCELED, taker.status);
        assertBalance(BUYER, AssetEnum.USD, "900", "0");
        assertBalance(BUYER, AssetEnum.BTC, "1", "0");
        assertBalance(SELLER, AssetEnum.USD, "100", "0");
        assertNull(this.orderService.getOrder(taker.id));
    }

    @Test
    void cancelPartialSellOrderReleasesOnlyRemainingFrozenBtc() {
        OrderEntity maker = createOrder(BUYER, Direction.BUY, "100", "1");
        settle(maker);
        OrderEntity taker = createOrder(SELLER, Direction.SELL, "90", "3");
        settle(taker);

        cancel(taker);

        assertEquals(OrderStatus.PARTIAL_CANCELED, taker.status);
        assertBalance(SELLER, AssetEnum.USD, "100", "0");
        assertBalance(SELLER, AssetEnum.BTC, "9", "0");
        assertBalance(BUYER, AssetEnum.BTC, "1", "0");
        assertNull(this.orderService.getOrder(taker.id));
    }

    @Test
    void insufficientFrozenAssetThrowsAndDoesNotRollBackEarlierTransfer() {
        OrderEntity maker = createOrder(SELLER, Direction.SELL, "100", "1");
        settle(maker);
        OrderEntity taker = createOrder(BUYER, Direction.BUY, "100", "1");
        MatchResult result = this.matchEngine.processOrder(taker.sequenceId, taker);
        this.assetService.unfreeze(SELLER, AssetEnum.BTC, BigDecimal.ONE);

        assertThrows(RuntimeException.class, () -> this.clearingService.clearMatchResult(result));

        assertBalance(BUYER, AssetEnum.USD, "900", "0");
        assertBalance(BUYER, AssetEnum.BTC, "0", "0");
        assertBalance(SELLER, AssetEnum.USD, "100", "0");
        assertBalance(SELLER, AssetEnum.BTC, "10", "0");
        assertSame(taker, this.orderService.getOrder(taker.id));
        assertSame(maker, this.orderService.getOrder(maker.id));
    }

    private OrderEntity createOrder(long userId, Direction direction, String price, String quantity) {
        this.sequenceId++;
        OrderEntity order = this.orderService.createOrder(this.sequenceId, 1000L + this.sequenceId, this.sequenceId,
                userId, direction, bd(price), bd(quantity));
        assertNotNull(order);
        return order;
    }

    private MatchResult settle(OrderEntity order) {
        MatchResult result = this.matchEngine.processOrder(order.sequenceId, order);
        this.clearingService.clearMatchResult(result);
        return result;
    }

    private void cancel(OrderEntity order) {
        this.matchEngine.cancel(2000L, order);
        this.clearingService.clearCancelOrder(order);
    }

    private void deposit(long userId, AssetEnum assetId, String amount) {
        this.assetService.tryTransfer(Transfer.AVAILABLE_TO_AVAILABLE, DEBT, userId, assetId, bd(amount), false);
    }

    private void assertBalance(long userId, AssetEnum assetId, String available, String frozen) {
        Asset asset = this.assetService.getAsset(userId, assetId);
        assertNotNull(asset);
        assertBDEquals(available, asset.getAvailable());
        assertBDEquals(frozen, asset.getFrozen());
    }

    private void assertNonNegativeBalance(long userId, Asset asset) {
        if (userId == DEBT) {
            assertBDEquals("0", asset.getFrozen());
        } else {
            assertTrue(asset.getAvailable().signum() >= 0, "negative available balance for user " + userId);
            assertTrue(asset.getFrozen().signum() >= 0, "negative frozen balance for user " + userId);
        }
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private void assertBDEquals(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual),
                () -> "Expected " + expected + " but got " + actual.toPlainString());
    }
}
