package com.itranswarp.exchange.enums;

/**
 * NOTE: enum-with-constructor syntax, and why negate() belongs on the enum itself.
 * Each constant like BUY(1) is an instance created via the (implicitly private) constructor,
 * binding a stable external int representation used for event serialization and DB storage.
 * Unlike name() (string, spelling-sensitive) and ordinal() (breaks if declaration order ever
 * changes), the explicit value survives refactors; of(int) is the reverse lookup.
 * negate() keeps a domain rule next to the data: in every match the counterparty direction is
 * always the opposite side, so the matching engine writes direction.negate() instead of
 * scattering ternary checks.
 */
public enum Direction {
    BUY(1),
    SELL(0);

    /**
     * int value of Direction
     */
    public final int value;

    /**
     * get negate direction
     */
    public Direction negate(){
        return this==BUY?SELL:BUY;
    }

    Direction(int value) {
        this.value = value;
    }

    public static Direction of(int intValue){
        if(intValue==1){
            return BUY;
        }
        if(intValue==0){
            return SELL;
        }
        throw new IllegalArgumentException("Invalid Direction value.");
    }
}
