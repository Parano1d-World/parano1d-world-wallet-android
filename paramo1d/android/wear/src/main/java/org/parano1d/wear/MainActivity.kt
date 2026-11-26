package org.parano1d.wear

import android.Manifest
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.input.RemoteInputIntentHelper
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

// iOS Theme.mint on black by default; the optional Parano1d World palette when the phone uses it.
// Same values as the Apple Watch's WatchTheme (iOS 168): accent, secondary text and panel per phone appearance.
val Neon: Color get() = when { WatchStyle.orange.value -> Color(0xFFFF9B36); WatchStyle.world.value -> Color(0xFF9BDE7E); WatchStyle.cyber.value -> Color(0xFF00FF80); else -> Color(0xFF30E374) }
val Muted: Color get() = when { WatchStyle.world.value -> Color(0xFFA9B8AC); WatchStyle.cyber.value -> if (WatchStyle.orange.value) Color(0xFFCBBCA9) else Color(0xFF9FC7AF); else -> Color(0xFF9AA59C) }
val PanelColor: Color get() = when { WatchStyle.world.value -> Color(0xFF0C191B); WatchStyle.cyber.value -> if (WatchStyle.orange.value) Color(0xFF241E17) else Color(0xFF102019); else -> Color(0xFF0B1716) }
val Warn = Color(0xFFFFB300)

sealed class Screen(val needsSession: Boolean = false) {
    object Home : Screen()
    object Pay : Screen()
    object SendPick : Screen()
    data class SendAmount(val contact: Contact) : Screen()
    data class Review(val review: JSONObject, val contact: Contact?) : Screen()
    data class Sent(val txid: String) : Screen()
    object RequestPick : Screen(true)
    data class RequestAmount(val contact: Contact) : Screen(true)
    object Receipts : Screen()
    object Receive : Screen()
    object Chats : Screen(true)
    data class Conversation(val chat: String, val name: String) : Screen(true)
    object Nfts : Screen()
    object More : Screen()
    data class Pin(val then: Screen) : Screen()
    data class Confirm(val event: JSONObject) : Screen()
    data class Handoff(val message: String, val contactId: String, val amount: String) : Screen()
}

class MainActivity : ComponentActivity() {
    private val nav = mutableStateListOf<Screen>(Screen.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneLink.loadSaved(this)
        handleIntent(intent)
        setContent { MaterialTheme { App(nav) } }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra("confirm")?.let { raw -> runCatching { JSONObject(raw) }.getOrNull()?.let { nav.add(Screen.Confirm(it)) } }
        if (intent?.getStringExtra("open") == "receipts") nav.add(Screen.Receipts)
        // Message alert tapped: open that conversation (behind the PIN if the session has ended), like the Apple Watch app.
        intent?.getStringExtra("chat")?.let { chat ->
            if (chat.matches(Regex("[dg]:[0-9A-Za-z_-]{1,128}"))) {
                val target = Screen.Conversation(chat, intent.getStringExtra("name") ?: "Chat")
                nav.clear(); nav.add(Screen.Home); nav.add(Screen.Chats)
                nav.add(if (PhoneLink.session == null) Screen.Pin(target) else target)
            }
        }
        intent?.removeExtra("confirm"); intent?.removeExtra("open"); intent?.removeExtra("chat"); intent?.removeExtra("name")
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { PhoneLink.hello(this@MainActivity) }
        PhoneLink.pendingConfirm.value?.let { event -> if (nav.none { it is Screen.Confirm }) nav.add(Screen.Confirm(event)) }
    }

    // Like the Apple Watch app: the PIN session survives wrist-down. The phone ends it after
    // 2 idle minutes or 30 minutes at most, and "Lock watch session" in More ends it at once.
}

// ---------------------------------------------------------------- Shell

