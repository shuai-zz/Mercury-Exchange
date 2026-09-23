package com.itranswarp.exchange.order;

import com.itranswarp.exchange.assets.AssetService;
import com.itranswarp.exchange.enums.AssetEnum;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.enums.OrderStatus;
import com.itranswarp.exchange.model.trade.OrderEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class OrderService {
    final AssetService assetService;

    public OrderService(@Autowired AssetService assetService){
        this.assetService=assetService;
    }

    // track all active orders
    final ConcurrentMap<Long, OrderEntity> activeOrders=new ConcurrentHashMap<>();

    // track user active orders
    final ConcurrentMap<Long, ConcurrentMap<Long, OrderEntity>> userOrders=new ConcurrentHashMap<>();

    /**
     * create order, return null if failed
     * <p>
     * NOTE: freeze amount differs by direction because the two sides of a BTC/USD order
     * hand over different assets. The freeze rule is "lock whatever you must pay out if
     * this order fully fills":
     * BUY pays USD, so freeze the worst-case cost price*quantity (fills below the limit
     * price unfreeze the excess at settlement); SELL hands over BTC itself, so freeze
     * exactly quantity regardless of price (price only decides how much USD comes IN,
     * which needs no freezing). This guarantees every order resting on the book is fully
     * backed by frozen assets, so matching never has to check solvency again.
     * <p>
     * NOTE: input defense runs BEFORE any freezing, so a rejected order leaves zero side
     * effects. quantity must be positive (zero/negative quantities are programming errors,
     * not business failures -> IllegalArgumentException); a duplicate orderId is rejected
     * instead of silently overwriting the resting order. The containsKey-then-put sequence
     * relies on the matching engine being the single writer of this map.
     */
    public OrderEntity createOrder(long sequenceId, long ts, Long orderId, Long userId, Direction direction,
                                   BigDecimal price, BigDecimal quantity){
        if(quantity.signum()<=0){
            throw new IllegalArgumentException("quantity must be positive: "+quantity);
        }
        if(activeOrders.containsKey(orderId)){
            throw new IllegalArgumentException("duplicate orderId: "+orderId);
        }
        switch (direction){
            case BUY -> {
                // BUY, USD need to be frozen
                if(!assetService.tryFreeze(userId, AssetEnum.USD, price.multiply(quantity))){
                    return null;
                }
            }
            case SELL -> {
                // SELL, BTC need to be frozen
                if(!assetService.tryFreeze(userId, AssetEnum.BTC, quantity)){
                    return null;
                }
            }
            default -> throw new IllegalArgumentException("Invalid direction.");
        }

        OrderEntity order = new OrderEntity();
        order.id=orderId;
        order.sequenceId=sequenceId;
        order.userId=userId;
        order.direction=direction;
        order.price=price;
        order.quantity=quantity;
        order.unfilledQuantity=quantity;
        order.status= OrderStatus.PENDING;
        order.createdAt=order.updatedAt=ts;

        // register into ActiveOrders
        activeOrders.put(orderId, order);
        // register into UserOrders
        this.userOrders.computeIfAbsent(userId, id -> new ConcurrentHashMap<>()).put(order.id, order);
        return order;
    }

    public ConcurrentMap<Long, OrderEntity> getActiveOrders(){
        return this.activeOrders;
    }

    public OrderEntity getOrder(Long orderId){
        return this.activeOrders.get(orderId);
    }

    public ConcurrentMap<Long, OrderEntity> getUserOrders(Long userId){
        return this.userOrders.get(userId);
    }

    // delete active order
    public void removeOrder(Long orderId){
        // delete from ActiveOrders
        OrderEntity removed = this.activeOrders.remove(orderId);
        if(removed==null){
            throw new IllegalArgumentException("Order not found by orderId in active orders: "+orderId);
        }
        // delete from UserOrders
        ConcurrentMap<Long, OrderEntity> uOrders = userOrders.get(removed.userId);
        if(uOrders==null){
            throw new IllegalArgumentException("User orders not found by userId: "+removed.userId);
        }
        if(uOrders.remove(orderId)==null){
            throw new IllegalArgumentException("Order not found by orderId in user orders: "+orderId);
        }
    }


    public void debug(){
        System.out.println("--------------- orders ---------------");
        List<OrderEntity> orders = new ArrayList<>(this.activeOrders.values());
        Collections.sort(orders);
        for (OrderEntity order : orders) {
            System.out.println("    " + order.id + " " + order.direction + " price: " + order.price
                    + " unfilled: " + order.unfilledQuantity + " quantity: " + order.quantity
                    + " sequenceId: " + order.sequenceId + " userId: " + order.userId);
        }
        System.out.println("--------------- // orders ---------------");
    }


}
