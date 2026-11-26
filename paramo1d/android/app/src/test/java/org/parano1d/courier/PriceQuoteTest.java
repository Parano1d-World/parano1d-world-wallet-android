package org.parano1d.courier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

/** SafeTrade is the first NOID price source (iOS 193 PriceSourceTests, same live response). */
public class PriceQuoteTest {
    /** The exact response SafeTrade's public ticker returned on 24 Nov 2026; only "at" is moved so freshness applies. */
    private static JSONObject live(long atSeconds) throws Exception {
        String text = "{\"at\":\"AT\",\"ticker\":{\"buy\":\"0.094388\",\"sell\":\"0.09042\",\"at\":\"AT\",\"avg_price\":\"0.0986632\",\"high\":\"0.12539956\",\"last\":\"0.094388\",\"low\":\"0.08125242\",\"open\":\"0.10746035\",\"price_change_percent\":\"-12.16%\",\"volume\":\"43343.14439821\",\"amount\":\"452413.39\",\"vol\":\"43343.14439821\"}}";
        return new JSONObject(text.replace("AT", String.valueOf(atSeconds)));
    }
    private static long now() { return System.currentTimeMillis(); }
    private static void refused(JSONObject body) {
        try { PriceQuote.safetrade(body); fail("refused: " + body); } catch (Exception expected) { }
    }

    @Test public void liveSafeTradeResponseGivesAFreshPrice() throws Exception {
        JSONObject quote = PriceQuote.safetrade(live(now() / 1000));
        assertEquals("0.094388", quote.getString("usd"));
        assertTrue(PriceQuote.fresh(quote, now()));
    }
    @Test public void thinMarketIsRefused() throws Exception {
        JSONObject body = live(now() / 1000);
        body.getJSONObject("ticker").put("volume", "12").put("vol", "12");
        refused(body);
        body.getJSONObject("ticker").put("volume", "99.99").put("vol", "99.99");
        refused(body);
    }
    @Test public void zeroOrMissingPriceIsRefused() throws Exception {
        JSONObject body = live(now() / 1000);
        body.getJSONObject("ticker").put("last", "0");
        refused(body);
        body.getJSONObject("ticker").remove("last");
        refused(body);
    }
    @Test public void errorBodyIsRefused() throws Exception {
        refused(new JSONObject().put("error", "public.market.doesnt_exist"));
        refused(new JSONObject().put("errors", new org.json.JSONArray().put("record.not_found")));
    }
    @Test public void oldTimeIsRefused() throws Exception {
        JSONObject old = PriceQuote.safetrade(live(now() / 1000 - 3600));
        assertFalse("an hour old is not used", PriceQuote.fresh(old, now()));
        JSONObject body = live(0);
        refused(body);
    }
}