@Composable
fun App(nav: SnapshotStateList<Screen>) {
    val push: (Screen) -> Unit = { target -> nav.add(if (target.needsSession && PhoneLink.session == null) Screen.Pin(target) else target) }
    val pop: () -> Unit = { if (nav.size > 1) nav.removeAt(nav.lastIndex) }
    val replace: (Screen) -> Unit = { target -> if (nav.size > 1) nav.removeAt(nav.lastIndex); push(target) }
    BackHandler(enabled = nav.size > 1) { pop() }
    val confirm by PhoneLink.pendingConfirm.collectAsState()
    LaunchedEffect(confirm) { confirm?.let { event -> if (nav.none { it is Screen.Confirm }) nav.add(Screen.Confirm(event)) } }
    val nav2 = Nav(push, pop, replace) { target -> nav.clear(); nav.add(Screen.Home); if (target != Screen.Home) push(target) }
    when (val screen = nav.last()) {
        Screen.Home -> HomeScreen(nav2)
        Screen.Pay -> PayScreen(nav2)
        Screen.SendPick -> SendPickScreen(nav2)
        is Screen.SendAmount -> AmountScreen(nav2, screen.contact, request = false)
        is Screen.Review -> ReviewScreen(nav2, screen.review, screen.contact)
        is Screen.Sent -> SentScreen(nav2, screen.txid)
        Screen.RequestPick -> RequestPickScreen(nav2)
        is Screen.RequestAmount -> AmountScreen(nav2, screen.contact, request = true)
        Screen.Receipts -> ReceiptsScreen()
        Screen.Receive -> ReceiveScreen()
        Screen.Chats -> ChatsScreen(nav2)
        is Screen.Conversation -> ConversationScreen(nav2, screen.chat, screen.name)
        Screen.Nfts -> NftsScreen()
        Screen.More -> MoreScreen(nav2)
        is Screen.Pin -> PinScreen(nav2, screen.then)
        is Screen.Confirm -> ConfirmScreen(nav2, screen.event)
        is Screen.Handoff -> HandoffScreen(nav2, screen)
    }
}

class Nav(val push: (Screen) -> Unit, val pop: () -> Unit, val replace: (Screen) -> Unit, val home: (Screen) -> Unit)

@Composable
fun Page(title: String? = null, content: ScalingLazyListScope.() -> Unit) {
    val listState = rememberScalingLazyListState()
    val state by PhoneLink.state.collectAsState()
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (state?.demo == true) item { Text("DEMO DATA · no phone", color = Warn, fontSize = 10.sp) }
            if (title != null) item { Text(title, color = Neon, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            content()
        }
    }
}

fun ScalingLazyListScope.note(text: String, color: Color = Muted) = item {
    Text(text, color = color, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
}

fun ScalingLazyListScope.action(label: String, secondary: String? = null, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) = item {
    Chip(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = if (secondary != null) { { Text(secondary, maxLines = 2, overflow = TextOverflow.Ellipsis) } } else null,
        colors = if (primary) ChipDefaults.chipColors(backgroundColor = Neon, contentColor = Color.Black, secondaryContentColor = Color.Black) else ChipDefaults.secondaryChipColors(),
    )
}

fun usdValue(balance: String?, price: String?): String? = runCatching {
    val value = java.math.BigDecimal(balance!!).multiply(java.math.BigDecimal(price!!))
    java.text.NumberFormat.getCurrencyInstance(java.util.Locale.US).format(value)
}.getOrNull()

fun fromPhone(connected: Boolean, updatedAt: Long): String {
    if (updatedAt <= 0L) return if (connected) "From phone" else "Phone not connected"
    val minutes = ((System.currentTimeMillis() - updatedAt) / 60_000L).coerceAtLeast(0)
    val age = if (minutes < 1) "just now" else "$minutes min ago"
    return if (connected) "From phone · $age" else "Phone not connected · saved $age"
}

fun shortAddress(address: String) = if (address.length <= 14) address else address.take(8) + "…" + address.takeLast(4)

@Composable
fun Avatar(name: String, id: String) {
    val hue = (id.hashCode() and 0xFFFFFF) or 0xFF000000.toInt()
    Box(Modifier.size(28.dp).background(Color(hue), CircleShape), contentAlignment = Alignment.Center) {
        // No "?" or empty circle: a person glyph when there's no name.
        val letter = name.trim().take(1).uppercase()
        if (letter.isNotEmpty()) Text(letter, color = Color.White, fontWeight = FontWeight.Bold)
        else androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
            drawCircle(Color.White, radius = size.minDimension * 0.22f, center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height * 0.3f))
            drawArc(Color.White, 180f, 180f, true, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.12f, size.height * 0.6f), size = androidx.compose.ui.geometry.Size(size.width * 0.76f, size.height * 0.76f))
        }
    }
}

