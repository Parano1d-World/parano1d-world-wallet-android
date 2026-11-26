package org.parano1d.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** NOID balance complication. Updated when the phone publishes new state; shows "Hidden" with Hide balances on. */
class BalanceComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        PhoneLink.loadSaved(this)
        val state = PhoneLink.state.value
        val value = when {
            state == null -> "—"
            state.hidden -> "Hidden"
            state.balance == null -> "—"
            else -> short(state.balance)
        }
        return build(request.complicationType, value, state?.height ?: 0)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, "88.3", 322_099)

    private fun build(type: ComplicationType, value: String, height: Long): ComplicationData? {
        val tap = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val description = PlainComplicationText.Builder("NOID balance $value").build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(PlainComplicationText.Builder(value).build(), description)
                .setTitle(PlainComplicationText.Builder("NOID").build()).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                PlainComplicationText.Builder(if (value == "Hidden" || value == "—") "NOID $value" else "$value NOID").build(), description)
                .setTitle(PlainComplicationText.Builder(if (height > 0) "Verified · block $height" else "Parano1d").build()).setTapAction(tap).build()
            else -> null
        }
    }

    private fun short(balance: String): String {
        val number = balance.toBigDecimalOrNull() ?: return balance
        return when {
            number >= java.math.BigDecimal(1_000_000) -> number.divide(java.math.BigDecimal(1_000_000)).setScale(1, java.math.RoundingMode.DOWN).toPlainString() + "M"
            number >= java.math.BigDecimal(10_000) -> number.divide(java.math.BigDecimal(1_000)).setScale(1, java.math.RoundingMode.DOWN).toPlainString() + "K"
            else -> number.setScale(1, java.math.RoundingMode.DOWN).toPlainString()
        }
    }

    companion object {
        fun requestUpdate(context: Context) {
            runCatching {
                ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, BalanceComplicationService::class.java)).requestUpdateAll()
            }
        }
    }
}
