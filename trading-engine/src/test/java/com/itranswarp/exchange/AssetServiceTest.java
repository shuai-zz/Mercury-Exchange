package com.itranswarp.exchange;

import static org.junit.jupiter.api.Assertions.*;
import com.itranswarp.exchange.assets.Asset;
import com.itranswarp.exchange.assets.AssetService;
import com.itranswarp.exchange.assets.Transfer;
import com.itranswarp.exchange.enums.AssetEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/**
 * Tests for {@link AssetService}.
 *
 * Every test is followed by a global ledger verification (see {@link #verify()}):
 * the per-asset sum of available + frozen over all accounts must be zero,
 * normal users must never go negative, and the system debt account must keep a zero frozen balance.
 */
public class AssetServiceTest{
    static final Long DEBT=1L;
    static final Long USER_A=2000L;
    static final Long USER_B=3000L;
    static final Long USER_C=4000L;

    AssetService service;

    @BeforeEach
    public void setup(){
        service=new AssetService();
        init();
    }

    @AfterEach
    public void tearDown(){
        verify();
    }

    /**
     * AVAILABLE_TO_AVAILABLE transfer: moves funds between users,
     * returns false without changing anything when the source balance is insufficient.
     */
    @Test
    void tryTransfer(){
        // A -> B ok
        service.tryTransfer(Transfer.AVAILABLE_TO_AVAILABLE, USER_A, USER_B, AssetEnum.USD, new BigDecimal("12000"), true);
        assertBDEquals(300, service.getAsset(USER_A, AssetEnum.USD).getAvailable());
        assertBDEquals(12000+45600, service.getAsset(USER_B, AssetEnum.USD).getAvailable());

        //A -> B failed
        assertFalse(service.tryTransfer(Transfer.AVAILABLE_TO_AVAILABLE, USER_A, USER_B, AssetEnum.USD, new BigDecimal("301"), true));

        assertBDEquals(300, service.getAsset(USER_A, AssetEnum.USD).getAvailable());
        assertBDEquals(12000+45600, service.getAsset(USER_B, AssetEnum.USD).getAvailable());
    }

    /**
     * tryFreeze moves funds from available to frozen within the same account;
     * insufficient funds are a normal business outcome, so it returns false instead of throwing.
     */
    @Test
    void tryFreeze(){
        // freeze 12000 ok
        service.tryFreeze(USER_A, AssetEnum.USD, new BigDecimal("12000"));
        assertBDEquals(300, service.getAsset(USER_A, AssetEnum.USD).getAvailable());
        assertBDEquals(12000, service.getAsset(USER_A, AssetEnum.USD).getFrozen());

        // freeze 301 failed
        assertFalse(service.tryFreeze(USER_A, AssetEnum.USD, new BigDecimal("301")));
        assertBDEquals(300, service.getAsset(USER_A, AssetEnum.USD).getAvailable());
        assertBDEquals(12000, service.getAsset(USER_A, AssetEnum.USD).getFrozen());
    }

    /**
     * unfreeze moves funds back from frozen to available;
     * failure means inconsistent internal state (the funds should have been frozen), so it throws.
     */
    @Test
    void unfreeze() {
        // freeze 12000 ok:
        service.tryFreeze(USER_A, AssetEnum.USD, new BigDecimal("12000"));
        assertBDEquals(300, service.getAsset(USER_A, AssetEnum.USD).getAvailable());
        assertBDEquals(12000, service.getAsset(USER_A, AssetEnum.USD).getFrozen());

        // unfreeze 9000 ok:
        service.unfreeze(USER_A, AssetEnum.USD, new BigDecimal("9000"));
        assertBDEquals(9300, service.getAsset(USER_A, AssetEnum.USD).getAvailable());
        assertBDEquals(3000, service.getAsset(USER_A, AssetEnum.USD).getFrozen());

        // unfreeze 3001 failed:
        assertThrows(RuntimeException.class, () -> {
            service.unfreeze(USER_A, AssetEnum.USD, new BigDecimal("3001"));
        });
    }

