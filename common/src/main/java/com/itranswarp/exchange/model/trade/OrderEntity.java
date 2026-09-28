package com.itranswarp.exchange.model.trade;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.itranswarp.exchange.enums.Direction;
import com.itranswarp.exchange.enums.OrderStatus;
import com.itranswarp.exchange.model.support.EntitySupport;
import jakarta.persistence.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

/**
 * Order entity
 * <p>
 * NOTE: the JPA annotations on this class are pure mapping metadata, and no mapper/DAO layer exists by design.
 * This project uses neither Hibernate nor MyBatis; common/pom.xml only carries jakarta.persistence-api
 * (annotation definitions, no runtime). @Entity / @Table / @Id / @Column are read by our own mini-ORM
 * (common/db/DbTemplate, a thin JdbcTemplate wrapper introduced in step-6) which scans @Entity classes
 * and builds SQL from these annotations.
 * There is deliberately no repository/mapper layer: services call DbTemplate directly with the entity
 * itself as the model. Under event sourcing the authoritative state lives in memory (AssetService,
 * OrderService) and MySQL is only an async backup rebuilt by replaying the events table, so an extra
 * data-access abstraction would add indirection without buying anything.
 */
@Entity
@Table(name = "orders")
public class OrderEntity implements EntitySupport, Comparable<OrderEntity> {
    /**
     * Primary key: assigned order id
     */
    @Id
    @Column(nullable = false, updatable = false)
    public Long id;


    /**
     * event id (AKA sequenceId) that create this order. ASC only
     */
    @Column(nullable = false, updatable = false)
    public long sequenceId;

    /**
     * Order direction
     */
    @Column(nullable = false, updatable = false, length = VAR_ENUM)
    public Direction direction;

    /**
     * User id of this order
     */
    @Column(nullable = false, updatable = false)
    public Long userId;

    /**
     * Order status
     */
    @Column(nullable = false, updatable = false, length = VAR_ENUM)
    public OrderStatus status;

    /**
     * Intended mutation entry point after insert; public fields rely on caller discipline.
     * NOTE: seqlock write side. The first version++ makes the version ODD (write in progress),
     * the second makes it EVEN (write complete); readers reject odd snapshots in copy().
     * The volatile counter alone is not a proof of consistent cross-thread snapshots.
     * Memory-ordering constraints and snapshot validation are deferred to Issue #44.
     * Assumes one writer per order (caller contract, not enforced by the type system);
     * plain ++ has no competing-writer race only under that contract.
     */
    // Assumes the matching-engine thread is the only writer. This is caller discipline,
    // not an API-enforced invariant; competing writers would race on the non-atomic ++.
    @SuppressWarnings("NonAtomicOperationOnVolatileField")
    public void updateOrder(BigDecimal unfilledQuantity, OrderStatus status, long updatedAt) {
        this.version++;
        this.unfilledQuantity = unfilledQuantity;
        this.status = status;
        this.updatedAt = updatedAt;
        this.version++;
    }

    /**
     * The limit-order price. MUST NOT change after insert
     */
    @Column(nullable = false, updatable = false, precision = PRECISION, scale = SCALE)
    public BigDecimal price;

    /**
     * Created time (millisecond)
     */
    @Column(nullable = false, updatable = false)
    public long createdAt;

    /**
     * Updated time (ms)
     */
    @Column(nullable = false, updatable = false)
    public long updatedAt;

    /**
     * NOTE: seqlock version counter (see Issue #42). Odd = write in progress, even = stable.
     * volatile gives the counter visibility semantics; the complete snapshot protocol still
     * needs memory-ordering review and validation (Issue #44). Do not infer snapshot safety
     * from counter visibility alone.
     * Incremented twice per updateOrder() (bracketing the field writes); single-writer, so
     * no atomicity needed.
     */
    private volatile int version;

    @Transient
    @JsonIgnore
    public int getVersion() {
        return this.version;
    }

    /**
     * The order quantity. MUST NOT change after insert
     */
    @Column(nullable = false, updatable = false, precision = PRECISION, scale = SCALE)
    public BigDecimal quantity;

    /**
     * How much unfilled during match
     */
    @Column(nullable = false, updatable = false, precision = PRECISION, scale = SCALE)
    public BigDecimal unfilledQuantity;

    /**
     * Create a snapshot copy of this order.
     * Returns null for an odd starting version or a detected version change; caller should
     * retry. Cross-thread consistency is not yet validated; see Issue #44 before concurrent use.
     */
    @Nullable
    public OrderEntity copy() {
        OrderEntity entity = new OrderEntity();
        // NOTE: seqlock read side. An odd starting version means a writer is mid-update;
        // reject immediately instead of copying torn fields. See Issue #42.
        int ver = this.version;
        if ((ver & 1) == 1) {
            return null;
        }
        entity.status = this.status;
        entity.unfilledQuantity = this.unfilledQuantity;
        entity.updatedAt = this.updatedAt;
        if (ver != this.version) {
            return null;
        }
        // immutable after insert, safe to copy after the check:
        entity.id = this.id;
        entity.sequenceId = this.sequenceId;
        entity.userId = this.userId;
        entity.direction = this.direction;
        entity.price = this.price;
        entity.quantity = this.quantity;
        entity.createdAt = this.createdAt;
        return entity;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof OrderEntity) {
            OrderEntity e = (OrderEntity) obj;
            return this.id.longValue() == e.id.longValue();
        }
        return false;
    }

    @Override
    public int hashCode() {
        return this.id.hashCode();
    }

    @Override
    public String toString() {
        return "OrderEntity [id=" + id + ", sequenceId=" + sequenceId + ", direction=" + direction + ", userId="
                + userId + ", status=" + status + ", price=" + price + ", quantity=" + quantity
                + ", unfilledQuantity=" + unfilledQuantity + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt
                + ", version=" + version + "]";
    }

    /**
     * sort by orderId
     */
    @Override
    public int compareTo(OrderEntity orderEntity) {
        return Long.compare(this.id, orderEntity.id);
    }
}
