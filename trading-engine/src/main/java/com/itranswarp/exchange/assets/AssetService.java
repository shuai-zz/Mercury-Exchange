package com.itranswarp.exchange.assets;

import com.itranswarp.exchange.enums.AssetEnum;
import com.itranswarp.exchange.support.LoggerSupport;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AssetService extends LoggerSupport {
    /**
     * Maps each user ID to the user's available and frozen balances by asset type.
     */

    final ConcurrentHashMap<Long, ConcurrentHashMap<AssetEnum, Asset>> userAssets = new ConcurrentHashMap<>();

    /**
     * Returns all asset balances for the specified user.
     */
    public Map<AssetEnum, Asset> getAssets(Long userId) {
        Map<AssetEnum, Asset> assets = userAssets.get(userId);
        if (assets == null) {
            return Map.of();
        }
        return assets;
    }

    /**
     * Returns the user's balance for the specified asset, or {@code null} if it does not exist.
     */
    public Asset getAsset(Long userId, AssetEnum assetId) {
        ConcurrentHashMap<AssetEnum, Asset> assets = userAssets.get(userId);
        if (assets == null) {
            return null;
        }
        return assets.get(assetId);
    }

    public ConcurrentHashMap<Long, ConcurrentHashMap<AssetEnum, Asset>> getUserAssets() {
        return this.userAssets;
    }


    /**
     * Initializes a zero balance when a user transacts in an asset for the first time.
     *
     * @return the newly initialized asset balance
     */
    private Asset initAssets(Long userId, AssetEnum assetId) {
        ConcurrentHashMap<AssetEnum, Asset> map = userAssets.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        Asset zeroAsset = new Asset();
        map.put(assetId, zeroAsset);
        return zeroAsset;
    }

    /**
     * Transfers an asset and throws an exception if the transfer cannot be completed.
     */
    public void transfer(Transfer type, Long fromUser, Long toUser, AssetEnum assetId, BigDecimal amount) {
        if (!tryTransfer(type, fromUser, toUser, assetId, amount, true)) {
            throw new RuntimeException("Transfer failed for " + type + ", from user " + fromUser + " to user " + toUser
                    + ", asset = " + assetId + ", amount = " + amount);
        }
        if (logger.isDebugEnabled()) {
            logger.debug("transfer asset {}, from {} -> {}, amount {}", assetId, fromUser, toUser, amount);
        }
    }

    /**
     * Attempts to transfer an asset between balance buckets.
     *
     * @param checkBalance whether to reject the transfer when the source balance is insufficient;
     *                     this should be {@code false} only for the system debt account
     * @return {@code true} if the transfer succeeds; {@code false} if the balance is insufficient
     */

    public boolean tryTransfer(Transfer type, Long fromUser, Long toUser, AssetEnum assetId, BigDecimal amount, boolean checkBalance) {
        if (amount.signum() == 0) {
            return true;
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Negative amount");
        }
        Asset fromAsset = getAsset(fromUser, assetId);
        if (fromAsset == null) {
            fromAsset = initAssets(fromUser, assetId);
        }
        Asset toAsset = getAsset(toUser, assetId);
        if (toAsset == null) {
            toAsset = initAssets(toUser, assetId);
        }

        return switch (type) {
            case AVAILABLE_TO_AVAILABLE -> {
                // Reject the transfer if balance checks are enabled and the available balance is insufficient.
                if (checkBalance && fromAsset.available.compareTo(amount) < 0) {
                    yield false;
                }
                fromAsset.available = fromAsset.available.subtract(amount);
                toAsset.available = toAsset.available.add(amount);
                yield true;
            }
            case AVAILABLE_TO_FROZEN -> {
                // Reject the transfer if balance checks are enabled and the available balance is insufficient.
                if (checkBalance && fromAsset.available.compareTo(amount) < 0) {
                    yield false;
                }
                fromAsset.available = fromAsset.available.subtract(amount);
                toAsset.frozen = toAsset.frozen.add(amount);
                yield true;
            }
            case FROZEN_TO_AVAILABLE -> {
                // Reject the transfer if balance checks are enabled and the frozen balance is insufficient.
                if (checkBalance && fromAsset.frozen.compareTo(amount) < 0) {
                    yield false;
                }
                fromAsset.frozen = fromAsset.frozen.subtract(amount);
                toAsset.available = toAsset.available.add(amount);
                yield true;
            }
            default -> throw new IllegalArgumentException("Invalid type: " + type);

        };
    }

    /**
     * Attempts to move funds from the user's available balance to the frozen balance.
     * Insufficient funds are an expected business outcome, so this method returns {@code false} instead of throwing.
     */
    public boolean tryFreeze(Long userId, AssetEnum assetId, BigDecimal amount) {
        boolean ok = tryTransfer(Transfer.AVAILABLE_TO_FROZEN, userId, userId, assetId, amount, true);
        if (ok && logger.isDebugEnabled()) {
            logger.debug("frozen user {}, asset {}, amount {}", userId, assetId, amount);
        }
        return ok;
    }

    /**
     * Moves funds from the user's frozen balance back to the available balance.
     * Failure indicates an inconsistent internal state because these funds should have been frozen previously,
     * so this method throws rather than returning {@code false}.
     */
    public void unfreeze(Long userId, AssetEnum assetId, BigDecimal amount) {
        if (!tryTransfer(Transfer.FROZEN_TO_AVAILABLE, userId, userId, assetId, amount, true)) {
            throw new RuntimeException(
                    "Unfreeze failed for user " + userId + ", asset = " + assetId + ", amount = " + amount
            );
        }
        if (logger.isDebugEnabled()) {
            logger.debug("unfrozen user {}, asset {}, amount {}", userId, assetId, amount);
        }
    }


    public void debug() {
        System.out.println("--------------- assets ---------------");
        List<Long> userIds = new ArrayList<>(userAssets.keySet());
        Collections.sort(userIds);
        for (Long userId : userIds) {
            System.out.println(" user " + userId + " -----------");
            Map<AssetEnum, Asset> assets = userAssets.get(userId);
            ArrayList<AssetEnum> assetIds = new ArrayList<>(assets.keySet());
            Collections.sort(assetIds);
            for (AssetEnum assetId : assetIds) {
                System.out.println("    " + assetId + ": " + assets.get(assetId));
            }
        }
        System.out.println("--------------- // assets ---------------");
    }
}
