package com.itranswarp.exchange;

import static org.junit.jupiter.api.Assertions.*;
import com.itranswarp.exchange.assets.AssetService;
import com.itranswarp.exchange.assets.Transfer;
import com.itranswarp.exchange.enums.AssetEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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
        }
        assertBDEquals(0, totalBTC);
        assertBDEquals(0, totalUSD);
    }
    void assertBDEquals(long value, BigDecimal bd){
        assertBDEquals(String.valueOf(value), bd);
    }
    void assertBDEquals(String value, BigDecimal bd){
        assertEquals(0, new BigDecimal(value).compareTo(bd), String.format("Expected %s but actual %s.", value, bd.toPlainString()));
    }
}