    /**
     * transfer is the throwing wrapper around tryTransfer:
     * AVAILABLE_TO_FROZEN freezes for oneself, FROZEN_TO_AVAILABLE pays frozen funds to another user.
     */
    @Test
    void transfer() {
        // A USD -> A frozen:
        service.transfer(Transfer.AVAILABLE_TO_FROZEN, USER_A, USER_A, AssetEnum.USD, new BigDecimal("9000"));
        assertBDEquals(3300, service.getAsset(USER_A, AssetEnum.USD).getAvailable());
        assertBDEquals(9000, service.getAsset(USER_A, AssetEnum.USD).getFrozen());

        // A frozen -> C available:
        service.transfer(Transfer.FROZEN_TO_AVAILABLE, USER_A, USER_C, AssetEnum.USD, new BigDecimal("8000"));
        assertBDEquals(1000, service.getAsset(USER_A, AssetEnum.USD).getFrozen());
        assertBDEquals(8000, service.getAsset(USER_C, AssetEnum.USD).getAvailable());

        // A frozen -> B available failed:
        assertThrows(RuntimeException.class, () -> {
            service.transfer(Transfer.FROZEN_TO_AVAILABLE, USER_A, USER_B, AssetEnum.USD, new BigDecimal("1001"));
        });
    }

    /**
     * A zero amount always succeeds as a no-op: no balance changes,
     * and it does not even initialize an account for an unknown user.
     */
    @Test
    void zeroAmountIsNoOp() {
        // zero transfer/freeze always succeeds and changes nothing:
        assertTrue(service.tryTransfer(Transfer.AVAILABLE_TO_AVAILABLE, USER_A, USER_B, AssetEnum.USD, BigDecimal.ZERO, true));
        assertTrue(service.tryFreeze(USER_A, AssetEnum.USD, BigDecimal.ZERO));
        assertBDEquals(12300, service.getAsset(USER_A, AssetEnum.USD).getAvailable());
        assertBDEquals(45600, service.getAsset(USER_B, AssetEnum.USD).getAvailable());

        // zero amount does not even initialize an account for an unknown user:
        assertTrue(service.tryTransfer(Transfer.AVAILABLE_TO_AVAILABLE, 9999L, USER_A, AssetEnum.USD, BigDecimal.ZERO, true));
        assertNull(service.getAsset(9999L, AssetEnum.USD));
    }

    /**
     * Negative amounts are programming errors, not business failures:
     * every entry point rejects them with IllegalArgumentException and leaves balances untouched.
     */
    @Test
    void negativeAmountRejected() {
        assertThrows(IllegalArgumentException.class, () -> {
            service.tryTransfer(Transfer.AVAILABLE_TO_AVAILABLE, USER_A, USER_B, AssetEnum.USD, new BigDecimal("-1"), true);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            service.tryFreeze(USER_A, AssetEnum.USD, new BigDecimal("-0.01"));
        });
        assertThrows(IllegalArgumentException.class, () -> {
            service.unfreeze(USER_A, AssetEnum.USD, new BigDecimal("-100"));
        });
        // nothing changed:
        assertBDEquals(12300, service.getAsset(USER_A, AssetEnum.USD).getAvailable());
        assertBDEquals(0, service.getAsset(USER_A, AssetEnum.USD).getFrozen());
    }

    /**
     * Boundary check: transferring or freezing the exact full balance succeeds
     * (the insufficiency check is strictly "less than"), leaving a zero balance behind.
     */
    @Test
    void exactBalanceOperations() {
        // transfer exact full balance ok:
        service.transfer(Transfer.AVAILABLE_TO_AVAILABLE, USER_A, USER_B, AssetEnum.USD, new BigDecimal("12300"));
        assertBDEquals(0, service.getAsset(USER_A, AssetEnum.USD).getAvailable());
        assertBDEquals(57900, service.getAsset(USER_B, AssetEnum.USD).getAvailable());

        // now A has nothing left:
        assertFalse(service.tryFreeze(USER_A, AssetEnum.USD, BigDecimal.ONE));

        // freeze exact full balance ok:
        assertTrue(service.tryFreeze(USER_B, AssetEnum.USD, new BigDecimal("57900")));
        assertBDEquals(0, service.getAsset(USER_B, AssetEnum.USD).getAvailable());
        assertBDEquals(57900, service.getAsset(USER_B, AssetEnum.USD).getFrozen());
    }