/** Runs a phone request with busy/error handling and session/hand-off routing. */
class Runner(private val nav: Nav, private val scope: CoroutineScope, private val current: () -> Screen) {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (e: PhoneError) {
                when (e.code) {
                    "session_expired" -> nav.replace(Screen.Pin(current()))
                    "handoff" -> nav.push(Screen.Handoff(e.message ?: "Continue on your phone.", e.contactId, e.amount))
                    else -> error = e.message
                }
            } catch (e: Exception) { error = e.message ?: "Something went wrong" }
            finally { busy = false }
        }
    }
}

@Composable
fun rememberRunner(nav: Nav, current: Screen): Runner {
    val scope = rememberCoroutineScope()
    return remember(current) { Runner(nav, scope) { current } }
}

// ---------------------------------------------------------------- Home and Pay

@Composable
fun HomeScreen(nav: Nav) {
    val state by PhoneLink.state.collectAsState()
    val connected by PhoneLink.connected.collectAsState()
    Page {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Image(androidx.compose.ui.res.painterResource(R.drawable.brand_emblem), contentDescription = null, modifier = Modifier.size(22.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Official wordmark: PARANO1D with the green 1, WORLD underneath in green.
                    Text(androidx.compose.ui.text.buildAnnotatedString {
                        append("PARANO")
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = Neon)); append("1"); pop()
                        append("D")
                    }, color = Color(0xFFE6E8E7), fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 0.5.sp)
                    Text("WORLD", color = Neon, fontWeight = FontWeight.Bold, fontSize = 8.sp, letterSpacing = 3.sp)
                }
            }
        }
        // Account name under the brand mark, like the phone header.
        item { Text(if (WatchStyle.world.value) "MAIN WALLET" else "Main wallet", color = Muted, fontSize = 10.sp, letterSpacing = if (WatchStyle.world.value) 1.sp else 0.sp) }
        val s = state
        if (s == null) { note("Open Parano1d on your phone to connect.") }
        else if (!s.hasWallet) { note("Set up your wallet in the Parano1d phone app.") }
        else {
            // iOS 182 watch Home: NOID price chip above the balance, then a balance card in the look's panel colour.
            val world = WatchStyle.world.value; val cyber = WatchStyle.cyber.value
            s.priceUsd?.let { price ->
                item {
                    Text((if (world) "NOID $" else "NOID $") + price.take(7), color = Neon, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        letterSpacing = if (world) 1.sp else 0.sp,
                        modifier = Modifier.border(1.dp, Neon.copy(alpha = 0.45f), RoundedCornerShape(50)).background(PanelColor, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp))
                }
            }
            item {
                val shape = RoundedCornerShape(if (cyber) 4.dp else 14.dp)
                val outline = when { cyber -> Modifier.border(2.dp, Neon.copy(alpha = 0.6f), shape); world -> Modifier.border(1.dp, Neon.copy(alpha = 0.6f), shape); else -> Modifier.border(1.dp, Neon.copy(alpha = 0.25f), shape) }
                val figures = if (cyber) FontFamily.Monospace else FontFamily.Default
                Column(Modifier.fillMaxWidth().then(outline).background(PanelColor, shape).padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (world) "BALANCE" else "Balance", color = Muted, fontSize = 10.sp, letterSpacing = if (world) 1.sp else 0.sp, fontFamily = figures)
                    Text(when { s.hidden -> "Hidden"; s.balance == null -> "—"; else -> s.balance }, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = figures)
                    Text("NOID", color = Neon, fontSize = 12.sp, fontFamily = figures)
                    // USD line with the phone's wording (hidden with balances).
                    if (!s.hidden) {
                        val usd = usdValue(s.balance, s.priceUsd)
                        val line = when { s.balance == null -> "USD after the balance check"; usd != null -> "≈ $usd USD"; else -> "USD value unavailable" }
                        Text(if (world) line.uppercase() else line, color = Muted, fontSize = 11.sp, letterSpacing = if (world) 1.sp else 0.sp)
                    }
                }
            }
            note(if (s.height > 0) "Verified · block ${"%,d".format(s.height)}" else "Waiting for the phone to verify")
            if (connected) note(fromPhone(true, PhoneLink.updatedAt.value)) else note(fromPhone(false, PhoneLink.updatedAt.value), Warn)
        }
        action("Pay", "Send · Request · Receipts · Receive", primary = true) { nav.push(Screen.Pay) }
        action("Chat", state?.let { if (it.unread > 0) "${it.unread} unread" + (if (it.openRequests > 0) " · ${it.openRequests} request(s)" else "") else null }) { nav.push(Screen.Chats) }
        action("NFTs") { nav.push(Screen.Nfts) }
        action("More") { nav.push(Screen.More) }
    }
}

