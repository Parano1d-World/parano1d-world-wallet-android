package org.parano1d.courier;

import org.json.JSONObject;
import java.math.BigDecimal;

/**
 * NOID/USD quote rules (iOS 193 PriceSources). Pure, so it is unit tested.
 * SafeTrade's public NOID/USDT ticker is the first source; USDT is treated as $1. A SafeTrade price is
 * used only when the market traded at least $100 (USDT) in 24 hours. Every quote must be positive and at
 * most 30 minutes old. The source name is kept internally only; the app never shows it.
 */
final class PriceQuote {
    static final long MAX_AGE_MS = 30 * 60_000L;
    static final BigDecimal MIN_VOLUME_USDT = new BigDecimal(100);
    private PriceQuote() { }

    /** A positive decimal from a JSON number or string, or null. */
    static BigDecimal positive(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        try { BigDecimal number = new BigDecimal(String.valueOf(value).trim()); return number.signum() > 0 ? number : null; }
        catch (NumberFormatException invalid) { return null; }
    }
    static String decimal(Object value) throws Exception {
        BigDecimal number = positive(value);
        if (number == null) throw new Exception("Invalid price.");
        return number.stripTrailingZeros().toPlainString();
    }
    static boolean fresh(JSONObject quote, long now) {
        if (quote == null) return false;
        long updated = quote.optLong("updated_ms");
        return positive(quote.opt("usd")) != null && updated - now <= 60_000 && now - updated <= MAX_AGE_MS;
    }
    /**
     * SafeTrade (Peatio) public ticker: {"at":"<unix seconds>","ticker":{"last", "volume"/"vol" = 24h volume
     * in USDT, "amount" = 24h volume in NOID, ...}}. Refuses a missing or zero price, a thin market, an error
     * body and a missing or impossible time. (Freshness is checked separately, like every source.)
     */
    static JSONObject safetrade(JSONObject body) throws Exception {
        JSONObject ticker = body.optJSONObject("ticker");
        if (ticker == null) throw new Exception("Invalid price.");
        String usd = decimal(ticker.opt("last"));
        BigDecimal volume = positive(ticker.opt("volume"));
        if (volume == null) volume = positive(ticker.opt("vol"));
        if (volume == null || volume.compareTo(MIN_VOLUME_USDT) < 0) throw new Exception("Invalid price.");
        BigDecimal at = positive(body.opt("at"));
        if (at == null) at = positive(ticker.opt("at"));
        if (at == null || at.compareTo(new BigDecimal(1_600_000_000L)) <= 0 || at.compareTo(new BigDecimal(100_000_000_000L)) >= 0) throw new Exception("Invalid price.");
        return new JSONObject().put("usd", usd).put("updated_ms", at.movePointRight(3).longValue()).put("source", "SafeTrade");
    }
}
