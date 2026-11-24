package com.itranswarp.exchange.clearing;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.itranswarp.exchange.assets.AssetService;
import com.itranswarp.exchange.assets.Transfer;
import com.itranswarp.exchange.enums.AssetEnum;
import com.itranswarp.exchange.match.MatchDetailRecord;
import com.itranswarp.exchange.match.MatchResult;
import com.itranswarp.exchange.model.trade.OrderEntity;
import com.itranswarp.exchange.order.OrderService;
import com.itranswarp.exchange.support.LoggerSupport;

@Component
public class ClearingService extends LoggerSupport {

    final AssetService assetService;
    final OrderService orderService;

    public ClearingService(@Autowired AssetService assetService, @Autowired OrderService orderService) {
        this.assetService = assetService;
        this.orderService = orderService;
    }

    /**
     * Settles every execution from the taker's perspective. The matching engine executes each detail at the maker's
     * price, while a BUY taker's USD was frozen at the taker's possibly higher limit price.
     */
    public void clearMatchResult(MatchResult result) {
        OrderEntity taker = result.takerOrder;
        switch (taker.direction) {
            case BUY -> {
                for (MatchDetailRecord detail : result.matchDetails) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(
                                "clear buy matched detail: price={}, quantity={}, takerOrderId={}, makerOrderId={}, takerUserId={}, makerUserId={}",
                                detail.price(), detail.quantity(), detail.takerOrder().id, detail.makerOrder().id,
                                detail.takerOrder().userId, detail.makerOrder().userId);
                    }
                    OrderEntity maker = detail.makerOrder();
                    BigDecimal matched = detail.quantity();

                    if (taker.price.compareTo(maker.price) > 0) {
                        // Return the frozen USD that is not needed at the maker's lower price.
                        BigDecimal unfreezeQuote = taker.price.subtract(maker.price).multiply(matched);
                        logger.debug("unfreeze extra unused quote {} back to taker user {}", unfreezeQuote, taker.userId);
                        assetService.unfreeze(taker.userId, AssetEnum.USD, unfreezeQuote);
                    }

                    // Transfer the execution value from the buyer's frozen USD to the seller's available USD.
                    assetService.transfer(Transfer.FROZEN_TO_AVAILABLE, taker.userId, maker.userId, AssetEnum.USD,
                            maker.price.multiply(matched));

                    // Transfer the executed quantity from the seller's frozen BTC to the buyer's available BTC.
                    assetService.transfer(Transfer.FROZEN_TO_AVAILABLE, maker.userId, taker.userId, AssetEnum.BTC,
                            matched);

                    // Remove a maker only after the matching engine has completely filled it.
                    if (maker.unfilledQuantity.signum() == 0) {
                        orderService.removeOrder(maker.id);
                    }
                }
                // Keep a partially filled taker active with its remaining frozen USD.
                if (taker.unfilledQuantity.signum() == 0) {
                    orderService.removeOrder(taker.id);
                }
            }
            case SELL -> {
                for (MatchDetailRecord detail : result.matchDetails) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(
                                "clear sell matched detail: price={}, quantity={}, takerOrderId={}, makerOrderId={}, takerUserId={}, makerUserId={}",
                                detail.price(), detail.quantity(), detail.takerOrder().id, detail.makerOrder().id,
                                detail.takerOrder().userId, detail.makerOrder().userId);
                    }

                    OrderEntity maker = detail.makerOrder();
                    BigDecimal matched = detail.quantity();

                    // Transfer the executed quantity from the seller's frozen BTC to the buyer's available BTC.
                    assetService.transfer(Transfer.FROZEN_TO_AVAILABLE, taker.userId, maker.userId, AssetEnum.BTC,
                            matched);
                    // Transfer the execution value from the buyer's frozen USD to the seller's available USD.
                    assetService.transfer(Transfer.FROZEN_TO_AVAILABLE, maker.userId, taker.userId, AssetEnum.USD,
                            maker.price.multiply(matched));

                    // Remove a maker only after the matching engine has completely filled it.
                    if (maker.unfilledQuantity.signum() == 0) {
                        orderService.removeOrder(maker.id);
                    }
                }
                // Keep a partially filled taker active with its remaining frozen BTC.
                if (taker.unfilledQuantity.signum() == 0) {
                    orderService.removeOrder(taker.id);
                }
            }
            default -> throw new IllegalArgumentException("Invalid direction.");
        }
    }

    /**
     * Releases the assets reserved for the unfilled remainder and removes the canceled order from the active-order
     * index. The matching engine must remove the order from its order book before this method is called.
     */
    public void clearCancelOrder(OrderEntity order) {
        switch (order.direction) {
            case BUY -> {
                // Release the USD reserved for the unfilled remainder.
                assetService.unfreeze(order.userId, AssetEnum.USD, order.price.multiply(order.unfilledQuantity));
            }
            case SELL -> {
                // Release the BTC reserved for the unfilled remainder.
                assetService.unfreeze(order.userId, AssetEnum.BTC, order.unfilledQuantity);
            }
            default -> throw new IllegalArgumentException("Invalid direction.");
        }
        orderService.removeOrder(order.id);
    }
}