@Composable
fun PayScreen(nav: Nav) = Page("Pay") {
    action("Send NOID", "To a contact", primary = true) { nav.push(Screen.SendPick) }
    action("Request NOID") { nav.push(Screen.RequestPick) }
    action("Receipts") { nav.push(Screen.Receipts) }
    action("Receive") { nav.push(Screen.Receive) }
}

fun ScalingLazyListScope.contactItems(contacts: List<Contact>, onPick: (Contact) -> Unit) {
    contacts.forEach { contact ->
        item {
            Chip(onClick = { onPick(contact) }, modifier = Modifier.fillMaxWidth(),
                icon = { Avatar(contact.name, contact.id) },
                label = { Text(contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = { Text(if (contact.addressChanged) "Address changed" else shortAddress(contact.address), color = if (contact.addressChanged) Warn else Muted, maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors())
        }
    }
}

@Composable
fun rememberDictation(onText: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence("text")?.toString() }
        if (!text.isNullOrBlank()) onText(text.trim())
    }
    return {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder("text").setLabel("Say or type").build()))
        launcher.launch(intent)
    }
}

@Composable
fun SendPickScreen(nav: Nav) {
    val state by PhoneLink.state.collectAsState()
    var query by remember { mutableStateOf("") }
    val search = rememberDictation { query = it }
    Page("Send to") {
        val s = state
        when {
            s == null -> note("Phone not connected.")
            !s.sendsEnabled -> note("Watch sends are off. Turn them on in the phone app: Settings → Wallet → Wear OS watch.", Warn)
            s.contacts.isEmpty() -> note("Add contacts in the phone app first.")
            else -> {
                action(if (query.isEmpty()) "Search" else "Search: $query") { search() }
                if (query.isNotEmpty()) action("Clear search") { query = "" }
                contactItems(s.contacts.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }) { nav.push(Screen.SendAmount(it)) }
            }
        }
    }
}

@Composable
fun RequestPickScreen(nav: Nav) {
    val state by PhoneLink.state.collectAsState()
    Page("Request from") {
        val contacts = state?.contacts.orEmpty()
        if (contacts.isEmpty()) note("Add contacts in the phone app first.") else contactItems(contacts) { nav.push(Screen.RequestAmount(it)) }
    }
}

// ---------------------------------------------------------------- Keypad

