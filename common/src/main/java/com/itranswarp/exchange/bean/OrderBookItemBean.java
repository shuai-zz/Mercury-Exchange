package com.itranswarp.exchange.bean;

import java.math.BigDecimal;

public class OrderBookItemBean {
    // price of this order book level
    public BigDecimal price;
    // total quantity of all orders at this price
    public BigDecimal quantity;


    public OrderBookItemBean(BigDecimal price, BigDecimal quantity) {
        this.price = price;
        this.quantity = quantity;
    }

    public void addQuantity(BigDecimal quantity) {
        this.quantity = this.quantity.add(quantity);
    }
}
