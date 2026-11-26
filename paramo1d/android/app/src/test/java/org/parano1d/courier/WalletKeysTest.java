package org.parano1d.courier;

import org.junit.Test;
import static org.junit.Assert.*;

public class WalletKeysTest {
    // iOS Native/src/lib.rs fixture: master key [7; 32] derives this address.
    private static final String FIXTURE = "o1lhhsuqy5d35c3zarhnuj7kpywrke5wwsr8ukc6x8y4dvhe38vs2qzg2759";

    @Test public void acceptsTheIosFixtureAddress() { assertTrue(WalletKeys.validAddress(FIXTURE)); }

    @Test public void rejectsBadAddresses() {
        assertFalse(WalletKeys.validAddress(null));
        assertFalse(WalletKeys.validAddress(FIXTURE.toUpperCase()));
        assertFalse(WalletKeys.validAddress(FIXTURE.substring(0, 59) + (FIXTURE.endsWith("9") ? "8" : "9")));
        assertFalse(WalletKeys.validAddress(FIXTURE.substring(0, 59)));
        assertFalse(WalletKeys.validAddress("o2" + FIXTURE.substring(2)));
    }

    @Test public void masterKeyKeepsOnlyHexAndNeeds64() throws Exception {
        String key = "07".repeat(32);
        assertEquals(key, WalletKeys.masterKey(key.toUpperCase()));
        assertEquals(key, WalletKeys.masterKey("0707 0707-" + "07".repeat(28)));
        try { WalletKeys.masterKey("07".repeat(31)); fail(); } catch (Exception expected) { }
        try { WalletKeys.masterKey("0g".repeat(32)); fail(); } catch (Exception expected) { }
        try { WalletKeys.masterKey("07".repeat(33)); fail(); } catch (Exception expected) { }
    }
}