@Composable
fun Keypad(decimal: Boolean, onKey: (Char) -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val rows = listOf("123", "456", "789", if (decimal) ".0<" else " 0<")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    if (key == ' ') Box(Modifier.size(ButtonDefaults.SmallButtonSize))
                    else Button(onClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove); onKey(key) }, modifier = Modifier.size(ButtonDefaults.SmallButtonSize), colors = ButtonDefaults.secondaryButtonColors()) {
                        Text(if (key == '<') "⌫" else key.toString(), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

fun edit(value: String, key: Char, max: Int, decimal: Boolean): String = when {
    key == '<' -> value.dropLast(1)
    value.length >= max -> value
    key == '.' && (!decimal || value.contains('.')) -> value
    key == '.' && value.isEmpty() -> "0."
    decimal && value.contains('.') && value.substringAfter('.').length >= 6 -> value
    else -> value + key
}

@Composable
fun AmountScreen(nav: Nav, contact: Contact, request: Boolean) {
    var amount by remember { mutableStateOf("") }
    val current = if (request) Screen.RequestAmount(contact) else Screen.SendAmount(contact)
    val runner = rememberRunner(nav, current)
    val context = LocalContext.current
    Page(if (request) "Request from ${contact.name}" else "Send to ${contact.name}") {
        item { Text(if (amount.isEmpty()) "0" else amount, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        item { Text("NOID", color = Neon, fontSize = 11.sp) }
        item { Keypad(decimal = true) { amount = edit(amount, it, 14, true) } }
        runner.error?.let { note(it, Warn) }
        action(if (runner.busy) "Checking…" else if (request) "Send request" else "Review", primary = true, enabled = amount.toBigDecimalOrNull()?.signum() == 1 && !runner.busy) {
            runner.run {
                if (request) {
                    PhoneLink.call(context, "request_create", JSONObject().put("contact_id", contact.id).put("amount", amount))
                    nav.home(Screen.Home)
                } else {
                    val review = PhoneLink.call(context, "send_review", JSONObject().put("contact_id", contact.id).put("amount", amount), timeoutMs = 120_000)
                    nav.replace(Screen.Review(review, contact))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Review and approve

@Composable
fun ReviewScreen(nav: Nav, review: JSONObject, contact: Contact?) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(review.optInt("expires_seconds", 90)) }
    val runner = rememberRunner(nav, Screen.Review(review, contact))
    LaunchedEffect(review) { while (remaining > 0) { delay(1000); remaining-- } }
    Page("Check payment") {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Avatar(review.optString("contact_name"), contact?.id ?: review.optString("to"))
                Column { Text(review.optString("contact_name"), fontWeight = FontWeight.Bold); Text(shortAddress(review.optString("to")), color = Muted, fontSize = 11.sp) }
            }
        }
        if (review.optBoolean("using_saved_state")) note("Prepared from the balance your phone last verified.")
        if (remaining <= 0) {
            note("This review expired. Review the payment again.", Warn)
            action("Back") { nav.pop() }
        } else if (!confirmed) {
            // Step 1: the full address and the amount must be confirmed before the PIN step.
            item { Text("SEND TO", color = Muted, fontSize = 10.sp) }
            item { Text(review.optString("to").chunked(4).joinToString(" "), color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 10.dp)) }
            item { Text("AMOUNT", color = Muted, fontSize = 10.sp) }
            item { Text("${review.optString("amount")} NOID", color = Neon, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
            note("Fee ${review.optString("fee")} NOID · Total ${review.optString("total")} NOID", Color.White)
            note("Expires in ${remaining}s")
            action("Address and amount are correct", primary = true) { confirmed = true }
            action("Cancel") { nav.pop() }
        } else {
            note("${review.optString("amount")} NOID to ${shortAddress(review.optString("to"))}", Color.White)
            note("Expires in ${remaining}s · enter wallet PIN")
            item { Text("●".repeat(pin.length).ifEmpty { "PIN" }, color = if (pin.isEmpty()) Muted else Color.White, fontSize = 18.sp) }
            item { Keypad(decimal = false) { pin = edit(pin, it, 8, false) } }
            runner.error?.let { note(it, Warn) }
            action(if (runner.busy) "Signing on phone…" else "Confirm and send", primary = true, enabled = pin.length >= 4 && !runner.busy) {
                runner.run {
                    try {
                        val sent = PhoneLink.call(context, "send_approve", JSONObject().put("review_id", review.optString("review_id")).put("pin", pin)
                            .put("confirmed_to", review.optString("to")).put("confirmed_amount", review.optString("amount")), timeoutMs = 120_000)
                        nav.replace(Screen.Sent(sent.optString("txid")))
                    } finally { pin = "" }
                }
            }
        }
    }
}

@Composable
fun SentScreen(nav: Nav, txid: String) = Page("Signed") {
    note("Your phone signed the payment and is sending it to the network. A receipt appears here once it's final.", Color.White)
    note(shortAddress(txid))
    action("Done", primary = true) { nav.home(Screen.Home) }
}

@Composable
fun HandoffScreen(nav: Nav, screen: Screen.Handoff) {
    val context = LocalContext.current
    val runner = rememberRunner(nav, screen)
    var sent by remember { mutableStateOf(false) }
    Page("Continue on phone") {
        note(screen.message, Color.White)
        runner.error?.let { note(it, Warn) }
        if (sent) note("Check your phone to finish.", Neon)
        else action("Continue on phone", primary = true, enabled = !runner.busy) {
            runner.run { PhoneLink.call(context, "handoff", JSONObject().put("contact_id", screen.contactId).put("amount", screen.amount).put("reason", screen.message)); sent = true }
        }
        action("Back") { nav.pop() }
    }
}

// ---------------------------------------------------------------- Receipts and Receive

@Composable
fun ReceiptsScreen() {
    val state by PhoneLink.state.collectAsState()
    Page("Receipts") {
        val receipts = state?.receipts.orEmpty()
        if (receipts.isEmpty()) note("Receipts appear here once your phone verifies a payment is final.")
        receipts.forEach { r ->
            val received = r.direction == "received"
            action(
                (if (received) "+" else "−") + "${r.amount} NOID",
                buildString {
                    append(if (received) "Received" else "Sent")
                    if (r.counterparty.isNotEmpty()) append(if (received) " from " else " to ").append(r.counterparty)
                    if (r.height > 0) append(" · block ${r.height}")
                },
            ) { }
        }
    }
}

@Composable
fun ReceiveScreen() {
    val state by PhoneLink.state.collectAsState()
    val address = state?.address.orEmpty()
    val qr = remember(address) { if (address.isEmpty()) null else qrBitmap(address, 360) }
    Page("Receive NOID") {
        if (qr == null) note("Open Parano1d on your phone to load your address.")
        else {
            item { Image(qr.asImageBitmap(), contentDescription = "Wallet address QR code", modifier = Modifier.size(120.dp).background(Color.White).padding(4.dp)) }
            note(shortAddress(address), Color.White)
        }
    }
}

fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bitmap
}

// ---------------------------------------------------------------- Chat

@Composable
fun ChatsScreen(nav: Nav) {
    val context = LocalContext.current
    val runner = rememberRunner(nav, Screen.Chats)
    var chats by remember { mutableStateOf(PhoneLink.chatsCache ?: context.getSharedPreferences("watch", android.content.Context.MODE_PRIVATE).getString("chats", null)?.let { runCatching { JSONObject(it) }.getOrNull() }) }
    // Keep the last good list if the phone can't read its chats right now (for example while it's locked).
    LaunchedEffect(Unit) { runner.run { val fresh = PhoneLink.call(context, "chats"); PhoneLink.chatsCache = fresh; chats = fresh
        // Saved without message previews: names only, like the Apple Watch's saved contact list.
        val saved = JSONObject(fresh.toString()); saved.optJSONArray("chats").objects().forEach { it.remove("preview") }; saved.optJSONObject("community")?.remove("preview")
        context.getSharedPreferences("watch", android.content.Context.MODE_PRIVATE).edit().putString("chats", saved.toString()).apply() } }
    Page("Chat") {
        runner.error?.let { note(if (chats == null) "Unlock your phone once to show your chats here." else it, Warn) }
        val list = chats
        if (list == null) note(if (runner.busy) "Loading…" else "")
        else {
            list.optJSONObject("community")?.takeIf { !it.optBoolean("paused") }?.let { community ->
                action("#parano1d", community.optString("preview").ifEmpty { "Public room" }) { nav.push(Screen.Conversation("g:parano1d", "#parano1d")) }
            }
            list.optJSONArray("chats").objects().forEach { c ->
                action(c.optString("name"), c.optString("preview").ifEmpty { null }) { nav.push(Screen.Conversation(c.optString("chat"), c.optString("name"))) }
            }
            if (list.optJSONArray("chats").objects().isEmpty()) note("Your private chats appear here once your phone shares them. Open Parano1d on your phone and unlock it.")
        }
    }
}

@Composable
fun ConversationScreen(nav: Nav, chat: String, name: String) {
    val context = LocalContext.current
    val current = Screen.Conversation(chat, name)
    val runner = rememberRunner(nav, current)
    var thread by remember { mutableStateOf(PhoneLink.threadCache[chat]) } // instant open from cache
    var refresh by remember { mutableIntStateOf(0) }
    val isPublic = chat == "g:parano1d"
    val send: (String) -> Unit = { text ->
        // Optimistic send: show the message right away, then confirm with the phone.
        val shown = JSONObject(thread?.toString() ?: "{\"messages\":[]}")
        shown.getJSONArray("messages").put(JSONObject().put("id", "local").put("mine", true).put("type", "text").put("text", text).put("status", "sending"))
        thread = shown
        runner.run { PhoneLink.call(context, "chat_send", JSONObject().put("chat", chat).put("text", text)); refresh++ }
    }
    val dictate = rememberDictation(send)
    // Refreshes quietly: cached messages stay on screen while the phone answers.
    val load: suspend () -> Unit = {
        val fresh = PhoneLink.call(context, "messages", JSONObject().put("chat", chat))
        PhoneLink.threadCache[chat] = fresh; thread = fresh
    }
    LaunchedEffect(refresh) { runner.run { load() } }
    val pulses by PhoneLink.chatPulse.collectAsState()
    LaunchedEffect(pulses[chat]) { if (pulses[chat] != null) runCatching { load() } }
    LaunchedEffect(chat) { while (true) { kotlinx.coroutines.delay(15_000); if (PhoneLink.session != null || isPublic) runCatching { load() } } }
    Page(name) {
        if (isPublic) note("Public · everyone in the community can read this", Warn)
        runner.error?.let { note(it, Warn) }
        // Newest first, like the Apple Watch: write at the top, the latest message right under it.
        action(if (isPublic) "Post…" else "Reply…", primary = true, enabled = !runner.busy) { dictate() }
        listOf("👍", "On my way", "Thanks!", "Call you soon").forEach { quick -> action(quick, enabled = !runner.busy) { send(quick) } }
        thread?.optJSONArray("messages").objects().reversed().forEach { m ->
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalAlignment = if (m.optBoolean("mine")) Alignment.End else Alignment.Start) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!m.optBoolean("mine")) Text(m.optString("sender"), color = Neon, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (m.optLong("time") > 0) Text("  " + ago(m.optLong("time")), color = Muted, fontSize = 9.sp)
                    }
                    Text(m.optString("text"), fontSize = 13.sp, color = Color.White)
                    if (m.optString("type") == "invoice" && m.optBoolean("declined")) Text("Declined", color = Muted, fontSize = 10.sp)
                }
            }
            if (m.optString("type") == "invoice" && m.optBoolean("payable")) {
                action("Pay ${m.optString("amount")} NOID", primary = true) {
                    runner.run {
                        val review = PhoneLink.call(context, "send_review", JSONObject().put("invoice_message_id", m.optString("id")), timeoutMs = 120_000)
                        val contact = PhoneLink.state.value?.contacts?.firstOrNull { "d:" + it.id == chat }
                        nav.push(Screen.Review(review, contact))
                    }
                }
                action("Decline") { runner.run { PhoneLink.call(context, "request_decline", JSONObject().put("message_id", m.optString("id"))); refresh++ } }
            }
        }
        if (!isPublic) {
            val contact = PhoneLink.state.value?.contacts?.firstOrNull { "d:" + it.id == chat }
            if (contact != null) action("Request NOID") { nav.push(Screen.RequestAmount(contact)) }
        } else note("Report or block members from your phone.")
    }
}

fun ago(ms: Long): String {
    val minutes = ((System.currentTimeMillis() - ms) / 60_000L).coerceAtLeast(0)
    return when { minutes < 1 -> "now"; minutes < 60 -> "${minutes}m"; minutes < 1440 -> "${minutes / 60}h"; else -> "${minutes / 1440}d" }
}

// ---------------------------------------------------------------- NFTs, More, PIN, Confirm

@Composable
fun NftsScreen() {
    val context = LocalContext.current
    var list by remember { mutableStateOf(PhoneLink.nftCache) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val pulse by PhoneLink.nftPulse.collectAsState()
    LaunchedEffect(tick, pulse) {
        // Until the phone has verified holdings, look again every few seconds (up to 3 minutes), like the Apple Watch.
        for (attempt in 0 until 36) {
            runCatching { PhoneLink.call(context, "nfts", timeoutMs = 45_000) }
                .onSuccess { list = it; PhoneLink.nftCache = it; error = null }
                .onFailure { if (list == null) error = it.message }
            if (list?.optJSONArray("items")?.length() ?: 0 > 0) break
            kotlinx.coroutines.delay(5_000)
        }
    }
    Page("NFTs") {
        error?.let { note(it, Warn) }
        val items = list?.optJSONArray("items").objects()
        if (list != null && items.isEmpty()) note(list?.optString("note")?.ifEmpty { null } ?: "No NFTs in this wallet yet.")
        items.forEach { nft ->
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    nft.optString("thumb").takeIf { it.isNotEmpty() }?.let { b64 ->
                        runCatching { android.util.Base64.decode(b64, android.util.Base64.NO_WRAP) }.getOrNull()
                            ?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                            ?.let { Image(it.asImageBitmap(), contentDescription = nft.optString("name"), modifier = Modifier.size(96.dp)) }
                    }
                    Text(nft.optString("name"), fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(nft.optString("collection") + (if (nft.optBoolean("pending")) " · pending" else ""), fontSize = 10.sp, color = Muted, maxLines = 1)
                }
            }
        }
        action("Refresh") { tick++ }
        note(list?.optLong("height")?.takeIf { it > 0 }?.let { "Verified on your phone · block %,d. Send NFTs from the phone app.".format(it) } ?: "Verified on your phone. Send NFTs from the phone app.")
    }
}

@Composable
fun MoreScreen(nav: Nav) {
    val context = LocalContext.current
    val connected by PhoneLink.connected.collectAsState()
    val scope = rememberCoroutineScope()
    var locked by remember { mutableStateOf(false) }
    Page("More") {
        action("Community", "Public room") { nav.push(Screen.Conversation("g:parano1d", "Community")) }
        action(if (locked) "Session locked" else "Lock watch session", enabled = !locked) { scope.launch { PhoneLink.lock(context); locked = true } }
        note(if (connected) "Phone connected" else "Phone not connected")
        note("Watch send and confirmation settings are on your phone")
        note("Parano1d ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · internal")
    }
}

@Composable
fun PinScreen(nav: Nav, then: Screen) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    val runner = rememberRunner(nav, Screen.Pin(then))
    Page("Wallet PIN") {
        item { Text("●".repeat(pin.length).ifEmpty { "Enter PIN" }, color = if (pin.isEmpty()) Muted else Color.White, fontSize = 18.sp) }
        item { Keypad(decimal = false) { pin = edit(pin, it, 8, false) } }
        runner.error?.let { note(it, Warn) }
        action(if (runner.busy) "Checking…" else "Unlock", primary = true, enabled = pin.length >= 4 && !runner.busy) {
            runner.run {
                try {
                    val result = PhoneLink.call(context, "pin_unlock", JSONObject().put("pin", pin))
                    PhoneLink.session = result.optString("session")
                    nav.replace(then)
                } finally { pin = "" }
            }
        }
        note("Your phone checks the PIN. It is never stored on the watch.")
    }
}