    /**
     * Self-transfers: from and to resolve to the same Asset object,
     * so subtracting and adding must net out without creating or destroying money.
     */
    @Test
    void sameAccountOperations() {
        // available -> available to self creates nothing:
        service.transfer(Transfer.AVAILABLE_TO_AVAILABLE, USER_A, USER_A, AssetEnum.USD, new BigDecimal("1000"));
        assertBDEquals(12300, service.getAsset(USER_A, AssetEnum.USD).getAvailable());

        // freeze then unfreeze to self, partially:
        service.tryFreeze(USER_A, AssetEnum.BTC, new BigDecimal("5"));
        service.unfreeze(USER_A, AssetEnum.BTC, new BigDecimal("2"));
        assertBDEquals(9, service.getAsset(USER_A, AssetEnum.BTC).getAvailable());
        assertBDEquals(3, service.getAsset(USER_A, AssetEnum.BTC).getFrozen());
    }

    /**
     * Accounts are initialized lazily: a user/asset pair does not exist until the first real transfer,
     * and a newly created asset starts with a zero frozen balance.
     */
    @Test
    void lazyInitNewUserAndAsset() {
        Long NEW_USER = 5000L;
        // untouched user/asset does not exist:
        assertNull(service.getAsset(NEW_USER, AssetEnum.BTC));
        assertTrue(service.getAssets(NEW_USER).isEmpty());
        assertNull(service.getAsset(USER_B, AssetEnum.BTC));

        // first deposit initializes the account with zero frozen:
        service.tryTransfer(Transfer.AVAILABLE_TO_AVAILABLE, DEBT, NEW_USER, AssetEnum.BTC, new BigDecimal("5"), false);
        assertBDEquals(5, service.getAsset(NEW_USER, AssetEnum.BTC).getAvailable());
        assertBDEquals(0, service.getAsset(NEW_USER, AssetEnum.BTC).getFrozen());
        assertBDEquals(-51, service.getAsset(DEBT, AssetEnum.BTC).getAvailable());

        // existing user, first time touching a new asset:
        service.transfer(Transfer.AVAILABLE_TO_AVAILABLE, NEW_USER, USER_B, AssetEnum.BTC, new BigDecimal("2"));
        assertBDEquals(2, service.getAsset(USER_B, AssetEnum.BTC).getAvailable());
    }

    /**
     * Initial ledger, funded by issuing assets from the system debt account:
     * USER_A: USD=12300, BTC=12
     * USER_B: USD=45600
     * USER_C: BTC=34
     */
    void init(){
        service.tryTransfer(Transfer.AVAILABLE_TO_AVAILABLE, DEBT, USER_A, AssetEnum.USD, BigDecimal.valueOf(12300), false);
        service.tryTransfer(Transfer.AVAILABLE_TO_AVAILABLE, DEBT, USER_A, AssetEnum.BTC, BigDecimal.valueOf(12), false);

        service.tryTransfer(Transfer.AVAILABLE_TO_AVAILABLE, DEBT, USER_B, AssetEnum.USD, BigDecimal.valueOf(45600), false);
        service.tryTransfer(Transfer.AVAILABLE_TO_AVAILABLE, DEBT, USER_C, AssetEnum.BTC, BigDecimal.valueOf(34), false);

        assertBDEquals(-57900, service.getAsset(DEBT, AssetEnum.USD).getAvailable());
        assertBDEquals(-46, service.getAsset(DEBT, AssetEnum.BTC).getAvailable());
        assertBDEquals(12300, service.getAsset(USER_A, AssetEnum.USD).getAvailable());
        assertBDEquals(12, service.getAsset(USER_A, AssetEnum.BTC).getAvailable());
        assertBDEquals(45600, service.getAsset(USER_B, AssetEnum.USD).getAvailable());
        assertBDEquals(34, service.getAsset(USER_C, AssetEnum.BTC).getAvailable());
    }

    /**
     * Global invariants checked after every test:
     * the per-asset sum of available + frozen over all accounts is zero,
     * normal users never hold negative balances, and the debt account never holds frozen funds.
     */
    void verify(){
        BigDecimal totalUSD = BigDecimal.ZERO;
        BigDecimal totalBTC = BigDecimal.ZERO;
        for (Long userId : service.getUserAssets().keySet()) {
            var assetUSD=service.getAsset(userId, AssetEnum.USD);
            if(assetUSD!=null){
                totalUSD=totalUSD.add(assetUSD.getAvailable()).add(assetUSD.getFrozen());
            }
            var assetBTC=service.getAsset(userId, AssetEnum.BTC);
            if(assetBTC!=null){
                totalBTC=totalBTC.add(assetBTC.getAvailable()).add(assetBTC.getFrozen());
            }
            for (var asset : new Asset[]{assetUSD, assetBTC}) {
                if (asset == null) {
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