@Composable
fun ConfirmScreen(nav: Nav, event: JSONObject) {
    val context = LocalContext.current
    val runner = rememberRunner(nav, Screen.Confirm(event))
    var remaining by remember { mutableIntStateOf(event.optInt("expires_seconds", 60)) }
    LaunchedEffect(event) { while (remaining > 0) { delay(1000); remaining-- } }
    val answer: (Boolean) -> Unit = { approved ->
        runner.run {
            PhoneLink.call(context, "confirm_reply", JSONObject().put("confirm_id", event.optString("confirm_id")).put("approved", approved))
            PhoneLink.showConfirm(null)
            nav.pop()
        }
    }
    Page("Approve phone send?") {
        if (event.optString("to_name").isNotEmpty()) item { Text(event.optString("to_name"), fontWeight = FontWeight.Bold) }
        item { Text(event.optString("to").chunked(4).joinToString(" "), color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 10.dp)) }
        // NOID payments show amount, fee and total; token/NFT transfers show what is sent and the fee.
        val noid = event.optString("amount").matches(Regex("[0-9]+(\\.[0-9]+)?"))
        item { Text(if (noid) "${event.optString("amount")} NOID" else event.optString("amount"), color = Neon, fontSize = if (noid) 22.sp else 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
        if (event.optString("total").isNotEmpty()) note("Fee ${event.optString("fee")} NOID · Total ${event.optString("total")} NOID")
        else if (event.optString("fee").isNotEmpty()) note("Network fee ${event.optString("fee")}")
        note("Check the address and amount before approving.")
        runner.error?.let { note(it, Warn) }
        if (remaining <= 0) {
            note("Timed out. Nothing was signed.", Warn)
            action("Close") { PhoneLink.showConfirm(null); nav.pop() }
        } else {
            note("${remaining}s left")
            action("Approve", primary = true, enabled = !runner.busy) { answer(true) }
            action("Decline", enabled = !runner.busy) { answer(false) }
        }
    }
}
