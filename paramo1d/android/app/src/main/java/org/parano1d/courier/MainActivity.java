package org.parano1d.courier;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static java.lang.ref.WeakReference<MainActivity> active = new java.lang.ref.WeakReference<>(null);
    /** iOS 178: tells the open review which step it is on (shown under its spinner). */
    static void reviewStep(String text) {
        MainActivity activity = active.get(); if (activity == null) return;
        String script = "window.parano1dReviewStep?.(" + JSONObject.quote(text) + ")";
        activity.runOnUiThread(() -> { if (!activity.isDestroyed() && activity.pageReady) activity.web.evaluateJavascript(script, null); });
    }
    private WebView web;
    private AppLock appLock;
    private android.widget.FrameLayout root;
    private android.view.View lockScreen;
    private android.widget.FrameLayout launchScreen;
    private boolean lockScreenHasCredential;
    private boolean lockScreenDark;
    private String lockScreenCode = "";
    private android.widget.EditText lockCodeField;
    private boolean interfaceLoaded;
    private boolean pageReady;
    private boolean keyboardVisible;
    private boolean interfaceReady;
    private boolean backupPickerActive;
    private boolean entryPromptAttempted;
    private static final int QR_SCAN_REQUEST = 64;
    private volatile String pendingScanId, pendingScanKind;
    private final Set<Integer> unlockedSystemFlows = new HashSet<>();
    private static SecureStore store;
    private static android.content.Context application;
    private static JSONObject data;
    private String pendingBackup;
    private String pendingRestorePassword;
    /** iOS WalletSetupView: a new wallet's master key lives only in memory until the owner re-enters it (verify step). */
    private String pendingCreateKey; private long pendingCreateMs;
    private boolean notificationPermissionAsked;
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final ExecutorService chatWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32));
    // Like iOS build 163: deep proof verification gets a 64 MB stack instead of the default thread stack.
    private static final ExecutorService chainWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), work -> new Thread(null, work, "parano1d-node", 64L << 20));
    private static final ExecutorService deliveryWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2));
    private static final ExecutorService inboxWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
    private static final ExecutorService paymentDeliveryWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2));
    private static final Set<String> sendingPayments = new HashSet<>();
    private static boolean deliveryBusy;
    private static boolean inboxBusy;
    private static final ConnectionAdvisor connectionAdvisor = new ConnectionAdvisor();
    private static long nextInboxAttempt;
    private static long connectionGeneration;
    private static volatile boolean publicChatEnabled = true;
    private static volatile boolean visible;
    private static String activeConversation = "";
    private static boolean paymentFlow;
    private static boolean walletVisible;
    private static long nextNodeAttempt;
    private static PendingChain pendingChain;
    private static PendingChain pendingRefresh;
    private static String activeChainOperation = "";
    private static boolean restartingWalletRoute;
    private final android.os.Handler nodeTimer = new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.content.BroadcastReceiver screenOff = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) { pauseNode(); }
    };
    private static final class PendingChain {
        final MainActivity owner; final String id; final JSONObject request;
        PendingChain(MainActivity owner,String id,JSONObject request) { this.owner=owner;this.id=id;this.request=request; }
    }
    /** Auto-lock while the app is open: locks after the chosen period without a tap or key press. */
    private final Runnable lockWatch = new Runnable() {
        @Override public void run() {
            if (!visible || appLock == null) return;
            if (!appLock.authenticating() && (lockScreen == null || lockScreen.getVisibility() != android.view.View.VISIBLE) && appLock.needsUnlock()) { appLock.lock(); pauseNode(); showLockScreen(); }
            nodeTimer.postDelayed(this, 5000);
        }
    };
    private final Runnable nodeTick = new Runnable() {
        @Override public void run() {
            if (!nodeAllowed()) { pauseNode(); return; }
            try { chatWorker.execute(() -> { try {
                if (data==null || offline() || !data.has("seed_hex") && !watchMode() || data.getJSONObject("chain").optBoolean("running") || pendingChain!=null || paymentFlow || System.currentTimeMillis()<nextNodeAttempt || !nodeAllowed() || !data.optBoolean("paranoid_mode") && data.getJSONObject("settings").optString("rpc").isEmpty()) return;
                if(!data.getJSONObject("chain").has("height") && !walletVisible && !data.optBoolean("paranoid_mode")) return;
                if (data.optBoolean("protected_route") && (!protectedRoute || tunnelStarting || !tunnelState.optBoolean("connected"))) return;
                startChainAction(null,new JSONObject().put("op","sync"),true);
            } catch(Exception ignored) {} }); } catch(RuntimeException ignored) {}
            nodeTimer.postDelayed(this,3000);
        }
    };
    private static final android.os.Handler deliveryTimer = new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile boolean protectedRoute;
    private static boolean tunnelStarting;
    private static boolean pendingProtection;
    private static long nextProtectionAttempt;
    private static int protectionFailures;
    private static JSONObject tunnelState = new JSONObject();
    /** Fields that say a payment was approved on the Wear OS watch (audit 176). Never accepted from the WebView. */
    static final String[] WATCH_APPROVAL_FIELDS = {"watch", "confirmed_to", "confirmed_amount_micronoid", "confirmed_review_id"};
    private static final Set<String> CHAIN_ACTIONS = new HashSet<>(java.util.Arrays.asList("sync", "payment_preview", "payment_send", "receipt_verify", "broadcast_preview", "broadcast_send", "asset_sync", "asset_send_prepare", "asset_send_approve", "asset_send_retry", "asset_reconcile"));
    // Token/NFT reads (local verified State) and hash-checked artwork run beside wallet work, like iOS 163.
    /** Mining requests to parano1dworld.com (iOS 185): off the chat worker so a slow rig never delays messages. */
    private static final ExecutorService miningWorker = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
    private static final ExecutorService assetWorker = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), work -> new Thread(null, work, "parano1d-assets", 32L << 20));

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        CrashReports.install(this);
        active = new java.lang.ref.WeakReference<>(this);
        application = getApplicationContext();
        connectionAdvisor.suppressUntil(application.getSharedPreferences("interface",MODE_PRIVATE).getLong("vpn_hint_until",0));
        appLock = new AppLock(this);
        applySystemTheme(getSharedPreferences("interface",MODE_PRIVATE).getString("theme","system"));
        applyCapturePolicy();
        if (store == null) store = new SecureStore(getApplicationContext());
        web = new WebView(this);
        WebSettings settings = web.getSettings(); settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false); settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false); settings.setJavaScriptCanOpenWindowsAutomatically(false);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder().addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request.getUrl().getHost() != null && request.getUrl().getHost().equals("appassets.androidplatform.net")) return loader.shouldInterceptRequest(request.getUrl());
                return new WebResourceResponse("text/plain", "utf-8", 403, "External resources blocked", java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return !request.getUrl().toString().startsWith(ORIGIN + "/assets/"); }
            @Override public void onPageFinished(WebView view, String url) { pageReady = true; web.evaluateJavascript("window.paramo1dKeyboard?.(" + keyboardVisible + ")", null); handleContactIntent(getIntent()); }
        });
        web.addJavascriptInterface(new Bridge(), "Paramo1d");
        root = new android.widget.FrameLayout(this);
        root.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            boolean visible = android.os.Build.VERSION.SDK_INT >= 30
                ? insets.isVisible(android.view.WindowInsets.Type.ime())
                : insets.getSystemWindowInsetBottom() > insets.getStableInsetBottom() + Math.round(96 * getResources().getDisplayMetrics().density);
            if (keyboardVisible != visible) {
                keyboardVisible = visible;
                if (pageReady) web.evaluateJavascript("window.paramo1dKeyboard?.(" + visible + ")", null);
            }
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard = insets.getInsets(android.view.WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            }
            return insets;
        });
        setContentView(root);
        registerBackHandler();
        applySystemTheme(getSharedPreferences("interface",MODE_PRIVATE).getString("theme","system"));
        android.content.IntentFilter screenFilter=new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (android.os.Build.VERSION.SDK_INT>=33) registerReceiver(screenOff,screenFilter,RECEIVER_NOT_EXPORTED); else registerReceiver(screenOff,screenFilter);
        if (appLock.needsUnlock()) showLockScreen(); else openInterface();
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleContactIntent(intent); }
    @Override public void startActivityForResult(Intent intent, int request) {
        if ((request == 62 || request == 63 || request == QR_SCAN_REQUEST) && appLock != null && !appLock.needsUnlock()) unlockedSystemFlows.add(request);
        super.startActivityForResult(intent, request);
    }
    @Override protected void onResume() {
        super.onResume(); visible = true;
        nodeTimer.removeCallbacks(lockWatch); nodeTimer.postDelayed(lockWatch, 5000);
        if (appLock != null && !appLock.authenticating()) {
            if (appLock.needsUnlock()) { showLockScreen(); root.postOnAnimation(this::maybeUnlock); }
            else openInterface();
        }
        deliveryPoll();
    }
    @Override protected void onPause() {
        visible = false;
        pauseNode();
        if (appLock != null) { if (!appLock.authenticating()) entryPromptAttempted = false; appLock.backgrounded(); if ((AppLock.enabled(this) || AppCode.isSet(this)) && new File(getFilesDir(), "identity.pmc").isFile()) showLockScreen(); }
        super.onPause();
    }
    @Override protected void onStop() {
        entryPromptAttempted = false;
        super.onStop();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && root != null) root.postOnAnimation(this::maybeUnlock);
    }
    private void maybeUnlock() {
        // With an app PIN or password the lock screen asks for it; the phone's screen lock is a button there (iOS).
        if (visible && hasWindowFocus() && appLock != null && appLock.needsUnlock() && !appLock.authenticating() && !entryPromptAttempted && !AppCode.isSet(this)) unlockInterface();
    }
    private void unlockInterface() {
        entryPromptAttempted = true;
        appLock.authenticate(this::openInterface, this::showLockScreen);
    }
    private void openInterface() {
        appLock.opened();
        entryPromptAttempted = false;
        if (lockScreen != null) lockScreen.setVisibility(android.view.View.GONE);
        web.setVisibility(android.view.View.VISIBLE);
        if (!interfaceReady) showLaunchScreen();
        applyCapturePolicy();
        if (!interfaceLoaded) { interfaceLoaded = true; web.loadUrl(ORIGIN + "/assets/index.html"); }
        else { if (pageReady) handleContactIntent(getIntent()); chatWorker.execute(() -> { try { if (data != null) publishView(); } catch (Exception ignored) {} }); }
        if (visible) { nextNodeAttempt=0;nodeControl(true); nodeTimer.removeCallbacks(nodeTick); nodeTimer.postDelayed(nodeTick,1000); }
    }
    private void showLaunchScreen() {
        if (launchScreen == null) {
            launchScreen = new android.widget.FrameLayout(this);
            int size = Math.round(200 * getResources().getDisplayMetrics().density);
            String launchTheme = getSharedPreferences("interface", MODE_PRIVATE).getString("theme", "system");
            boolean launchDark = launchTheme.equals("dark") || launchTheme.equals("system") && (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            android.widget.ImageView coin = new android.widget.ImageView(this); coin.setImageResource(launchDark ? R.drawable.parano1d_logo_dark : R.drawable.parano1d_logo_light); coin.setContentDescription("Opening Parano1d World");
            launchScreen.addView(coin, new android.widget.FrameLayout.LayoutParams(Math.round(size * 1.5f), size, android.view.Gravity.CENTER));
            root.addView(launchScreen, new android.widget.FrameLayout.LayoutParams(-1, -1));
        }
        applySystemTheme(getSharedPreferences("interface", MODE_PRIVATE).getString("theme", "system"));
        launchScreen.setVisibility(android.view.View.VISIBLE); launchScreen.bringToFront();
    }
    private void hideLaunchScreen() {
        interfaceReady = true;
        if (launchScreen == null) return;
        android.widget.FrameLayout finished = launchScreen; launchScreen = null;
        if (android.animation.ValueAnimator.areAnimatorsEnabled() && finished.isShown()) finished.animate().alpha(0).setDuration(160).withEndAction(() -> root.removeView(finished)).start();
        else root.removeView(finished);
    }
    private boolean nodeAllowed() {
        return visible && appLock!=null && !appLock.needsUnlock() && !getSystemService(android.app.KeyguardManager.class).isDeviceLocked() && getSystemService(android.os.PowerManager.class).isInteractive();
    }
    private static void nodeControl(boolean active) {
        try { NativeCore.call(new JSONObject().put("op","node_active").put("active",active).toString()); } catch(Throwable ignored) {}
    }
    private void pauseNode() { nodeTimer.removeCallbacks(nodeTick); nodeControl(false); walletScreenAwake(false); if(BuildConfig.DEBUG) android.util.Log.d("Paramo1dNode","Paused"); }
    private void showLockScreen() {
        if (root == null) return;
        if (launchScreen != null) launchScreen.setVisibility(android.view.View.GONE);
        web.setVisibility(android.view.View.INVISIBLE);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        boolean secure = AppLock.available(this);
        String theme = getSharedPreferences("interface", MODE_PRIVATE).getString("theme", "system");
        boolean dark = theme.equals("dark") || theme.equals("system") && (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        String codeKind = AppCode.isSet(this) ? AppCode.kind(this) + (AppLock.enabled(this) ? "+device" : "") : "";
        if (lockScreen != null && (secure != lockScreenHasCredential || dark != lockScreenDark || !codeKind.equals(lockScreenCode))) { root.removeView(lockScreen); lockScreen = null; }
        if (lockScreen == null && !codeKind.isEmpty()) { lockScreenHasCredential = secure; lockScreenDark = dark; lockScreenCode = codeKind; lockScreen = codeLockScreen(AppCode.kind(this), secure && AppLock.enabled(this), dark); root.addView(lockScreen, new android.widget.FrameLayout.LayoutParams(-1,-1)); }
        if (lockScreen == null) {
            lockScreenHasCredential = secure; lockScreenDark = dark; lockScreenCode = "";
            int ink = dark ? Color.rgb(238,246,239) : Color.rgb(7,18,13);
            android.widget.LinearLayout panel = new android.widget.LinearLayout(this); panel.setOrientation(android.widget.LinearLayout.VERTICAL); panel.setGravity(android.view.Gravity.CENTER); int padding = (int)(28 * getResources().getDisplayMetrics().density); panel.setPadding(padding,padding,padding,padding); panel.setBackgroundColor(dark ? Color.rgb(16,20,19) : Color.rgb(246,246,238));
            android.widget.ImageView logo = new android.widget.ImageView(this); logo.setImageResource(dark ? R.drawable.parano1d_logo_dark : R.drawable.parano1d_logo_light); logo.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO); int logoSize = (int)(164 * getResources().getDisplayMetrics().density); android.widget.LinearLayout.LayoutParams logoLayout = new android.widget.LinearLayout.LayoutParams(Math.round(logoSize * 1.6f),logoSize); logoLayout.bottomMargin = padding / 2; panel.addView(logo,logoLayout);
            android.widget.TextView title = new android.widget.TextView(this); title.setText("Parano1d"); title.setVisibility(android.view.View.GONE); /* the logo carries the wordmark */ title.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD); title.setTextSize(30); title.setTextColor(ink); title.setGravity(android.view.Gravity.CENTER); title.setMaxLines(1); title.setAutoSizeTextTypeUniformWithConfiguration(20, 30, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            android.widget.LinearLayout.LayoutParams titleLayout = new android.widget.LinearLayout.LayoutParams(-1, -2); titleLayout.bottomMargin = padding; panel.addView(title, titleLayout);
            android.widget.Button unlock = new android.widget.Button(this); unlock.setText("Unlock Parano1d"); unlock.setAllCaps(false); unlock.setTextColor(Color.rgb(7,18,13));
            android.graphics.drawable.GradientDrawable buttonShape = new android.graphics.drawable.GradientDrawable(); buttonShape.setColor(Color.rgb(0,255,128)); buttonShape.setCornerRadius(12 * getResources().getDisplayMetrics().density);
            unlock.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x2807120d), buttonShape, null)); unlock.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density)); unlock.setPadding(padding, 0, padding, 0);
            unlock.setOnClickListener(v -> unlockInterface()); panel.addView(unlock);
            // iOS 182 unlock note, with Android's unlock method (the phone's screen lock).
            android.widget.TextView unlockNote = new android.widget.TextView(this); unlockNote.setText("Your phone's screen lock unlocks the wallet on this phone. It can't recover a lost wallet, so keep your recovery key safe.");
            unlockNote.setTextSize(13); unlockNote.setTextColor(Color.argb(190, Color.red(ink), Color.green(ink), Color.blue(ink))); unlockNote.setGravity(android.view.Gravity.CENTER); unlockNote.setLineSpacing(0, 1.2f);
            android.widget.LinearLayout.LayoutParams noteLayout = new android.widget.LinearLayout.LayoutParams(-1, -2); noteLayout.topMargin = padding; panel.addView(unlockNote, noteLayout);
            if (!AppLock.available(this)) { unlock.setEnabled(false); android.widget.Button setup = new android.widget.Button(this); setup.setText("Set up phone screen lock"); setup.setAllCaps(false); setup.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))); panel.addView(setup); }
            android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.addView(panel, new android.widget.ScrollView.LayoutParams(-1,-2));
            lockScreen = scroll; root.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1,-1));
        }
        lockScreen.setVisibility(android.view.View.VISIBLE);
        if (lockCodeField != null) lockCodeField.setText("");
        applyCapturePolicy();
    }
    /** iOS AppLockView: "Welcome back.", the app PIN or password, the phone's screen lock as a second way in, and "Forgot app code?". */
    private android.view.View codeLockScreen(String kind, boolean secure, boolean dark) {
        float dp = getResources().getDisplayMetrics().density; int padding = (int) (28 * dp);
        int ink = dark ? Color.rgb(238,246,239) : Color.rgb(7,18,13), muted = Color.argb(190, Color.red(ink), Color.green(ink), Color.blue(ink));
        android.widget.LinearLayout panel = new android.widget.LinearLayout(this); panel.setOrientation(android.widget.LinearLayout.VERTICAL); panel.setGravity(android.view.Gravity.CENTER);
        panel.setPadding(padding, padding, padding, padding); panel.setBackgroundColor(dark ? Color.rgb(7,12,10) : Color.rgb(246,246,238));
        android.widget.ImageView logo = new android.widget.ImageView(this); logo.setImageResource(dark ? R.drawable.parano1d_logo_dark : R.drawable.parano1d_logo_light); logo.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        panel.addView(logo, new android.widget.LinearLayout.LayoutParams((int) (164 * dp), (int) (110 * dp)));
        android.widget.TextView title = new android.widget.TextView(this); title.setText("Welcome back."); title.setTextSize(30); title.setTextColor(ink); title.setGravity(android.view.Gravity.CENTER); title.setTypeface(android.graphics.Typeface.SERIF);
        panel.addView(title, new android.widget.LinearLayout.LayoutParams(-1, -2));
        android.widget.TextView line = new android.widget.TextView(this); line.setText("Enter your " + AppCode.label(kind).toLowerCase(java.util.Locale.ROOT) + "."); line.setTextSize(15); line.setTextColor(muted); line.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams lineLayout = new android.widget.LinearLayout.LayoutParams(-1, -2); lineLayout.bottomMargin = (int) (18 * dp); panel.addView(line, lineLayout);
        android.widget.EditText code = new android.widget.EditText(this); code.setId(0x7f0c0de1); lockCodeField = code;
        int digits = AppCode.digits(kind);
        code.setInputType(digits > 0 ? android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(digits > 0 ? digits : 128)});
        code.setGravity(android.view.Gravity.CENTER); code.setTextSize(digits > 0 ? 28 : 18); code.setTextColor(ink); code.setHint(digits > 0 ? "•".repeat(digits) : "Password"); code.setHintTextColor(Color.argb(90, Color.red(ink), Color.green(ink), Color.blue(ink)));
        code.setContentDescription(AppCode.label(kind)); code.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        panel.addView(code, new android.widget.LinearLayout.LayoutParams(-1, -2));
        android.widget.TextView error = new android.widget.TextView(this); error.setTextColor(Color.rgb(255,155,54)); error.setTextSize(14); error.setGravity(android.view.Gravity.CENTER); error.setVisibility(android.view.View.GONE);
        panel.addView(error, new android.widget.LinearLayout.LayoutParams(-1, -2));
        android.widget.Button unlock = new android.widget.Button(this); unlock.setText("Unlock wallet"); unlock.setAllCaps(false); unlock.setTextColor(Color.rgb(7,18,13));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable(); shape.setColor(Color.rgb(0,255,128)); shape.setCornerRadius(12 * dp);
        unlock.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x2807120d), shape, null)); unlock.setMinHeight((int) (48 * dp));
        android.widget.LinearLayout.LayoutParams unlockLayout = new android.widget.LinearLayout.LayoutParams(-1, -2); unlockLayout.topMargin = (int) (16 * dp); panel.addView(unlock, unlockLayout);
        Runnable submit = () -> {
            String entered = code.getText().toString(); code.setText("");
            if (entered.isEmpty() || digits > 0 && entered.length() != digits) return;
            unlock.setEnabled(false); unlock.setText("Unlocking…"); error.setVisibility(android.view.View.GONE);
            new Thread(() -> {
                String problem = null;
                try { AppCode.verify(this, entered, this::eraseLocalWalletKey); } catch (Exception wrong) { problem = wrong.getMessage(); }
                final String shown = problem;
                runOnUiThread(() -> {
                    unlock.setEnabled(true); unlock.setText("Unlock wallet");
                    if (shown == null) { appLock.opened(); openInterface(); return; }
                    error.setText(shown); error.setVisibility(android.view.View.VISIBLE); error.announceForAccessibility(shown);
                    if (!new File(getFilesDir(), "identity.pmc").isFile() || data != null && !data.has("seed_hex")) { appLock.opened(); openInterface(); }
                });
            }, "parano1d-app-code").start();
        };
        unlock.setOnClickListener(v -> submit.run());
        code.setOnEditorActionListener((v, action, event) -> { submit.run(); return true; });
        if (digits > 0) code.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence t, int a, int b, int c) { }
            public void onTextChanged(CharSequence t, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable t) { if (t.length() == digits) submit.run(); }
        });
        if (secure) {
            android.widget.Button device = new android.widget.Button(this, null, android.R.attr.borderlessButtonStyle); device.setText("Use phone screen lock"); device.setAllCaps(false); device.setTextColor(ink);
            device.setOnClickListener(v -> { code.setText(""); unlockInterface(); }); panel.addView(device, new android.widget.LinearLayout.LayoutParams(-1, -2));
        }
        android.widget.Button forgot = new android.widget.Button(this, null, android.R.attr.borderlessButtonStyle); forgot.setText("Forgot app code?"); forgot.setAllCaps(false); forgot.setTextSize(13); forgot.setTextColor(muted);
        forgot.setOnClickListener(v -> {
            code.setText("");
            if (!AppLock.available(this)) { error.setText("Set a phone screen lock first, then reset the app code with it. Or restore with your master key."); error.setVisibility(android.view.View.VISIBLE); return; }
            appLock.confirm("Reset app code", () -> { AppCode.reset(this); lockScreenCode = "x"; openInterface(); publishViewSoon(); }, () -> { });
        });
        panel.addView(forgot, new android.widget.LinearLayout.LayoutParams(-1, -2));
        android.widget.TextView note = new android.widget.TextView(this); note.setText("Your app code unlocks the wallet on this phone. It can't recover a lost wallet, so keep your recovery key safe.");
        note.setTextSize(13); note.setTextColor(muted); note.setGravity(android.view.Gravity.CENTER); note.setLineSpacing(0, 1.2f);
        android.widget.LinearLayout.LayoutParams noteLayout = new android.widget.LinearLayout.LayoutParams(-1, -2); noteLayout.topMargin = (int) (16 * dp); panel.addView(note, noteLayout);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this); scroll.setFillViewport(true); scroll.addView(panel, new android.widget.ScrollView.LayoutParams(-1, -2));
        return scroll;
    }
    private void publishViewSoon() { chatWorker.execute(() -> { try { if (data != null) publishView(); } catch (Exception ignored) { } }); }
    /**
     * iOS "Erase local wallet key after 10 wrong app-code entries": removes every wallet key from this phone (the
     * first wallet's key, which also holds the chat identity, and any separate keys). Contacts and message history
     * stay; restoring the same master key brings the same address and chat identity back. Funds stay on-chain.
     */
    private void eraseLocalWalletKey() throws Exception {
        synchronized (MainActivity.class) {
            // At the lock screen nothing has loaded the wallet yet: load it so the erase really removes the keys.
            // The keys are removed before the fields are set up, so no delivery service is started for a wallet being erased.
            boolean loadedHere = data == null;
            JSONObject target = loadedHere ? store.read() : data;
            for (String key : new String[]{"seed_hex", "wallets", "selected_root", "default_root", "pending_review", "pending_asset_review", "invite", "profile", "wallet_name"}) target.remove(key);
            if (loadedHere) { data = target; initializeFields(); }
            data.put("chain", new JSONObject()).put("key_erased_ms", System.currentTimeMillis());
            clearWalletCaches();
            store.save(data);
        }
        NodeSyncJob.schedule(application, false);
        if (DeliveryService.isRunning()) application.stopService(new Intent(application, DeliveryService.class));
    }
    private void handleContactIntent(Intent intent) {
        if (appLock.needsUnlock() || !pageReady) return;
        String destination = intent.getStringExtra("open_chat");
        String sendAmount = intent.getStringExtra("send_amount");
        if (destination != null && destination.matches("d:[0-9a-f]{64}") && sendAmount != null && sendAmount.matches("\\d+(\\.\\d{1,6})?")) { web.evaluateJavascript("window.parano1dOpenSend(" + JSONObject.quote(destination) + "," + JSONObject.quote(sendAmount) + ")", null); intent.removeExtra("open_chat"); intent.removeExtra("send_amount"); }
        else if (destination != null && destination.matches("d:[0-9a-f]{64}")) { web.evaluateJavascript("window.paramo1dOpenChat(" + JSONObject.quote(destination) + ")", null); intent.removeExtra("open_chat"); }
        Uri uri = intent.getData();
        // Payment links (iOS 181): paramo1d://pay?to=<o1 address>&amount=<NOID>. They only fill in Send; the person
        // still reviews and approves with their PIN. Held while locked (this method waits for unlock). Bad links are ignored.
        if (uri != null && "paramo1d".equals(uri.getScheme()) && "pay".equals(uri.getHost())) {
            intent.setData(null);
            try {
                String to = uri.toString().length() <= 300 ? uri.getQueryParameter("to") : null;
                String amount = uri.getQueryParameter("amount");
                boolean validTo = to != null && to.matches("o1[0-9a-z]{20,126}");
                boolean validAmount = amount == null || amount.isEmpty() || amount.length() <= 24 && amount.matches("\\d+(\\.\\d{1,6})?") && new java.math.BigDecimal(amount).signum() > 0;
                if (validTo && validAmount) web.evaluateJavascript("window.parano1dOpenPay?.(" + JSONObject.quote(to) + "," + JSONObject.quote(amount == null ? "" : amount) + ")", null);
            } catch (Exception ignoredBadLink) { }
            return;
        }
        // Mining pairing (iOS 185): paramo1d://mining/pair/CODE, or the rig dashboard's QR https://parano1dworld.com/mining/pair/CODE
        // opened from the camera. It only opens the pairing screen; pairing still asks for the screen lock. Held while locked.
        if (uri != null && ("paramo1d".equals(uri.getScheme()) && "mining".equals(uri.getHost()) || "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getHost().matches("(www\\.)?parano1dworld\\.com"))) {
            intent.setData(null);
            String code = Mining.parseCode(uri.toString());
            if (code != null) web.evaluateJavascript("window.parano1dOpenMiningPair?.(" + JSONObject.quote(code) + ")", null);
            return;
        }
        if (uri != null && "paramo1d".equals(uri.getScheme()) && "contact".equals(uri.getHost())) {
            web.evaluateJavascript("window.paramo1dOpenContact(" + JSONObject.quote(uri.toString()) + ")", null); intent.setData(null);
        }
    }
    /** Mining ops (iOS 185 MiningView), run on miningWorker. Pair, Start and Stop ask for the phone's screen lock first. */
    private void mining(String id, JSONObject request, boolean tunnel, String address) {
        try {
            Mining.Tunnel route = tunnel ? MainActivity::miningTunnel : null;
            String op = request.getString("op");
            switch (op) {
                case "mining_list": reply(id, success(new JSONObject().put("rigs", Mining.list(this)).put("addresses", new JSONArray().put(address)))); return;
                case "mining_parse": {
                    String code = Mining.parseCode(request.optString("text"));
                    if (code == null) throw new Exception("Enter the 12-character code shown under the rig's pairing QR.");
                    reply(id, success(new JSONObject().put("code", code).put("display", Mining.display(code)))); return;
                }
                case "mining_status": reply(id, success(miningStatus(request.getString("id"), route))); return;
                case "mining_rename": Mining.rename(this, request.getString("id"), request.optString("label")); reply(id, success(new JSONObject().put("rigs", Mining.list(this)))); return;
                case "mining_remove": Mining.remove(this, request.getString("id")); reply(id, success(new JSONObject().put("rigs", Mining.list(this)))); return;
                case "mining_order": Mining.reorder(this, request.optJSONArray("ids")); reply(id, success(new JSONObject().put("rigs", Mining.list(this)))); return;
                case "mining_pair": {
                    String code = Mining.parseCode(request.getString("code"));
                    if (code == null) throw new Exception("Enter the 12-character code shown under the rig's pairing QR.");
                    confirmUser("Pair a mining computer");
                    JSONObject pairing;
                    try { pairing = Mining.pair(this, code, request.optString("label"), route); }
                    catch (Mining.Failure failure) {
                        if (failure.phoneControlOff()) throw new Exception("Phone control is off on that computer. Turn on Allow control from a phone in its mining dashboard, then show a new pairing QR.");
                        throw new Exception(failure.offline ? "The rig, its companion or the tunnel is offline. Try again when the computer is on." : failure.getMessage());
                    }
                    reply(id, success(new JSONObject().put("rigs", Mining.list(this)).put("id", pairing.getString("id")).put("status", miningStatus(pairing.getString("id"), route)))); return;
                }
                case "mining_command": {
                    String cmd = request.getString("cmd");
                    if (!(cmd.equals("start") || cmd.equals("stop"))) throw new Exception("Unknown mining command.");
                    JSONObject rig = Mining.find(this, request.getString("id"));
                    confirmUser((cmd.equals("stop") ? "Stop mining on " : "Start mining on ") + rig.optString("label", "your computer"));
                    try { Mining.command(this, rig.getString("id"), cmd, route); }
                    catch (Mining.Failure failure) {
                        if (failure.phoneControlOff()) { reply(id, success(new JSONObject().put("id", rig.getString("id")).put("received", System.currentTimeMillis()).put("phone_control_off", true))); return; }
                        reply(id, success(new JSONObject().put("error", failure.offline ? "The rig is offline. Nothing was changed." : failure.getMessage()).put("revoked", failure.status == 401))); return;
                    }
                    Thread.sleep(800);
                    reply(id, success(miningStatus(rig.getString("id"), route))); return;
                }
                default: throw new Exception("Unknown mining request.");
            }
        } catch (Exception failed) { reply(id, failure(failed)); }
    }
    /** The rig's status, or the reason it couldn't be read; never throws for a rig problem so each card can show its own state. */
    private JSONObject miningStatus(String rigId, Mining.Tunnel route) throws Exception {
        JSONObject out = new JSONObject().put("id", rigId).put("received", System.currentTimeMillis());
        try { out.put("status", Mining.command(this, rigId, "status", route)); }
        catch (Mining.Failure failure) {
            if (failure.phoneControlOff()) return out.put("phone_control_off", true); // not offline, never unpaired
            out.put("revoked", failure.status == 401);
            out.put("error", failure.offline ? "The rig, its companion or the tunnel is offline. Mining, if running, continues on the rig." : failure.getMessage());
        }
        return out;
    }
    private static JSONObject miningTunnel(String url, String body, JSONObject headers) throws Exception {
        return tunnelCore(new JSONObject().put("op", "request").put("url", url).put("method", "POST").put("body", body).put("headers", headers));
    }
    /** Blocks this mining worker until the person confirms with the phone's screen lock (no-op on a phone without one). */
    /**
     * A fresh approval for sends, secrets and wallet changes, like iOS: the fingerprint or phone screen lock when
     * that unlock is turned on, otherwise the app PIN or password (wrong entries count toward the same limit).
     */
    private void confirmUser(String title) throws Exception {
        boolean device = AppLock.available(this) && AppLock.enabled(this);
        if (!device && AppCode.isSet(this)) { confirmWithCode(title); return; }
        if (!AppLock.available(this)) return;
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final boolean[] approved = { false };
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || appLock.needsUnlock()) { done.countDown(); return; }
            appLock.confirm(title, () -> { approved[0] = true; openInterface(); done.countDown(); },
                () -> { if (appLock.needsUnlock()) showLockScreen(); else openInterface(); done.countDown(); });
        });
        if (!done.await(3, TimeUnit.MINUTES) || !approved[0]) throw new Exception("Not confirmed. Nothing was changed.");
    }
    private void confirmWithCode(String title) throws Exception {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final boolean[] approved = { false };
        final String kind = AppCode.kind(this); final int digits = AppCode.digits(kind);
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) { done.countDown(); return; }
            android.widget.EditText code = new android.widget.EditText(this);
            code.setInputType(digits > 0 ? android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            code.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(digits > 0 ? digits : 128)});
            code.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO); code.setContentDescription(AppCode.label(kind));
            android.widget.FrameLayout box = new android.widget.FrameLayout(this); int pad = (int) (20 * getResources().getDisplayMetrics().density); box.setPadding(pad, 0, pad, 0); box.addView(code);
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this).setTitle(title)
                .setMessage("Enter your " + AppCode.label(kind).toLowerCase(java.util.Locale.ROOT) + " to confirm.").setView(box)
                .setPositiveButton("Confirm", null).setNegativeButton("Cancel", (d, w) -> done.countDown()).setOnCancelListener(d -> done.countDown()).create();
            dialog.setCanceledOnTouchOutside(false); // only Cancel or Back cancels a confirmation
            if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            code.requestFocus();
            if (dialog.getWindow() != null) dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String entered = code.getText().toString(); code.setText("");
                if (entered.isEmpty()) return;
                v.setEnabled(false);
                new Thread(() -> {
                    String problem = null;
                    try { AppCode.verify(this, entered, this::eraseLocalWalletKey); } catch (Exception wrong) { problem = wrong.getMessage(); }
                    final String shown = problem;
                    runOnUiThread(() -> {
                        v.setEnabled(true);
                        if (shown == null) { approved[0] = true; appLock.opened(); dialog.dismiss(); done.countDown(); return; }
                        dialog.setMessage(shown);
                        if (data != null && !data.has("seed_hex")) { dialog.dismiss(); done.countDown(); openInterface(); }
                    });
                }, "parano1d-app-code-confirm").start();
            }));
            dialog.show();
        });
        if (!done.await(3, TimeUnit.MINUTES) || !approved[0]) throw new Exception("Not confirmed. Nothing was changed.");
    }
    private void reply(String id, JSONObject response) {
        if (id==null) return;
        if (id.startsWith("w:")) { WatchBridge.deliver(id, response); return; }
        final String script = "window.paramo1dReply(" + JSONObject.quote(id) + "," + response.toString() + ")";
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) web.evaluateJavascript(script, null); });
    }
    private JSONObject success(Object result) { try { return new JSONObject().put("ok", true).put("result", result); } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); } }
    private JSONObject failure(Exception error) { try { return new JSONObject().put("ok", false).put("error", error.getMessage() == null ? "Operation failed" : error.getMessage()); } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); } }
    private final class Bridge {
        /** Taps and typing (throttled by the page) restart the Auto-lock period. */
        @JavascriptInterface public void used() { AppLock.used(); }
        /** The page's localStorage (DOM storage is off): read once at page start, then each change. See UiStore. */
        @JavascriptInterface public String uiStoreAll() { return UiStore.all(application); }
        @JavascriptInterface public void uiStoreSet(String key, String value) { UiStore.set(application, key, value); }
        @JavascriptInterface public void uiStoreRemove(String key) { UiStore.remove(application, key); }
        @JavascriptInterface public void ready() {
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed() && !appLock.needsUnlock()) hideLaunchScreen(); });
        }
        @JavascriptInterface public void request(String id, String encoded) {
            if (id.length() > 32 || encoded.length() > 2 * 1024 * 1024) { reply(id, failure(new Exception("Request exceeds limit"))); return; }
            try { chatWorker.execute(() -> {
                try {
                    if (appLock.needsUnlock()) { runOnUiThread(MainActivity.this::showLockScreen); throw new Exception("Unlock Parano1d to continue."); }
                    if (data == null) { data = store.read(); initializeFields(); }
                    JSONObject request = new JSONObject(encoded); String op = request.getString("op");
                    if (offline() && OFFLINE_BLOCKED.contains(op)) throw new Exception("Offline mode is on. Turn it off in Settings › Network to connect.");
                    // Audit 176: only the Wear OS approval path (WatchBridge) may mark a NOID payment as approved on the watch.
                    // (Token/NFT approvals keep their own confirmed_to, checked in AssetSend.)
                    if (op.equals("payment_send")) for (String key : WATCH_APPROVAL_FIELDS) request.remove(key);
                    // Like iOS: every send from the phone needs a fresh approval (fingerprint/phone screen lock or the app code).
                    if (op.equals("payment_send")) confirmUser("Send this payment");
                    if (op.equals("asset_send_approve")) confirmUser("Send this token or NFT");
                    if (op.equals("payment_send") && WatchPolicy.confirmPhoneSends(MainActivity.this) && data.has("pending_review")) {
                        // Optional: approve phone sends on the Wear OS watch before anything is signed.
                        WatchBridge.confirmOnWatch(MainActivity.this, data.getJSONObject("pending_review"), error -> onWorker(() -> {
                            if (error != null) { reply(id, failure(error)); return; }
                            try { startChainAction(id, request); } catch (Exception failed) { reply(id, failure(failed)); }
                        }));
                        return;
                    }
                    if (op.equals("asset_send_approve") && WatchPolicy.confirmPhoneSends(MainActivity.this)) {
                        // Same optional watch confirmation as NOID sends (and iOS token/NFT approvals), before signing.
                        JSONObject shown = new JSONObject().put("to", request.optString("confirmed_to")).put("amount", request.optString("display_amount", "Token or NFT")).put("fee", request.optString("display_fee")).put("total", "");
                        WatchBridge.confirmOnWatch(MainActivity.this, shown, error -> onWorker(() -> {
                            if (error != null) { reply(id, failure(error)); return; }
                            try { startChainAction(id, request); } catch (Exception failed) { reply(id, failure(failed)); }
                        }));
                        return;
                    }
                    if (op.equals("asset_pending")) {
                        final JSONObject who = new JSONObject().put("profile", data.optJSONObject("profile") == null ? JSONObject.NULL : new JSONObject(data.getJSONObject("profile").toString()));
                        try { assetWorker.execute(() -> { try { reply(id, success(AssetSend.pending(who, getFilesDir()))); } catch (Exception failed) { reply(id, failure(failed)); } }); }
                        catch (java.util.concurrent.RejectedExecutionException busy) { reply(id, failure(new Exception("Busy. Try again in a moment."))); }
                        return;
                    }
                    if (op.startsWith("mining_")) {
                        final boolean tunnel = data.optBoolean("protected_route");
                        final String address = data.optJSONObject("profile") == null ? "" : data.getJSONObject("profile").optString("address");
                        try { miningWorker.execute(() -> mining(id, request, tunnel, address)); }
                        catch (java.util.concurrent.RejectedExecutionException busy) { reply(id, failure(new Exception("Busy. Try again in a moment."))); }
                        return;
                    }
                    if (op.equals("explorer_balance")) {
                        // Display-only estimate (iOS 188 item 52) and "Watch an address". Off in Parano1d mode and with App VPN.
                        final boolean blocked = data.optBoolean("paranoid_mode") || data.optBoolean("protected_route");
                        final String target = data.optJSONObject("profile") == null && !watchMode() ? request.optString("address") : ownerAddress();
                        try { assetWorker.execute(() -> { try { reply(id, success(explorerBalance(target, blocked))); } catch (Exception failed) { reply(id, failure(failed)); } }); }
                        catch (java.util.concurrent.RejectedExecutionException busy) { reply(id, failure(new Exception("Busy. Try again in a moment."))); }
                        return;
                    }
                    if (AssetIndex.OPS.contains(op)) {
                        final String owner = ownerAddress();
                        final boolean tunnel = data.optBoolean("protected_route");
                        if (op.equals("usd_quote")) request.put("paranoid_mode", data.optBoolean("paranoid_mode"));
                        try { assetWorker.execute(() -> { try {
                            JSONObject answer = AssetIndex.handle(MainActivity.this, request, owner, tunnel);
                            if (op.equals("assets")) WalletAlerts.observeAssets(application, owner, answer.optJSONObject("holdings"), visible);
                            reply(id, success(answer));
                        } catch (Exception failed) { reply(id, failure(failed)); } }); }
                        catch (java.util.concurrent.RejectedExecutionException busy) { reply(id, failure(new Exception("Loading artwork. Try again in a moment."))); }
                        return;
                    }
                    if (CHAIN_ACTIONS.contains(op)) { startChainAction(id, request); return; }
                    reply(id, success(handle(request)));
                } catch (Exception error) { reply(id, failure(error)); }
            }); } catch (Exception busy) { reply(id, failure(new Exception("Please wait for the current operation"))); }
        }
    }

    private static void initializeFields() throws Exception {
        for (String key : new String[]{"contacts", "messages", "receipts", "outbox", "observations", "watches", "prepared_payments", "envelopes"}) if (!data.has(key)) data.put(key, new JSONArray());
        if (!data.has("settings")) data.put("settings", new JSONObject().put("relays", new JSONArray().put("https://chat.parano1d.org")).put("rpc", "https://chat.parano1d.org/chain"));
        if (!data.has("cursors")) data.put("cursors", new JSONObject());
        if (!data.has("read_messages")) data.put("read_messages", new JSONObject());
        if (!data.has("drafts")) data.put("drafts", new JSONObject());
        if (!data.has("chain")) data.put("chain", new JSONObject());
        if (!data.has("background_delivery")) data.put("background_delivery",true);
        if (!data.has("paranoid_mode")) data.put("paranoid_mode",false);
        if (!data.has("public_chat")) data.put("public_chat", true); // one-time: chat was switched off by Paranoid mode before
        publicChatEnabled = data.optBoolean("public_chat", true);
        nativeResult(new JSONObject().put("op","wallet_mode").put("enabled",data.optBoolean("paranoid_mode")));
        logWalletMetrics("Saved P2P check");
        data.put("connection", new JSONObject().put("online", false));
        data.getJSONObject("chain").put("running", false);
        if (data.has("seed_hex") && (data.optBoolean("background_delivery") || data.optBoolean("protected_route")) && !DeliveryService.isRunning()) {
            try { application.startForegroundService(new Intent(application, DeliveryService.class)); } catch (RuntimeException requiresForeground) { }
        }
        if (data.optBoolean("protected_route") && !protectedRoute) beginProtection();
        if (!data.has("next_address")) data.put("next_address", 1);
        // Audit fixes: chat version 2 (accepted senders, 8-day tombstones). Keeps all history.
        if (ChatInbox.migrate(data, System.currentTimeMillis())) store.save(data);
    }
    private static void ensureDefaultDelivery() {
        if (data==null || !data.has("seed_hex") || !(data.optBoolean("background_delivery",true) || data.optBoolean("protected_route")) || DeliveryService.isRunning()) return;
        try { application.startForegroundService(new Intent(application,DeliveryService.class)); } catch(RuntimeException requiresForeground) {}
    }
    private static JSONObject view() throws Exception {
        JSONObject result = new JSONObject();
        for (String key : new String[]{"profile", "invite", "contacts", "messages", "receipts", "settings", "chain", "connection", "observations", "drafts"}) if (data.has(key)) result.put(key, data.get(key));
        result.put("messages",ChatPolicy.messages(data.getJSONArray("messages"),!publicChatEnabled));
        result.put("observations",ChatPolicy.messages(data.getJSONArray("observations"),!publicChatEnabled));
        JSONArray pending = new JSONArray(), packets = data.getJSONArray("prepared_payments");
        for (int i = 0; i < packets.length(); i++) { JSONObject packet = packets.getJSONObject(i), entry = new JSONObject(); for (String key : new String[]{"txid", "to", "amount", "fee", "submitted", "created_ms", "delivery_error", "chat", "invoice_id", "valid_through_height", "replaced_by"}) if (packet.has(key)) entry.put(key, packet.get(key)); pending.put(entry); }
        result.put("prepared_payments", pending);
        result.put("paranoid_mode",data.optBoolean("paranoid_mode"));
        result.put("profile_named",data.optBoolean("profile_named",true));
        result.put("online_delivery", data.getJSONObject("settings").optJSONArray("relays") != null && data.getJSONObject("settings").getJSONArray("relays").length() > 0);
        if (watchMode()) {
            // Home renders the watched address like a wallet, labelled WATCH-ONLY; chat and sending stay off.
            result.put("watch_only", true).put("watch_address", data.getString("watch_address"))
                  .put("profile", new JSONObject().put("address", data.getString("watch_address")).put("name", "Watch-only").put("id", "00".repeat(32)));
        }
        if (data.has("wallet_name")) result.put("wallet_name",data.getString("wallet_name"));
        if (data.has("seed_hex") && data.optJSONArray("wallets") != null) {
            // iOS Wallets and accounts: the wallet side shows the selected account; chat keeps its own identity.
            JSONObject shown = new JSONObject(data.getJSONObject("profile").toString()).put("address", WalletAccounts.activeAddress(data));
            result.put("profile", shown).put("chat_address", data.getJSONObject("profile").optString("address"))
                  .put("wallet_name", WalletAccounts.activeName(data)).put("accounts", WalletAccounts.summary(data));
        }
        result.put("community_paused",!publicChatEnabled);
        result.put("chat_safety", chatSafety());
        result.put("moderation", ModerationFeed.view(application, data));
        try { result.put("app_version", application.getPackageManager().getPackageInfo(application.getPackageName(), 0).versionName); } catch (Exception ignored) { }
        result.put("prefs", data.optJSONObject("prefs") == null ? new JSONObject().put("live_prices", true) : data.getJSONObject("prefs"));
        // iOS parity: Offline mode, wallet activity alerts and the Auto-lock period.
        result.put("offline", offline()).put("alerts", WalletAlerts.state(application)).put("auto_lock_seconds", AppLock.awaySeconds(application));
        result.put("nearby", NearbyService.snapshot());
        long now=System.currentTimeMillis();
        JSONObject node=data.has("seed_hex")||watchMode()?nativeResult(new JSONObject().put("op","node_status")):new JSONObject();
        if(data.has("seed_hex")||watchMode()) result.put("node",node);
        connectionAdvisor.walletWaiting(now,data.getJSONObject("chain").optBoolean("running")?node.optLong("network_wait_ms",0):0);
        boolean vpn=tunnelState.optBoolean("enabled")||tunnelState.optBoolean("checking")||tunnelState.optBoolean("connected");
        JSONArray nearbyPeers=result.getJSONObject("nearby").optJSONArray("peers");
        String hint=connectionAdvisor.recommendation(now,hasInternet(),vpn,nearbyPeers!=null&&nearbyPeers.length()>0);
        result.put("network_help",new JSONObject().put("recommend_vpn",!hint.isEmpty()).put("issue",connectionAdvisor.issue(now)).put("id",hint.isEmpty()?"":connectionAdvisor.id(hint)));
        result.put("protection", tunnelState);
        result.put("background_delivery", data.optBoolean("background_delivery",true) || data.optBoolean("protected_route"));
        result.put("delivery_running", DeliveryService.isRunning());
        result.put("security", AppLock.state(application));
        result.put("app_code", AppCode.state(application));
        result.put("allow_screenshots", application.getSharedPreferences("interface", MODE_PRIVATE).getBoolean("allow_screenshots", false));
        result.put("backup_saved", data.optLong("backup_saved_ms") > 0);
        result.put("storage_full", data.optBoolean("storage_full") || store.isFull());
        try { result.put("requests", ChatInbox.requestSummary(requests())); } catch (Exception unreadable) { result.put("requests", new JSONArray()); }
        result.put("older_counts", data.optJSONObject("older_counts") == null ? new JSONObject() : data.getJSONObject("older_counts"));
        result.put("notifications_enabled", application.getSystemService(android.app.NotificationManager.class).areNotificationsEnabled());
        JSONObject unread = new JSONObject(), read = data.getJSONObject("read_messages"); JSONArray history = result.getJSONArray("messages"); String own = data.optJSONObject("profile") == null ? "" : data.getJSONObject("profile").getString("id");
        for (int i = 0; i < history.length(); i++) { JSONObject message = history.getJSONObject(i); String scope = message.getString("chat"); if (message.getString("id").equals(read.optString(scope))) unread.put(scope,0); else if (!message.getJSONObject("sender").getString("id").equals(own)) unread.put(scope, unread.optInt(scope) + 1); }
        result.put("unread", unread);
        result.put("connection", new JSONObject(data.optJSONObject("connection").toString()).put("checking", inboxBusy && !data.getJSONObject("connection").optBoolean("online")));
        return result.put("rooms", publicChatEnabled ? new JSONArray().put("parano1d") : new JSONArray());
    }
    private static void requireIdentity() throws Exception { if (!data.has("seed_hex")) throw new Exception("Create or restore your identity first"); }
    /** iOS "Watch an address": a saved public address with no master key. Its balance is verified like a wallet's; nothing can be sent. */
    /** False once the wallet key was erased (or never added): then there is nothing for the app lock to protect. */
    static boolean hasWalletKey() { return data == null || data.has("seed_hex") || data.has("watch_address"); }
    static boolean watchMode() { return data != null && !data.has("seed_hex") && data.has("watch_address"); }
    /** iOS Offline mode: no internet requests at all (relay, node, prices, artwork, explorer, mining, moderation). Nearby Bluetooth still works. */
    static boolean offline() { return data != null && data.optBoolean("offline_mode"); }
    private static final Set<String> OFFLINE_BLOCKED = new HashSet<>(java.util.Arrays.asList("sync", "payment_preview", "payment_send", "receipt_verify", "broadcast_preview", "broadcast_send",
        "asset_sync", "asset_send_prepare", "asset_send_approve", "asset_send_retry", "asset_reconcile", "asset_media", "asset_image", "usd_quote", "explorer_balance", "explorer_tx",
        "mining_status", "mining_pair", "mining_command", "moderation_refresh"));
    /** The address Home, NFTs and the explorer estimate show: the wallet's, or the watched one. */
    static String ownerAddress() throws Exception {
        if (data.has("seed_hex") && data.optJSONArray("wallets") != null) return WalletAccounts.activeAddress(data);
        return data.optJSONObject("profile") != null ? data.getJSONObject("profile").optString("address") : data.optString("watch_address", "");
    }
    private static String deriveAccount(String seedHex, int index) throws Exception {
        return nativeResult(new JSONObject().put("op", "account_address").put("seed_hex", seedHex).put("index", index)).getString("address");
    }
    private static final java.util.Set<String> WATCH_OPS = new java.util.HashSet<>(java.util.Arrays.asList(
        "poll", "theme", "settings", "prefs_set", "watch_settings", "watch_settings_set", "app_lock", "allow_screenshots", "phone_security", "lock_now",
        "notification_settings", "open_link", "explorer_tx", "copy", "share", "crash_reports", "crash_delete", "crash_email", "report_email", "paranoid_mode", "vpn_help_seen", "auto_lock", "alerts_set", "offline_mode", "book_list", "book_save", "book_remove", "category_save"));
    private static JSONObject core(String op, JSONObject args) throws Exception {
        args.put("op", op);
        if (data.has("seed_hex")) args.put("seed_hex", data.getString("seed_hex"));
        args.put("now_ms", System.currentTimeMillis());
        return nativeResult(args);
    }
    private static final class NativeFailure extends Exception {
        final boolean network;
        NativeFailure(String message, boolean network) {super(message);this.network=network;}
    }
    private static boolean transportFailure(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof javax.net.ssl.SSLPeerUnverifiedException || cause instanceof java.security.cert.CertificateException || cause instanceof java.security.cert.CertPathValidatorException) return false;
        }
        for(int depth=0;error!=null&&depth<8;depth++,error=error.getCause()) {
            if(error instanceof NativeFailure && ((NativeFailure)error).network) return true;
            if(error instanceof java.net.SocketTimeoutException || error instanceof java.net.ConnectException || error instanceof java.net.UnknownHostException || error instanceof java.net.NoRouteToHostException) return true;
            if(error instanceof javax.net.ssl.SSLException) return true;
            if(error instanceof java.net.SocketException && String.valueOf(error.getMessage()).matches("(?i).*(reset|unreachable|timed out|refused).*")) return true;
        }
        return false;
    }
    private static boolean hasInternet() {
        if(data!=null&&data.optJSONObject("connection")!=null&&data.optJSONObject("connection").optBoolean("online")) return true;
        try {
            android.net.ConnectivityManager manager=application.getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities caps=manager.getNetworkCapabilities(manager.getActiveNetwork());
            return caps!=null&&caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch(Exception unavailable){return false;}
    }
    private static void logWalletMetrics(String label) {
        try {
            File file=new File(application.getFilesDir(),"chain/p2p-metrics.json");
            if(!file.isFile()||file.length()>16384)return;
            JSONObject metrics=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
            JSONObject safe=new JSONObject();
            for(String key:new String[]{"height","bootstrap_live_slots","retained_wallet_slots","elapsed_ms","chain_check_ms","snapshot_ms","header_ms","origin_ms","proof_ms","proof_class","applied_blocks","cached_wallet_height","reused_wallet_view","rpc_requests","runtime_load_ms","resume_ms","discovery_ms","transfer_ms","data_before_proof"}) {
                Object value=metrics.opt(key);if(value instanceof Number||value instanceof Boolean)safe.put(key,value);
            }
            android.util.Log.i("Paramo1dWallet",label+": "+safe);
        }catch(Exception unavailable){}
    }
    private static JSONObject nativeResult(JSONObject args) throws Exception {
        JSONObject result = new JSONObject(NativeCore.call(args.toString()));
        if (!result.optBoolean("ok")) throw new NativeFailure(result.optString("error", "Native operation failed"),result.optBoolean("network_unavailable"));
        return result.getJSONObject("result");
    }
    private static String randomHex(int count) { byte[] bytes = new byte[count]; new SecureRandom().nextBytes(bytes); StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); return s.toString(); }

    /** iOS Wallets and accounts. Each change asks for the phone's screen lock, like iOS asks for Face ID or the passcode. */
    private JSONObject accounts(String op, JSONObject request) throws Exception {
        if (!data.has("seed_hex")) throw new Exception("Create or restore your wallet first.");
        WalletAccounts.ensure(data);
        String before = ownerAddress();
        String root = request.optString("root", data.optString("selected_root"));
        switch (op) {
            case "accounts_select": {
                confirmUser("Switch wallet account");
                if (pendingOutgoing().length() > 0) throw new Exception("A payment from this account is still waiting for confirmation. Switch accounts after it confirms or expires.");
                WalletAccounts.select(data, root, request.optInt("index", WalletAccounts.find(data, root).optInt("selected"))); break;
            }
            case "accounts_add": confirmUser("Add a wallet under this master key"); if (pendingOutgoing().length() > 0) throw new Exception("A payment from this account is still waiting for confirmation. Add an account after it confirms or expires."); WalletAccounts.addAccount(data, MainActivity::deriveAccount); break;
            case "accounts_rename_root": WalletAccounts.renameRoot(data, root, request.getString("name")); break;
            case "accounts_rename": WalletAccounts.renameAccount(data, root, request.getInt("index"), request.getString("name")); break;
            case "accounts_default": confirmUser("Set default wallet"); WalletAccounts.setDefault(data, root); break;
            case "accounts_move": WalletAccounts.move(data, root, request.getInt("by") < 0 ? -1 : 1); break;
            case "accounts_order": WalletAccounts.reorder(data, request.optJSONArray("roots")); break;
            case "accounts_hide": WalletAccounts.setHidden(data, root, request.getBoolean("hidden")); break;
            case "accounts_remove": confirmUser("Remove this saved account"); WalletAccounts.removeAccount(data, root, request.getInt("index")); break;
            case "accounts_remove_root": {
                confirmUser("Remove this wallet from this device");
                if (root.equals(data.optString("selected_root")) && pendingOutgoing().length() > 0) throw new Exception("A payment from this wallet is still waiting for confirmation. Remove it after the payment confirms or expires.");
                WalletAccounts.removeRoot(data, root); break;
            }
            case "accounts_scan": return scanAccounts(root);
            default: throw new Exception("Unknown wallet request.");
        }
        if (!before.equals(ownerAddress())) { data.put("chain", new JSONObject()); data.remove("pending_review"); clearWalletCaches(); nextNodeAttempt = 0; }
        store.save(data);
        return view();
    }
    /**
     * iOS WalletAddressBook: saved addresses (name, favourite, icon, category, linked contact) and contact categories,
     * kept per wallet (its first account's address) inside the encrypted store. 250 addresses, 30 categories.
     */
    private static final java.util.Set<String> CONTACT_ICONS = new java.util.HashSet<>(java.util.Arrays.asList("initials", "person.fill", "man", "woman", "cpu.fill", "laptopcomputer", "desktopcomputer", "iphone", "globe", "gamecontroller.fill", "building.2.fill", "house.fill"));
    private JSONObject addressBook(String op, JSONObject request) throws Exception {
        if (!data.has("seed_hex") && !watchMode()) throw new Exception("Unlock your wallet to use its address book.");
        String root = data.has("seed_hex") ? (WalletAccounts.active(data) == null ? ownerAddress() : WalletAccounts.active(data).getString("root")) : data.getString("watch_address");
        JSONObject books = data.optJSONObject("address_book"); if (books == null) { books = new JSONObject(); data.put("address_book", books); }
        JSONObject book = books.optJSONObject(root); if (book == null) { book = new JSONObject().put("entries", new JSONArray()).put("categories", new JSONArray()); books.put(root, book); }
        JSONArray entries = book.getJSONArray("entries"), categories = book.getJSONArray("categories");
        if (op.equals("book_save")) {
            String address = request.optString("address").trim(), name = request.optString("name").trim(), icon = request.optString("icon", "initials"), category = request.optString("category", "").trim();
            String contactId = request.optString("contact_id", "");
            if (!WalletKeys.validAddress(address) || name.isEmpty() || name.length() > 60 || !CONTACT_ICONS.contains(icon) || category.length() > 40 || contactId.length() > 128) throw new Exception("Enter a valid name, icon, category, and address.");
            JSONArray kept = new JSONArray(); String existingContact = "";
            for (int i = 0; i < entries.length(); i++) { JSONObject e = entries.getJSONObject(i); if (e.getString("address").equals(address)) existingContact = e.optString("contact_id"); else kept.put(e); }
            if (!existingContact.isEmpty() && !contactId.isEmpty() && !existingContact.equals(contactId)) throw new Exception("This address belongs to another contact. Remove it there before assigning it here.");
            if (kept.length() >= 250) throw new Exception("Address book is full.");
            kept.put(new JSONObject().put("address", address).put("name", name).put("favorite", request.optBoolean("favorite")).put("icon", icon).put("category", category).put("contact_id", contactId.isEmpty() ? existingContact : contactId));
            book.put("entries", kept); store.save(data);
        } else if (op.equals("book_remove")) {
            JSONArray kept = new JSONArray(); for (int i = 0; i < entries.length(); i++) if (!entries.getJSONObject(i).getString("address").equals(request.optString("address"))) kept.put(entries.getJSONObject(i));
            book.put("entries", kept); store.save(data);
        } else if (op.equals("category_save")) {
            String name = request.optString("name").trim(), icon = request.optString("icon", "initials");
            if (name.isEmpty() || name.length() > 40 || !CONTACT_ICONS.contains(icon)) throw new Exception("Enter a category name and icon.");
            JSONArray kept = new JSONArray(); for (int i = 0; i < categories.length(); i++) if (!categories.getJSONObject(i).getString("name").equals(name)) kept.put(categories.getJSONObject(i));
            if (kept.length() >= 30) throw new Exception("Too many categories.");
            kept.put(new JSONObject().put("name", name).put("icon", icon)); book.put("categories", kept); store.save(data);
        }
        return new JSONObject().put("entries", new JSONArray(book.getJSONArray("entries").toString())).put("categories", new JSONArray(book.getJSONArray("categories").toString()));
    }
    /** Outgoing payments of the current account that the chain hasn't confirmed yet (they reserve its coins). */
    private static JSONArray pendingOutgoing() throws Exception {
        JSONArray out = new JSONArray(), packets = data.getJSONArray("prepared_payments"), receipts = data.getJSONArray("receipts");
        for (int i = 0; i < packets.length(); i++) {
            JSONObject packet = packets.getJSONObject(i); if (packet.has("replaced_by")) continue;
            boolean done = false; for (int j = 0; j < receipts.length(); j++) if (receipts.getJSONObject(j).optBoolean("verified") && receipts.getJSONObject(j).optString("txid").equals(packet.optString("txid"))) done = true;
            long valid = packet.optLong("valid_through_height"), height = data.getJSONObject("chain").optLong("height");
            if (!done && (valid == 0 || height == 0 || height <= valid)) out.put(packet.optString("txid"));
        }
        return out;
    }
    /** "Find previously used accounts": 20 derived public addresses are checked on NOID Scan, never the key. */
    private JSONObject scanAccounts(String root) throws Exception {
        if (offline()) throw new Exception("Offline mode is on. Turn it off in Settings › Network to connect.");
        if (data.optBoolean("paranoid_mode") || tunnelState.optBoolean("enabled")) throw new Exception("Scanning uses NOID Scan, so it's off in Parano1d mode and while App VPN is on.");
        confirmUser("Discover accounts from your master key");
        JSONObject wallet = WalletAccounts.find(data, root);
        String seed = WalletAccounts.seed(data, wallet);
        int start = wallet.optInt("scan_next", 0), found = 0;
        JSONArray accounts = wallet.getJSONArray("accounts");
        for (int index = start; index < start + 20 && index <= 100000; index++) {
            String address = deriveAccount(seed, index);
            boolean used;
            try { JSONObject seen = explorerBalance(address, false); used = !seen.optString("balance").matches("0\\.0+"); }
            catch (Exception unavailable) { if (index == start) throw new Exception("NOID Scan didn't answer. Try again later."); continue; }
            if (!used) continue;
            boolean listed = false; for (int i = 0; i < accounts.length(); i++) if (accounts.getJSONObject(i).getInt("index") == index) { accounts.getJSONObject(i).put("used", true); listed = true; }
            if (!listed && accounts.length() < WalletAccounts.MAX_ACCOUNTS) { accounts.put(new JSONObject().put("index", index).put("name", "Account " + (index + 1)).put("address", address).put("used", true)); found++; }
        }
        wallet.put("scan_next", start + 20);
        store.save(data);
        return view().put("scan_found", found).put("scan_range", (start + 1) + "–" + (start + 20));
    }
    private JSONObject addSeparateKey(String key, String name, boolean created) throws Exception {
        WalletAccounts.ensure(data);
        confirmUser(created ? "Create a separate wallet" : "Import a separate master key");
        if (pendingOutgoing().length() > 0) throw new Exception("A payment from this account is still waiting for confirmation. Add a wallet after it confirms or expires.");
        WalletAccounts.addKey(data, key, name, MainActivity::deriveAccount);
        data.put("chain", new JSONObject()); data.remove("pending_review"); clearWalletCaches(); nextNodeAttempt = 0;
        store.save(data);
        return view();
    }
    /** Same derivation as iOS: the master key is the identity seed; the wallet address is spend_secret(seed, 0). */
    private JSONObject createWallet(String key, String walletName) throws Exception {
        String label = walletName == null ? "" : walletName.trim();
        if (label.length() > 40) label = label.substring(0, 40);
        if (data.has("watch_address")) { data.remove("watch_address"); data.remove("watch_key"); data.put("chain", new JSONObject()); clearWalletCaches(); }
        data.put("seed_hex", key);
        JSONObject profile = core("profile", new JSONObject().put("name", "Parano1d user"));
        data.put("profile", profile.getJSONObject("contact")).put("invite", profile.getString("invite")).put("profile_named", false);
        if (!label.isEmpty()) data.put("wallet_name", label);
        data.remove("wallets"); data.remove("selected_root"); data.remove("default_root"); WalletAccounts.ensure(data);
        if (AppCode.state(application).optBoolean("erased")) AppCode.reset(application); // after a lockout erase, set a new code
        store.save(data); ensureDefaultDelivery(); askNotificationPermission();
        return view();
    }
    /** Online delivery on = the saved (or default) relays; off = no relays, so messages go only to nearby phones. */
    private static void setOnlineDelivery(boolean on) throws Exception {
        JSONObject settings = data.getJSONObject("settings");
        JSONArray relays = settings.optJSONArray("relays") == null ? new JSONArray() : settings.getJSONArray("relays");
        if (on) {
            if (relays.length() == 0) settings.put("relays", data.optJSONArray("saved_relays") != null && data.getJSONArray("saved_relays").length() > 0 ? data.getJSONArray("saved_relays") : new JSONArray().put("https://chat.parano1d.org"));
        } else {
            if (relays.length() > 0) data.put("saved_relays", relays);
            settings.put("relays", new JSONArray());
        }
    }
    /** The cached verified wallet view belongs to one address; drop it when the watched address or wallet changes. */
    private void clearWalletCaches() {
        File chain = new File(getFilesDir(), "chain");
        for (String name : new String[]{"wallet-view.json", "p2p-wallet.json"}) { File f = new File(chain, name); if (f.isFile() && !f.delete()) f.deleteOnExit(); }
    }
    /** iOS 188 item 52 / ExplorerClient.address: display-only balance from the public explorer. Never cached to disk. */
    static JSONObject explorerBalance(String address, boolean blocked) throws Exception {
        if (blocked) throw new Exception("Explorer estimate is off in Parano1d mode and while App VPN is on.");
        if (!WalletKeys.validAddress(address)) throw new Exception("Not a valid NOID address.");
        java.net.HttpURLConnection http = (java.net.HttpURLConnection) new java.net.URL("https://parano1d-scan.com/api/address/" + address).openConnection();
        try {
            http.setInstanceFollowRedirects(false); http.setUseCaches(false); http.setConnectTimeout(8000); http.setReadTimeout(12000);
            if (http.getResponseCode() != 200) throw new Exception("Explorer unavailable. Please try again later.");
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
            try (java.io.InputStream in = http.getInputStream()) { while ((n = in.read(buffer)) > 0) { body.write(buffer, 0, n); if (body.size() > 2 * 1024 * 1024) throw new Exception("Explorer response exceeded the limit."); } }
            JSONObject result = new JSONObject(body.toString(StandardCharsets.UTF_8.name()));
            if (!address.equals(result.optString("address"))) throw new Exception("Explorer returned a different address.");
            String raw = result.optString("balance_micronoid", "");
            if (!raw.matches("[0-9]{1,20}")) throw new Exception("Explorer balance unavailable.");
            java.math.BigInteger micro = new java.math.BigInteger(raw), million = java.math.BigInteger.valueOf(1_000_000);
            java.math.BigInteger[] parts = micro.divideAndRemainder(million);
            return new JSONObject().put("address", address).put("balance", parts[0] + "." + String.format(java.util.Locale.ROOT, "%06d", parts[1].intValue())).put("fetched_ms", System.currentTimeMillis());
        } finally { http.disconnect(); }
    }

    private Object handle(JSONObject request) throws Exception {
        String op = request.getString("op");
        if (op.equals("boot") && AppCode.erasePending(application)) AppCode.completePendingErase(application, this::eraseLocalWalletKey);
        if (op.equals("boot") && data.has("seed_hex")) {
            WalletAccounts.ensure(data);
            if (WalletAccounts.openDefault(data)) { data.put("chain", new JSONObject()); clearWalletCaches(); }
            store.save(data);
        }
        if (op.equals("boot")) { if (data.has("seed_hex") && !offline()) NodeSyncJob.schedule(application, data.optJSONObject("prefs") == null || data.getJSONObject("prefs").optBoolean("background_node", true)); runOnUiThread(() -> applySystemTheme(data.optJSONObject("settings").optString("theme", "system"))); if(data.has("seed_hex")) { ensureDefaultDelivery(); if(data.optBoolean("background_delivery",true) || data.optBoolean("protected_route")) askNotificationPermission(); } return view(); }
        if (op.equals("create_identity")) {
            if (data.has("seed_hex")) throw new Exception("An identity already exists");
            String name = request.getString("name").trim(); if (name.isEmpty() || name.length() > 40) throw new Exception("Enter a display name");
            data.put("seed_hex", randomHex(32)); JSONObject profile = core("profile", new JSONObject().put("name", name));
            data.put("profile", profile.getJSONObject("contact")).put("invite", profile.getString("invite")); store.save(data); ensureDefaultDelivery(); askNotificationPermission(); return view();
        }
        // ---- Wallet setup (matches iOS WalletSetupView) ----
        if (op.equals("wallet_create_begin")) {
            if (data.has("seed_hex") && !request.optBoolean("separate")) throw new Exception("A wallet already exists on this phone");
            if (pendingCreateKey == null || System.currentTimeMillis() - pendingCreateMs > 2 * 60 * 60 * 1000L) { pendingCreateKey = randomHex(32); pendingCreateMs = System.currentTimeMillis(); }
            return new JSONObject().put("key_hex", pendingCreateKey);
        }
        if (op.equals("wallet_create_cancel")) { pendingCreateKey = null; return new JSONObject().put("cancelled", true); }
        if (op.equals("wallet_create_finish")) {
            if (data.has("seed_hex") && !request.optBoolean("separate")) throw new Exception("A wallet already exists on this phone");
            if (pendingCreateKey == null) throw new Exception("Start again: the new key was cleared.");
            String entered = WalletKeys.masterKey(request.optString("key_hex"));
            if (!java.security.MessageDigest.isEqual(entered.getBytes(StandardCharsets.US_ASCII), pendingCreateKey.getBytes(StandardCharsets.US_ASCII))) throw new Exception("That doesn't match the key you wrote down. Check each group and try again.");
            String key = pendingCreateKey; pendingCreateKey = null;
            if (request.optBoolean("separate")) return addSeparateKey(key, request.optString("wallet_name"), true);
            return createWallet(key, request.optString("wallet_name"));
        }
        if (op.equals("wallet_import")) {
            if (data.has("seed_hex") && request.optBoolean("separate")) { String key = WalletKeys.masterKey(request.optString("key_hex")); pendingCreateKey = null; return addSeparateKey(key, request.optString("wallet_name"), false); }
            if (data.has("seed_hex")) throw new Exception("A wallet already exists on this phone");
            String key = WalletKeys.masterKey(request.optString("key_hex"));
            pendingCreateKey = null;
            return createWallet(key, request.optString("wallet_name"));
        }
        if (op.equals("watch_start")) {
            if (data.has("seed_hex")) throw new Exception("A wallet is active on this phone. Its address can't be replaced by a watch address.");
            String address = request.optString("address").trim();
            if (!WalletKeys.validAddress(address)) throw new Exception("Enter a valid Parano1d address, including its checksum.");
            if (!address.equals(data.optString("watch_address"))) { data.put("chain", new JSONObject()); clearWalletCaches(); }
            data.put("watch_address", address); if (!data.has("watch_key")) data.put("watch_key", randomHex(32));
            store.save(data); nextNodeAttempt = 0; return view();
        }
        if (op.equals("watch_stop")) {
            if (data.has("seed_hex")) throw new Exception("No watched address");
            data.remove("watch_address"); data.remove("watch_key"); data.put("chain", new JSONObject()); clearWalletCaches(); store.save(data); return view();
        }
        if (op.equals("backup_restore")) {
            if (data.has("seed_hex")) throw new Exception("Restore on an empty installation to preserve your current identity");
            JSONObject result = nativeResult(new JSONObject(request.toString()).put("op", "backup_decrypt"));
            JSONObject restored = result.getJSONObject("data");
            JSONObject profile = nativeResult(new JSONObject().put("op", "profile").put("seed_hex", restored.getString("seed_hex")).put("name", restored.getJSONObject("profile").getString("name")));
            if (!profile.getJSONObject("contact").getString("id").equals(restored.getJSONObject("profile").getString("id"))) throw new Exception("Backup identity does not match");
            restored.remove("tunnel_config"); restored.put("protected_route", false);
            data = restored; initializeFields();
            WalletAccounts.ensure(data); // a backup keeps its separate wallets and accounts
            if (AppCode.erased(application)) AppCode.reset(application); // after a lockout erase, set a new code
            store.save(data); ensureDefaultDelivery(); askNotificationPermission(); return view();
        }
        if (op.equals("backup_restore_file")) {
            if (data.has("seed_hex")) throw new Exception("Restore on an empty installation");
            pendingRestorePassword = request.getString("password");
            if (pendingRestorePassword.length() < 12) throw new Exception("Enter the backup password");
            runOnUiThread(() -> { backupPickerActive = true; startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"), 41); });
            return new JSONObject().put("selecting", true);
        }
        if (watchMode()) { if (!WATCH_OPS.contains(op)) throw new Exception("This is a watch-only address, so it can't send, sign or chat. Add its master key to use it fully."); }
        else requireIdentity();
        switch (op) {
            case "app_lock": AppLock.setEnabled(this, request.getBoolean("enabled")); return view();
            case "auto_lock": AppLock.setAwaySeconds(this, request.getInt("seconds")); return view();
            case "app_code_set": {
                // iOS AppCodeSetupView. Changing an existing code first asks for the phone's screen lock or the current code's unlock.
                if (!data.has("seed_hex")) throw new Exception("Create or restore your wallet first.");
                if (AppCode.isSet(this)) confirmUser("Change app code");
                AppCode.configure(this, request.getString("kind"), request.getString("code"));
                return view();
            }
            case "app_code_erase": {
                if (request.getBoolean("enabled")) confirmUser("Enable erase after 10 wrong entries");
                AppCode.setErase(this, request.getBoolean("enabled")); return view();
            }
            case "alerts_set": WalletAlerts.set(this, request); return view();
            case "book_list": case "book_save": case "book_remove": case "category_save": return addressBook(op, request);
            case "wallet_key_reveal": {
                // iOS "Master key and backup": shown only after the phone's screen lock, for the selected wallet.
                if (!data.has("seed_hex")) throw new Exception("Create or restore your wallet first.");
                confirmUser("View your master key");
                WalletAccounts.ensure(data);
                return new JSONObject().put("key_hex", WalletAccounts.activeSeed(data)).put("address", WalletAccounts.active(data) == null ? ownerAddress() : WalletAccounts.active(data).getString("root"));
            }
            case "wallet_key_check": {
                if (!data.has("seed_hex")) throw new Exception("Create or restore your wallet first.");
                String entered = WalletKeys.masterKey(request.optString("key_hex"));
                if (!java.security.MessageDigest.isEqual(entered.getBytes(StandardCharsets.US_ASCII), WalletAccounts.activeSeed(data).getBytes(StandardCharsets.US_ASCII))) throw new Exception("That doesn't match this wallet's key. Check each group and try again.");
                data.put("backup_verified_ms", System.currentTimeMillis()); store.save(data);
                return view();
            }
            case "accounts_select": case "accounts_add": case "accounts_rename_root": case "accounts_rename": case "accounts_default": case "accounts_move": case "accounts_order":
            case "accounts_hide": case "accounts_remove_root": case "accounts_remove": case "accounts_scan": return accounts(op, request);
            case "paste_address": {
                // Quick Send's Paste: reads the clipboard only on this tap, and returns only a valid Parano1d address.
                CharSequence[] text = new CharSequence[1];
                java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                runOnUiThread(() -> { try { ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE); ClipData clip = clipboard.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) text[0] = clip.getItemAt(0).coerceToText(this); } catch (Exception ignored) { } finally { done.countDown(); } });
                done.await(2, java.util.concurrent.TimeUnit.SECONDS);
                String pasted = text[0] == null ? "" : text[0].toString().trim();
                if (pasted.startsWith("parano1d:")) pasted = pasted.substring(9).split("\\?")[0];
                if (!WalletKeys.validAddress(pasted)) throw new Exception("Clipboard does not contain a valid Parano1d address.");
                return new JSONObject().put("address", pasted);
            }
            case "offline_mode": {
                boolean on = request.getBoolean("enabled"); data.put("offline_mode", on); store.save(data);
                if (on) { connectionGeneration++; data.put("connection", new JSONObject().put("online", false)); pauseNode(); NodeSyncJob.schedule(application, false); }
                else { nextNodeAttempt = 0; nextInboxAttempt = 0; JSONObject prefs = data.optJSONObject("prefs"); NodeSyncJob.schedule(application, (prefs == null || prefs.optBoolean("background_node", true)) && data.has("seed_hex")); runOnUiThread(() -> { nodeTimer.removeCallbacks(nodeTick); nodeTimer.postDelayed(nodeTick, 500); }); }
                return view();
            }
            case "online_delivery": {
                // Settings → Chat → Online message delivery. Off = nearby-only; the user's choice is kept.
                boolean on = request.getBoolean("enabled");
                data.put("online_delivery", on); setOnlineDelivery(on); connectionGeneration++; nextInboxAttempt = 0; store.save(data); return view();
            }
            case "profile_name": {
                // The display name is asked the first time Chat opens (wallet setup no longer asks for it).
                String name = request.getString("name").trim(); if (name.isEmpty() || name.length() > 40) throw new Exception("Enter a display name (up to 40 characters)");
                JSONObject profile = core("profile", new JSONObject().put("name", name));
                if (!profile.getJSONObject("contact").getString("id").equals(data.getJSONObject("profile").getString("id"))) throw new Exception("Profile mismatch");
                data.put("profile", profile.getJSONObject("contact")).put("invite", profile.getString("invite")).put("profile_named", true);
                // First chat setup turns online delivery on, only if the user
                // never chose and Parano1d mode is off (no servers there).
                if (!data.has("online_delivery") && !data.optBoolean("paranoid_mode")) { data.put("online_delivery", true); setOnlineDelivery(true); }
                store.save(data); return view();
            }
            case "allow_screenshots": {
                getSharedPreferences("interface", MODE_PRIVATE).edit().putBoolean("allow_screenshots", request.getBoolean("enabled")).apply();
                runOnUiThread(this::applyCapturePolicy); return view();
            }
            case "scan_qr": {
                String kind = request.getString("kind"), scanId = request.getString("scan_id");
                if (!(kind.equals("address") || kind.equals("contact") || kind.equals("mining")) || !scanId.matches("[0-9]{1,12}")) throw new Exception("Choose an address or contact to scan.");
                if (pendingScanId != null) throw new Exception("Finish the current scan first.");
                pendingScanKind = kind; pendingScanId = scanId;
                runOnUiThread(() -> {
                    try {
                        if (appLock.needsUnlock() || !visible) throw new Exception("Unlock Parano1d before scanning.");
                        com.journeyapps.barcodescanner.ScanOptions options = new com.journeyapps.barcodescanner.ScanOptions()
                            .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                            .setCaptureActivity(QrScanActivity.class).setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(false)
                            .setPrompt(kind.equals("mining") ? "Point your camera at the pairing QR on your mining computer's dashboard." : kind.equals("contact") ? "Point your camera at a Parano1d contact QR code." : "Point your camera at a NOID receiving QR code.");
                        Intent scan = options.createScanIntent(this).putExtra("paramo1d.scan.kind", kind);
                        startActivityForResult(scan, QR_SCAN_REQUEST);
                    } catch (Exception unavailable) {
                        pendingScanId = null; pendingScanKind = null;
                        try { deliverScan(new JSONObject().put("scan_id", scanId).put("error", "The camera could not be opened. You can paste the text instead.")); } catch (Exception ignored) {}
                    }
                });
                return new JSONObject().put("opening", true);
            }
            case "lock_now": appLock.lock(); pauseNode(); runOnUiThread(this::showLockScreen); return new JSONObject().put("locked", true);
            case "phone_security": runOnUiThread(() -> startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))); return new JSONObject().put("opening", true);
            case "notification_settings": runOnUiThread(() -> startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()))); return new JSONObject().put("opening", true);
            case "chat_storage": return ChatHistory.summary(data, store.savedBytes(), SecureStore.ADMISSION_BYTES);
            // Audit 177b: Message requests. Names are as the sender claims; accepting is only by adding their own invitation.
            case "requests_open": {
                String sender = request.getString("sender"); if (!sender.matches("[0-9a-f]{64}")) throw new Exception("Choose a request.");
                return new JSONObject().put("sender", sender).put("messages", ChatInbox.requestMessages(requests(), sender));
            }
            case "requests_delete": { String sender = request.getString("sender"); if (!sender.matches("[0-9a-f]{64}")) throw new Exception("Choose a request."); discardRequests(sender); return view(); }
            case "requests_block": {
                String sender = request.getString("sender"); if (!sender.matches("[0-9a-f]{64}")) throw new Exception("Choose a request.");
                JSONObject safety = chatSafety(); JSONArray blocked = safety.getJSONArray("blocked"); boolean listed = false;
                for (int i = 0; i < blocked.length(); i++) listed |= blocked.getString(i).equals(sender);
                if (!listed) blocked.put(sender);
                data.put("chat_safety", safety); discardRequests(sender); return view();
            }
            // R1: "Show older messages (n)" and deleting an older message.
            case "chat_older": {
                String scope = request.getString("chat"); if (!scope.equals("g:parano1d") && !scope.matches("d:[0-9a-f]{64}")) throw new Exception("Choose a conversation.");
                java.io.File file = new java.io.File(getFilesDir(), "older.pmc");
                return new JSONObject().put("chat", scope).put("messages", file.exists() ? ChatInbox.olderFor(readSide(older()), data, scope) : new JSONArray());
            }
            case "chat_older_delete": {
                String messageId = request.getString("id"); if (!messageId.matches("[0-9a-f]{64}")) throw new Exception("Choose a message.");
                removeOlder(new HashSet<>(java.util.Collections.singleton(messageId)), null); store.save(data); return view();
            }
            case "delete_conversation": {
                if (!request.optBoolean("confirmed")) throw new Exception("Confirm deleting this chat and contact.");
                String scope = request.getString("chat");
                JSONObject next = ChatHistory.deleteConversation(data, scope, System.currentTimeMillis());
                ChatHistory.apply(data, next); removeOlder(new HashSet<>(), scope); store.save(data);
                android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
                manager.cancel(1000 + Math.floorMod(scope.hashCode(),1000000));
                if (data.getJSONArray("messages").length() < ChatHistory.MAX_MESSAGES && !store.isFull()) manager.cancel("storage", 1);
                if (scope.equals(activeConversation)) activeConversation = "";
                nextInboxAttempt = 0; poll(); return view();
            }
            case "clear_history": {
                if (!request.optBoolean("confirmed")) throw new Exception("Confirm clearing local chat history.");
                JSONObject next = ChatHistory.clear(data, request.optJSONArray("chats"), System.currentTimeMillis());
                ChatHistory.apply(data, next);
                JSONArray cleared = request.getJSONArray("chats"); for (int i = 0; i < cleared.length(); i++) removeOlder(new HashSet<>(), cleared.getString(i));
                store.save(data);
                android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
                JSONArray selected = request.getJSONArray("chats");
                for (int i = 0; i < selected.length(); i++) manager.cancel(1000 + Math.floorMod(selected.getString(i).hashCode(),1000000));
                if (data.getJSONArray("messages").length() < ChatHistory.MAX_MESSAGES && !store.isFull()) manager.cancel("storage", 1);
                nextInboxAttempt = 0; poll(); return view();
            }
            case "view_chat": {
                String scope = request.getString("chat");
                if (!scope.isEmpty() && !scope.equals("g:parano1d") && !scope.matches("d:[0-9a-f]{64}")) throw new Exception("Choose a conversation.");
                activeConversation = scope;
                paymentFlow=request.optBoolean("payment_flow");
                walletVisible=request.optBoolean("wallet_visible");
                return new JSONObject().put("updated",true);
            }
            case "mark_read": {
                String scope = request.getString("chat"), marker = request.getString("message_id"); JSONArray messages = data.getJSONArray("messages"); boolean found = false;
                if (!visible) return view();
                if (!scope.equals("g:parano1d") && !scope.matches("d:[0-9a-f]{64}")) throw new Exception("Choose a conversation.");
                for (int i = 0; i < messages.length(); i++) if (messages.getJSONObject(i).getString("id").equals(marker) && messages.getJSONObject(i).getString("chat").equals(scope)) { found = true; break; }
                if (found && !data.getJSONObject("read_messages").optString(scope).equals(marker)) { data.getJSONObject("read_messages").put(scope,marker); store.save(data); }
                if (found) getSystemService(android.app.NotificationManager.class).cancel(1000 + Math.floorMod(scope.hashCode(),1000000));
                return view();
            }
            case "paranoid_mode": {
                if (!watchMode()) requireIdentity();
                boolean enabled=request.getBoolean("enabled");
                if(enabled==data.optBoolean("paranoid_mode")) return view();
                if(paymentFlow || data.getJSONObject("chain").optBoolean("running") && !data.getJSONObject("chain").optBoolean("automatic")) throw new Exception("Finish the current wallet operation before changing mode.");
                nativeResult(new JSONObject().put("op","wallet_mode").put("enabled",enabled));
                data.put("paranoid_mode",enabled);data.remove("pending_review");WatchBridge.publish(this);
                connectionGeneration++;nextInboxAttempt=0;
                directClient.dispatcher().cancelAll();
                data.getJSONObject("chain").put("mode_pending",enabled);
                nodeControl(false);nextNodeAttempt=0;store.save(data);
                paranoidModeHaptic();
                return view();
            }
            case "vpn_help_seen": {
                if (!watchMode()) requireIdentity();
                if(request.optString("id").matches("(chat|wallet):[0-9]{1,16}")) {
                    long until=System.currentTimeMillis()+ConnectionAdvisor.COOLDOWN_MS;
                    connectionAdvisor.suppressUntil(until);
                    application.getSharedPreferences("interface",MODE_PRIVATE).edit().putLong("vpn_hint_until",until).apply();
                }
                return view();
            }
            case "settings": {
                JSONArray relays = request.getJSONArray("relays"); if (relays.length() > 3) throw new Exception("Use up to three delivery relays");
                JSONArray checked = new JSONArray(); for (int i = 0; i < relays.length(); i++) checked.put(checkEndpoint(relays.getString(i)));
                String rpc = request.optString("rpc").trim(); if (!rpc.isEmpty()) checkEndpoint(rpc);
                JSONArray backups = request.optJSONArray("rpc_backups"), checkedBackups = new JSONArray();
                if (backups != null) { if (backups.length() > 3) throw new Exception("Use up to three backup chain endpoints"); for (int i = 0; i < backups.length(); i++) checkedBackups.put(checkEndpoint(backups.getString(i))); }
                data.put("settings", new JSONObject().put("relays", checked).put("rpc", rpc).put("rpc_backups", checkedBackups).put("theme", data.getJSONObject("settings").optString("theme", "system"))); connectionGeneration++; data.put("connection", new JSONObject().put("online", false)); nextInboxAttempt = 0; directClient.dispatcher().cancelAll(); store.save(data); poll(); return view();
            }
            case "join_group": if (!request.getString("room").equals("parano1d")) throw new Exception("This version uses the community chat"); return view();
            case "import_contact": return addContact(core("contact_import", new JSONObject().put("uri", request.getString("uri"))).getJSONObject("contact"));
            case "add_contact": return addContact(request.getJSONObject("contact"));
            case "send": sendMessage(request.getString("chat"), request.getJSONObject("message")); data.getJSONObject("drafts").remove(request.getString("chat")); flushOutbox(); store.save(data); return view();
            case "save_draft": {
                String scope = request.getString("chat"), text = request.optString("text"), reply = request.optString("reply_to");
                if (!scope.equals("g:parano1d") && !scope.matches("d:[0-9a-f]{64}")) throw new Exception("Choose a conversation.");
                if (scope.startsWith("d:")) findContact(scope.substring(2));
                if (text.getBytes(StandardCharsets.UTF_8).length > 4096 || !reply.isEmpty() && !reply.matches("[0-9a-f]{64}")) throw new Exception("Draft exceeds the message limit.");
                JSONObject drafts = data.getJSONObject("drafts");
                JSONObject next = new JSONObject().put("text", text).put("reply_to", reply);
                if (!drafts.has(scope) && text.isEmpty() && reply.isEmpty() || drafts.has(scope) && drafts.getJSONObject(scope).toString().equals(next.toString())) return new JSONObject().put("saved", true);
                if (text.isEmpty() && reply.isEmpty()) drafts.remove(scope);
                else {
                    if (!drafts.has(scope) && drafts.length() >= 1001) throw new Exception("Too many saved drafts.");
                    drafts.put(scope, next);
                }
                store.save(data); return new JSONObject().put("saved", true);
            }
            case "preview_contact": return core("contact_import", new JSONObject().put("uri", request.getString("uri")));
            case "open_link": {
                String target = request.getString("url");
                java.net.URI link = new java.net.URI(target);
                if (target.length() > 8192 || !("https".equalsIgnoreCase(link.getScheme()) || "http".equalsIgnoreCase(link.getScheme())) || link.getHost() == null || link.getUserInfo() != null) throw new Exception("Choose a valid web link.");
                runOnUiThread(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)).addCategory(Intent.CATEGORY_BROWSABLE)); } catch (android.content.ActivityNotFoundException unavailable) { android.widget.Toast.makeText(this, "Install a browser to open web links.", android.widget.Toast.LENGTH_SHORT).show(); } });
                return new JSONObject().put("opening", true);
            }
            case "explorer_tx": {
                // iOS ExplorerPages: open the Parano1d World explorer if its status check answers 200 within 4 s, otherwise NOID Scan.
                String txid = request.getString("txid").toLowerCase(java.util.Locale.ROOT);
                if (!txid.matches("[0-9a-f]{64}")) throw new Exception("Not a valid transaction ID.");
                new Thread(() -> {
                    boolean primary = false;
                    java.net.HttpURLConnection status = null;
                    try {
                        status = (java.net.HttpURLConnection) new java.net.URL("https://parano1dworld.com/api/explorer/status").openConnection();
                        status.setInstanceFollowRedirects(false); status.setUseCaches(false);
                        status.setConnectTimeout(4000); status.setReadTimeout(4000);
                        primary = status.getResponseCode() == 200;
                    } catch (Exception unavailable) { primary = false; }
                    finally { if (status != null) status.disconnect(); }
                    String target = primary ? "https://parano1dworld.com/#Explorer/tx/" + txid : "https://parano1d-scan.com/tx/" + txid;
                    runOnUiThread(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)).addCategory(Intent.CATEGORY_BROWSABLE)); } catch (android.content.ActivityNotFoundException none) { android.widget.Toast.makeText(this, "Install a browser to open web links.", android.widget.Toast.LENGTH_SHORT).show(); } });
                }, "explorer-link").start();
                return new JSONObject().put("opening", true);
            }
            case "invoice": {
                core("amount", new JSONObject().put("amount", request.getString("amount")));
                String address = data.getJSONObject("profile").getString("address");
                JSONObject invoice = new JSONObject().put("type", "invoice").put("invoice_id", randomHex(16)).put("address", address).put("amount", request.getString("amount")).put("memo", request.optString("memo"));
                sendMessage("d:" + request.getString("contact_id"), invoice); flushOutbox(); store.save(data); return view();
            }
            case "poll": if (!watchMode()) poll(); return view();
            case "public_chat": { requireIdentity(); boolean on = request.getBoolean("enabled"); data.put("public_chat", on); publicChatEnabled = on; connectionGeneration++; nextInboxAttempt = 0; store.save(data); WatchBridge.publish(this); poll(); return view(); }
            case "watch_settings": return WatchPolicy.settings(this);
            case "moderation_refresh": {
                // Called by the UI only while #parano1d is open; the app is unlocked when ops run. Offline mode makes no request.
                if (data.optJSONObject("connection") == null || !data.getJSONObject("connection").optBoolean("online") || !publicChatEnabled) return view();
                final boolean tunnel = data.optBoolean("protected_route");
                final JSONObject copy = new JSONObject(data.toString());
                new Thread(() -> {
                    try {
                        if (ModerationFeed.refresh(application, copy, tunnel)) {
                            final JSONObject accepted = copy.getJSONObject("moderation");
                            chatWorker.execute(() -> { try { data.put("moderation", accepted); store.save(data); publishView(); } catch (Exception ignored) { } });
                        }
                    } catch (Exception rejected) { android.util.Log.i("Paramo1dWallet", "Moderation list not applied: " + rejected.getMessage()); }
                }, "parano1d-moderation").start();
                return view();
            }
            case "chat_safety_set": {
                // Chat rules agreement, on-device blocking and chat order (like iOS ChatSafety); stored only on this phone.
                JSONObject safety = chatSafety();
                if (request.has("rules_accepted")) safety.put("rules_accepted", request.getBoolean("rules_accepted"));
                if (request.has("blocked")) {
                    JSONArray in = request.getJSONArray("blocked"), out = new JSONArray(); Set<String> seen = new HashSet<>();
                    for (int i = 0; i < in.length() && out.length() < 2048; i++) { String id = in.getString(i); if (id.matches("[0-9a-f]{16,128}") && seen.add(id)) out.put(id); }
                    safety.put("blocked", out);
                    for (int i = 0; i < out.length(); i++) if (ChatInbox.requestMessages(requests(), out.getString(i)).length() > 0) discardRequests(out.getString(i));
                }
                if (request.has("order")) {
                    JSONArray in = request.getJSONArray("order"), out = new JSONArray(); Set<String> seen = new HashSet<>();
                    for (int i = 0; i < in.length() && out.length() < 4096; i++) { String key = in.getString(i); if (key.matches("[dg]:[0-9A-Za-z_-]{1,128}") && seen.add(key)) out.put(key); }
                    safety.put("order", out);
                }
                data.put("chat_safety", safety); store.save(data); return view();
            }
            case "prefs_set": {
                JSONObject prefs = data.optJSONObject("prefs") == null ? new JSONObject().put("live_prices", true) : data.getJSONObject("prefs");
                if (request.has("live_prices")) prefs.put("live_prices", request.getBoolean("live_prices"));
                if (request.has("external_media")) prefs.put("external_media", request.getBoolean("external_media"));
                if (request.has("background_node")) { prefs.put("background_node", request.getBoolean("background_node")); NodeSyncJob.schedule(application, request.getBoolean("background_node") && data.has("seed_hex")); }
                data.put("prefs", prefs); store.save(data); return view();
            }
            case "crash_reports": return CrashReports.list(this);
            case "crash_delete": return CrashReports.delete(this, request.getString("id"));
            case "crash_email": {
                Intent mail = CrashReports.email(this, request.getString("id"));
                if (mail.resolveActivity(getPackageManager()) == null) throw new Exception("No email app is set up. Copy the report and send it to support@koajent.com.");
                runOnUiThread(() -> { try { startActivity(mail); } catch (android.content.ActivityNotFoundException unavailable) { } });
                return new JSONObject().put("opening", true);
            }
            case "report_email": {
                // The person reviews and sends the report in their own email app; nothing else is attached.
                String subject = request.getString("subject"), body = request.getString("body");
                if (subject.length() > 300 || body.length() > 20000) throw new Exception("Report is too long.");
                Intent mail = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, new String[]{"support@koajent.com"})
                    .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body);
                if (mail.resolveActivity(getPackageManager()) == null) throw new Exception("No email app is set up. Copy the report and send it to support@koajent.com.");
                runOnUiThread(() -> { try { startActivity(mail); } catch (android.content.ActivityNotFoundException unavailable) { } });
                return new JSONObject().put("opening", true);
            }
            case "watch_settings_set": WatchPolicy.update(this, request); WatchBridge.publish(this); return WatchPolicy.settings(this);
            case "wallet_pin_set": WalletPin.set(this, request.optString("current"), request.getString("pin")); WatchPolicy.endSessions(); return WatchPolicy.settings(this);
            case "theme": {
                String theme = request.getString("value");
                if (!theme.equals("light") && !theme.equals("dark") && !theme.equals("system") && !theme.equals("world") && !theme.equals("cyber")) throw new Exception("Choose a display theme");
                data.getJSONObject("settings").put("theme", theme); getSharedPreferences("interface", MODE_PRIVATE).edit().putString("theme", theme).apply(); store.save(data); runOnUiThread(() -> applySystemTheme(theme)); WatchBridge.publish(this); return view();
            }
            case "background_enable":
                askNotificationPermission();
                application.startForegroundService(new Intent(application, DeliveryService.class)); data.put("background_delivery", true); store.save(data); return view();
            case "background_disable":
                stopDelivery(); application.stopService(new Intent(application, DeliveryService.class)); return view();
            case "protection_enable":
                if(data.getJSONObject("chain").optBoolean("running") && !data.getJSONObject("chain").optBoolean("automatic") && !activeChainOperation.equals("sync")) throw new Exception("Wait for the current wallet operation");
                restartingWalletRoute=data.getJSONObject("chain").optBoolean("running");
                nodeControl(false);
                askNotificationPermission();
                if (request.has("prefer_https")) {
                    if (!data.has("tunnel_config")) data.put("tunnel_config", tunnelCore(new JSONObject().put("op", "keypair")));
                    data.getJSONObject("tunnel_config").put("prefer_https", request.getBoolean("prefer_https"));
                }
                application.startForegroundService(new Intent(application, DeliveryService.class));
                data.put("background_delivery", true);
                if(data.getJSONObject("chain").optBoolean("running")) {
                    pendingProtection=true; data.put("protected_route",true);
                    tunnelState=new JSONObject().put("enabled",true).put("checking",true);store.save(data);
                } else beginProtection();
                runOnUiThread(() -> web.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK));
                return view();
            case "protection_disable":
                if (data.getJSONObject("chain").optBoolean("running") && !data.getJSONObject("chain").optBoolean("automatic") && !activeChainOperation.equals("sync")) throw new Exception("Wait for the current wallet operation");
                restartingWalletRoute=data.getJSONObject("chain").optBoolean("running");
                nodeControl(false);
                pendingProtection=false;
                data.put("protected_route", false); store.save(data); protectedRoute = false;
                connectionGeneration++; data.put("connection", new JSONObject().put("online", false)); nextInboxAttempt = 0;
                core("route_set", new JSONObject()); tunnelCore(new JSONObject().put("op", "stop"));
                tunnelState = new JSONObject().put("enabled", false); poll(); return view();
            case "nearby_start": runOnUiThread(this::startNearby); return view();
            case "nearby_stop": runOnUiThread(() -> stopService(new Intent(this, NearbyService.class))); return view();
            case "nearby_scan": NearbyService.scan(); return view();
            case "nearby_connect": NearbyService.connect(request.getString("address")); return view();
            case "nearby_visible": runOnUiThread(() -> startActivityForResult(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120), 63)); return view();
            case "share_prepared": {
                JSONArray packets = data.getJSONArray("prepared_payments");
                for (int i = 0; i < packets.length(); i++) if (packets.getJSONObject(i).getString("txid").equals(request.getString("txid"))) {
                    String destination = request.optString("chat", "g:parano1d");
                    if (!destination.equals("g:parano1d") && !destination.startsWith("d:")) throw new Exception("Choose the community or a contact");
                    sendMessage(destination, new JSONObject().put("type", "transaction").put("intent_hex", packets.getJSONObject(i).getString("intent_hex"))); watch(request.getString("txid"), destination); flushOutbox(); store.save(data); return view();
                }
                throw new Exception("Authorized payment is unavailable");
            }
            case "retry_payment": {
                JSONArray packets = data.getJSONArray("prepared_payments");
                for (int i = 0; i < packets.length(); i++) if (packets.getJSONObject(i).getString("txid").equals(request.getString("txid"))) { queueOwnBroadcast(packets.getJSONObject(i)); return view(); }
                throw new Exception("Saved payment is unavailable.");
            }
            case "share_receipt": {
                String txid = request.getString("txid"), encoded = ""; boolean verified = false;
                JSONArray receipts = data.getJSONArray("receipts");
                for (int i = 0; i < receipts.length(); i++) { JSONObject receipt = receipts.getJSONObject(i); if (receipt.optBoolean("verified") && receipt.optString("txid").equals(txid)) { verified = true; encoded = receipt.optString("receipt_hex"); break; } }
                if (!verified) throw new Exception("Check this payment before sharing its receipt.");
                if (encoded.isEmpty()) { JSONArray observations = data.getJSONArray("observations"); for (int i = 0; i < observations.length(); i++) { JSONObject message = observations.getJSONObject(i).getJSONObject("message"); if (message.optString("txid").equals(txid) && !message.optString("receipt_hex").isEmpty() && !message.isNull("receipt_hex")) { encoded = message.getString("receipt_hex"); break; } } }
                if (encoded.isEmpty()) throw new Exception("Receipt data is unavailable. Check the payment again.");
                sendMessage(request.getString("chat"), new JSONObject().put("type", "receipt").put("invoice_id", request.getString("invoice_id")).put("receipt_hex", encoded)); flushOutbox(); return view();
            }
            case "copy": {
                String text = request.getString("text"); runOnUiThread(() -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("Parano1d", text);
                    if (android.os.Build.VERSION.SDK_INT >= 33) { android.os.PersistableBundle extras = new android.os.PersistableBundle(); extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true); clip.getDescription().setExtras(extras); }
                    clipboard.setPrimaryClip(clip);
                    // Like iOS: copied addresses and keys expire from the clipboard.
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> { try { ClipData current = clipboard.getPrimaryClip(); if (current != null && current.getItemCount() > 0 && text.contentEquals(current.getItemAt(0).coerceToText(this)) && android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip(); } catch (RuntimeException ignored) {} }, 60_000);
                }); return new JSONObject().put("copied", true).put("expires_seconds", 60);
            }
            case "share": {
                String text = request.getString("text"); runOnUiThread(() -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share contact"))); return new JSONObject().put("shared", true);
            }
            case "backup_export": {
                pendingBackup = core("backup_encrypt", new JSONObject().put("data", data.toString()).put("password", request.getString("password"))).getString("backup");
                runOnUiThread(() -> { backupPickerActive = true; startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, "paramo1d-backup.json"), 40); });
                return new JSONObject().put("exporting", true);
            }
            default: throw new Exception("Unknown application action");
        }
    }

    private static JSONObject findContact(String id) throws Exception {
        JSONArray contacts = data.getJSONArray("contacts"); for (int i = 0; i < contacts.length(); i++) if (contacts.getJSONObject(i).getString("id").equals(id)) return contacts.getJSONObject(i);
        throw new Exception("Add this contact first");
    }
    private static JSONObject addContact(JSONObject contact) throws Exception {
        String id = contact.getString("id"); if (!id.matches("[0-9a-f]{64}")) throw new Exception("Invalid contact identity");
        if (id.equals(data.getJSONObject("profile").getString("id"))) return view();
        JSONArray contacts = data.getJSONArray("contacts");
        for (int i = 0; i < contacts.length(); i++) if (contacts.getJSONObject(i).getString("id").equals(id)) {
            if (!contacts.getJSONObject(i).getString("box_key").equals(contact.getString("box_key"))) throw new Exception("This contact's encryption key has changed"); return view();
        }
        if (contacts.length() >= 1000) throw new Exception("Contact limit reached"); contacts.put(contact);
        JSONArray promoted = promoteRequests(id);
        store.save(data);
        if (promoted.length() > 0) saveRequests(); // after the main store: a crash can duplicate, never lose
        return view();
    }

    // ---- Audit fixes 177/R1/R2/R3: requests, older messages, fair carry queue ----
    private static SecureStore olderStore, requestStore;
    private static JSONObject requests;
    private static JSONObject readSide(SecureStore side) throws Exception {
        try { return side.read(); }
        catch (SecureStore.Damaged damaged) { side.setAside(); return new JSONObject(); } // a read error is reported, not treated as damage
    }
    static JSONObject requests() throws Exception {
        if (requests == null) {
            if (requestStore == null) requestStore = new SecureStore(application, "requests.pmc", (int) (ChatInbox.REQUEST_BYTES * 2));
            JSONObject loaded = readSide(requestStore); ChatInbox.normalizeRequests(loaded); requests = loaded;
        }
        return requests;
    }
    private static void saveRequests() throws Exception { requests(); requestStore.save(requests); }
    private static SecureStore older() {
        if (olderStore == null) olderStore = new SecureStore(application, "older.pmc", (int) (ChatInbox.OLDER_BYTES + 8L * 1024 * 1024));
        return olderStore;
    }
    private static Set<String> queuedIds() throws Exception {
        Set<String> ids = new HashSet<>(); JSONArray outbox = data.getJSONArray("outbox");
        for (int i = 0; i < outbox.length(); i++) ids.add(outbox.getJSONObject(i).getString("id"));
        return ids;
    }
    /** R1: past a limit the oldest messages move to Older messages, written before the main store. Never refuses. */
    private static void rolloverHistory() throws Exception {
        JSONArray before = data.getJSONArray("messages");
        JSONObject clearedBefore = data.optJSONObject("cleared_messages") == null ? null : new JSONObject(data.getJSONObject("cleared_messages").toString());
        JSONArray moved = ChatInbox.rollover(data, queuedIds(), System.currentTimeMillis());
        if (moved.length() == 0) return;
        try {
            JSONObject saved = readSide(older()); ChatInbox.addOlder(saved, moved); older().save(saved);
            data.put("older_counts", ChatInbox.olderCounts(saved, data)).put("storage_full", false);
        } catch (Exception failed) { data.put("messages", before); if (clearedBefore != null) data.put("cleared_messages", clearedBefore); throw failed; }
    }
    /** 177b: a sender who becomes a contact (from their own invitation) is accepted; their requests move into chat. */
    private static JSONArray promoteRequests(String sender) throws Exception {
        ChatInbox.accept(data, sender);
        JSONArray taken = ChatInbox.takeRequests(requests(), sender);
        JSONArray messages = data.getJSONArray("messages");
        for (int i = 0; i < taken.length(); i++) {
            JSONObject local = taken.getJSONObject(i).put("status", "received");
            if (local.getJSONObject("message").optString("type").equals("transaction")) try { local.put("payment", core("broadcast_preview", new JSONObject().put("intent_hex", local.getJSONObject("message").getString("intent_hex")))); } catch (Exception unreadable) { }
            messages.put(local);
        }
        if (taken.length() > 0) rolloverHistory();
        return taken;
    }
    /** Deletes one sender's requests and remembers their IDs so a resend is ignored. */
    private static void discardRequests(String sender) throws Exception {
        JSONArray taken = ChatInbox.takeRequests(requests(), sender); long now = System.currentTimeMillis();
        for (int i = 0; i < taken.length(); i++) ChatInbox.tombstone(data, taken.getJSONObject(i).getString("id"), now);
        store.save(data); saveRequests();
    }
    /** Removes older messages (by ID or a whole conversation) and remembers their IDs. */
    private static void removeOlder(Set<String> ids, String chat) throws Exception {
        java.io.File file = new java.io.File(application.getFilesDir(), "older.pmc");
        if (!file.exists()) return;
        JSONObject saved = readSide(older());
        Set<String> removed = ChatInbox.removeOlder(saved, ids, chat); long now = System.currentTimeMillis();
        for (String id : removed) ChatInbox.tombstone(data, id, now);
        older().save(saved);
        data.put("older_counts", ChatInbox.olderCounts(saved, data));
    }
    private static void markStorageFull() throws Exception {
        boolean first = !data.optBoolean("storage_full"); data.put("storage_full", true);
        if (!first) return;
        MainActivity activity = active.get();
        if (activity != null && activity.visible && activity.appLock != null && !activity.appLock.needsUnlock()) return;
        try {
            android.app.NotificationManager manager = application.getSystemService(android.app.NotificationManager.class);
            if (!manager.areNotificationsEnabled()) return;
            manager.createNotificationChannel(new android.app.NotificationChannel("storage", "Chat storage", android.app.NotificationManager.IMPORTANCE_DEFAULT));
            Intent intent = new Intent(application, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            android.app.PendingIntent open = android.app.PendingIntent.getActivity(application, 2000001, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            manager.notify("storage", 1, new android.app.Notification.Builder(application, "storage").setSmallIcon(R.drawable.ic_message)
                .setContentTitle("Chat storage is full").setContentText("Open Parano1d to choose which chats to clear.")
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build());
        } catch (RuntimeException unavailable) { }
    }
    private static void reserveStorage(int bytes) throws Exception {
        try { store.reserveRoom(bytes); }
        catch (SecureStore.Full full) { markStorageFull(); throw full; }
    }
    private static void sendMessage(String chat, JSONObject message) throws Exception {
        if(!publicChatEnabled && !chat.startsWith("d:")) throw new Exception("Community chat is off. Turn on public chat in Settings.");
        JSONObject destination;
        if (chat.equals("g:parano1d")) destination = new JSONObject().put("type", "group").put("room", "parano1d");
        else if (chat.startsWith("d:")) destination = new JSONObject().put("type", "direct").put("contact", findContact(chat.substring(2)));
        else throw new Exception("Unknown conversation");
        JSONObject payment = message.optString("type").equals("transaction") ? core("broadcast_preview", new JSONObject().put("intent_hex", message.getString("intent_hex"))) : null;
        JSONObject envelope = core("seal", new JSONObject().put("sender", data.getJSONObject("profile")).put("destination", destination).put("message", message)).getJSONObject("envelope");
        int envelopeBytes = envelope.toString().getBytes(StandardCharsets.UTF_8).length;
        if (data.getJSONArray("outbox").toString().getBytes(StandardCharsets.UTF_8).length + envelopeBytes > 4 * 1024 * 1024) throw new Exception("Outgoing queue is full. Reconnect before sending more messages.");
        reserveStorage(Math.multiplyExact(4,envelopeBytes));
        JSONObject local = new JSONObject().put("id", envelope.getString("id")).put("chat", chat).put("sender", data.getJSONObject("profile")).put("message", message).put("created_ms", System.currentTimeMillis()).put("status", "queued");
        if (payment != null) local.put("payment", payment);
        if (!message.optString("type").equals("relay_update")) data.getJSONArray("messages").put(local);
        else recordObservation(local);
        if (chat.startsWith("d:")) ChatInbox.accept(data, chat.substring(2)); // replying accepts
        data.getJSONArray("outbox").put(envelope); rolloverHistory(); store.save(data);
        retainEnvelope(envelope); NearbyService.offer(envelope);
    }
    private static void flushOutbox() throws Exception {
        queueDelivery();
    }
    private static void poll() throws Exception {
        if (offline()) return; // Offline mode: no relay, inbox or chain requests (nearby Bluetooth is separate).
        if (data.optBoolean("protected_route") && !pendingProtection && !tunnelStarting && !data.getJSONObject("chain").optBoolean("running") && !tunnelState.optBoolean("connected") && System.currentTimeMillis() >= nextProtectionAttempt) beginProtection();
        queueDelivery();
        queueInbox();
        retryPendingPayments(false);
        scheduleObservations();
    }
    private static void queueDelivery() throws Exception {
        if (deliveryBusy || tunnelStarting) return;
        if (data.getJSONArray("outbox").length() == 0) return;
        JSONObject snapshot = new JSONObject();
        for (String key : new String[]{"settings", "cursors", "outbox", "profile", "seed_hex"}) snapshot.put(key, data.get(key));
        snapshot.put("outbox",ChatPolicy.envelopes(data.getJSONArray("outbox"),!publicChatEnabled));
        if(snapshot.getJSONArray("outbox").length()==0)return;
        final JSONObject frozen = new JSONObject(snapshot.toString()); deliveryBusy = true;
        deliveryWorker.execute(() -> {
            Set<String> delivered = new HashSet<>(), expired = new HashSet<>();
            try {
                JSONArray relays = frozen.getJSONObject("settings").getJSONArray("relays"); JSONArray outbox = frozen.getJSONArray("outbox");
                for (int j = 0; j < relays.length(); j++) {
                    String relay = relays.getString(j);
                    for (int i = 0; i < Math.min(outbox.length(), 8); i++) {
                        JSONObject envelope = outbox.getJSONObject(i);
                        if(!ChatPolicy.allows(envelope,!publicChatEnabled)) continue;
                        if (envelope.getJSONObject("data").getLong("expires_ms") <= System.currentTimeMillis()) { expired.add(envelope.getString("id")); continue; }
                        try { http(relay, "/v1/messages", envelope, null); delivered.add(envelope.getString("id")); } catch (Exception unavailable) { if (BuildConfig.DEBUG) android.util.Log.d("Paramo1dConnection", unavailable.getClass().getSimpleName() + ": " + unavailable.getMessage()); break; }
                    }
                }
            } catch (Exception unavailable) { }
            chatWorker.execute(() -> { try {
                JSONArray remaining = new JSONArray(), outbox = data.getJSONArray("outbox");
                for (int i = 0; i < outbox.length(); i++) { JSONObject envelope = outbox.getJSONObject(i); if (!delivered.contains(envelope.getString("id")) && !expired.contains(envelope.getString("id"))) remaining.put(envelope); }
                data.put("outbox", remaining);
                JSONArray messages = data.getJSONArray("messages");
                for (int i = 0; i < messages.length(); i++) { JSONObject message = messages.getJSONObject(i); if (delivered.contains(message.getString("id"))) message.put("status", "sent"); if (expired.contains(message.getString("id"))) message.put("status", "expired"); }
                if (!delivered.isEmpty() || !expired.isEmpty()) store.save(data);
                publishView();
            } catch (Exception ignored) { } finally { deliveryBusy = false; if (!delivered.isEmpty() || !expired.isEmpty()) try { queueDelivery(); } catch (Exception ignored) {} } });
        });
    }
    private static void queueInbox() throws Exception {
        if (inboxBusy || tunnelStarting || System.currentTimeMillis() < nextInboxAttempt) return;
        JSONObject snapshot = new JSONObject();
        for (String key : new String[]{"settings", "cursors", "profile", "seed_hex", "connection", "paranoid_mode"}) snapshot.put(key, data.get(key));
        final JSONObject frozen = new JSONObject(snapshot.toString()); inboxBusy = true;
        final long generation = connectionGeneration;
        inboxWorker.execute(() -> {
            JSONArray pages = new JSONArray(); boolean online = false, streaming = false, networkFailed=false;
            try {
                JSONArray relays = frozen.getJSONObject("settings").getJSONArray("relays");
                for (int j = 0; j < relays.length(); j++) {
                    String relay = relays.getString(j), contact = frozen.getJSONObject("profile").getString("id");
                    JSONObject cursors = frozen.getJSONObject("cursors");
                    if(frozen.optBoolean("paranoid_mode")) {
                        String path="/v1/inbox/"+contact+"?after="+cursors.optLong(relay+"|inbox",0)+"&limit=30";
                        try {
                            JSONObject auth=nativeResult(new JSONObject().put("op","read_auth").put("path",path).put("seed_hex",frozen.getString("seed_hex")).put("now_ms",System.currentTimeMillis()));
                            pages.put(new JSONObject().put("relay",relay).put("scope","inbox").put("authenticated",true).put("page",http(relay,path,null,auth)));
                            online=true;
                        } catch(Exception unavailable) {networkFailed|=transportFailure(unavailable);}
                        continue;
                    }
                    String path = "/v1/sync/" + contact + "?group_after=" + cursors.optLong(relay + "|g:parano1d", 0) + "&inbox_after=" + cursors.optLong(relay + "|inbox", 0) + "&wait_ms=" + (online || !frozen.getJSONObject("connection").optBoolean("online") ? 0 : 10000);
                    try {
                        JSONObject auth = nativeResult(new JSONObject().put("op", "read_auth").put("path", path).put("seed_hex", frozen.getString("seed_hex")).put("now_ms", System.currentTimeMillis()));
                        JSONObject both = http(relay, path, null, auth);
                        pages.put(new JSONObject().put("relay", relay).put("scope", "g:parano1d").put("authenticated", false).put("page", both.getJSONObject("group")));
                        pages.put(new JSONObject().put("relay", relay).put("scope", "inbox").put("authenticated", true).put("page", both.getJSONObject("inbox")));
                        online = true; streaming = true;
                    } catch (Exception unavailable) {
                        networkFailed |= transportFailure(unavailable);
                        if (!String.valueOf(unavailable.getMessage()).contains("404")) continue;
                        for (String scope : new String[]{"g:parano1d", "inbox"}) {
                            boolean authenticated = scope.equals("inbox");
                            String endpoint = authenticated ? "/v1/inbox/" + contact : "/v1/groups/parano1d/messages";
                            String legacyPath = endpoint + "?after=" + cursors.optLong(relay + "|" + scope, 0) + "&limit=30";
                            JSONObject auth = authenticated ? nativeResult(new JSONObject().put("op", "read_auth").put("path", legacyPath).put("seed_hex", frozen.getString("seed_hex")).put("now_ms", System.currentTimeMillis())) : null;
                            try { pages.put(new JSONObject().put("relay", relay).put("scope", scope).put("authenticated", authenticated).put("page", http(relay, legacyPath, null, auth))); online = true; } catch (Exception ignored) { }
                        }
                    }
                }
            } catch (Exception unavailable) { }
            final boolean connected = online, immediate = streaming, transportFailed=networkFailed;
            chatWorker.execute(() -> { inboxBusy = false;
                if (generation != connectionGeneration) { nextInboxAttempt = 0; try { queueInbox(); } catch (Exception ignored) {} return; }
                try {
                boolean reconnected = connected && (!data.has("connection") || !data.getJSONObject("connection").optBoolean("online"));
                boolean changed = !data.has("connection") || data.getJSONObject("connection").optBoolean("online") != connected;
                for (int i = 0; i < pages.length(); i++) {
                    JSONObject page = pages.getJSONObject(i); changed |= page.getJSONObject("page").getJSONArray("messages").length() > 0;
                    receivePage(page.getString("relay"), page.getString("scope"), page.getBoolean("authenticated"), page.getJSONObject("page"));
                }
                if (changed) rolloverHistory();
                if(connected) connectionAdvisor.chatConnected();else if(transportFailed) connectionAdvisor.chatFailed(System.currentTimeMillis());
                data.put("connection", new JSONObject().put("online", connected));
                if (connected) retryPendingPayments(reconnected);
                if (changed) store.save(data);
                publishView();
            } catch (Exception ignored) { } finally {
                inboxBusy = false; long delay = data.optBoolean("storage_full") ? 30000 : connected && immediate ? 100 : connected ? 2000 : 5000; nextInboxAttempt = System.currentTimeMillis() + delay;
                if (visible || DeliveryService.isRunning()) deliveryTimer.postDelayed(MainActivity::deliveryPoll, delay);
            } });
        });
    }
    private static void receivePage(String relay, String scope, boolean authenticated, JSONObject page) throws Exception {
        if(!publicChatEnabled && !authenticated)return;
        String cursorKey = relay + "|" + scope; JSONObject cursors = data.getJSONObject("cursors");
        long processed = cursors.optLong(cursorKey);
        if (page.getLong("cursor") < cursors.optLong(cursorKey)) throw new Exception("Relay cursor moved backwards");
        JSONArray deliveries = page.getJSONArray("messages");
        if (deliveries.length() == 0) return;
        Set<String> known = new HashSet<>();
        known.addAll(ChatHistory.clearedIds(data, System.currentTimeMillis()));
        for (String key : new String[]{"messages", "observations"}) { JSONArray list = data.getJSONArray(key); for (int i = 0; i < list.length(); i++) known.add(list.getJSONObject(i).getString("id")); }
        for (int i = 0; i < deliveries.length(); i++) {
            JSONObject envelope = deliveries.getJSONObject(i).getJSONObject("envelope");
            if (known.contains(envelope.getString("id"))) { processed = deliveries.getJSONObject(i).getLong("cursor"); continue; }
            try {
                JSONObject destination = envelope.getJSONObject("data").getJSONObject("destination");
                if (authenticated) { if (!destination.getString("type").equals("direct") || !destination.getJSONObject("contact").getString("id").equals(data.getJSONObject("profile").getString("id"))) continue; }
                else if (!destination.getString("type").equals("group") || !destination.getString("room").equals("parano1d")) continue;
                JSONObject opened = core("open", new JSONObject().put("envelope", envelope));
                JSONObject sender = opened.getJSONObject("sender"); String chat = scope;
                if (authenticated) {
                    // Audit 177: one gate before anything is saved. Blocked: dropped (ID remembered), the cursor still advances.
                    // Not accepted: Message requests (no notification, no Wear, no unread count).
                    String route = ChatInbox.decide(data, sender.getString("id"));
                    if (!route.equals(ChatInbox.ACCEPT)) {
                        String messageId = opened.getString("id");
                        if (route.equals(ChatInbox.REQUEST) && !opened.getJSONObject("message").optString("type").equals("relay_update")) {
                            JSONObject request = new JSONObject().put("id", messageId).put("chat", "d:" + sender.getString("id")).put("sender", sender).put("message", opened.getJSONObject("message")).put("created_ms", envelope.getJSONObject("data").getLong("created_ms")).put("status", "request");
                            if (ChatInbox.addRequest(requests(), request)) saveRequests();
                        } else ChatInbox.tombstone(data, messageId, System.currentTimeMillis());
                        known.add(messageId); processed = deliveries.getJSONObject(i).getLong("cursor"); continue;
                    }
                    addContact(sender); ChatInbox.accept(data, sender.getString("id")); chat = "d:" + sender.getString("id");
                }
                JSONObject local = new JSONObject().put("id", opened.getString("id")).put("chat", chat).put("sender", sender).put("message", opened.getJSONObject("message")).put("created_ms", envelope.getJSONObject("data").getLong("created_ms")).put("status", "received");
                String key = local.getJSONObject("message").getString("type").equals("relay_update") ? "observations" : "messages";
                if (key.equals("observations")) { reserveStorage(Math.multiplyExact(4,envelope.toString().getBytes(StandardCharsets.UTF_8).length)); recordObservation(local); }
                else {
                    if (local.getJSONObject("message").optString("type").equals("transaction")) local.put("payment", core("broadcast_preview", new JSONObject().put("intent_hex", local.getJSONObject("message").getString("intent_hex"))));
                    reserveStorage(Math.multiplyExact(4,envelope.toString().getBytes(StandardCharsets.UTF_8).length));
                    data.getJSONArray(key).put(local);
                    notifyMessage(local);
                }
                retainEnvelope(envelope); NearbyService.offer(envelope);
                known.add(opened.getString("id"));
            } catch (SecureStore.Full full) { markStorageFull(); break; }
            catch (Exception rejected) { }
            processed = deliveries.getJSONObject(i).getLong("cursor");
        }
        cursors.put(cursorKey, processed);
    }

    private void askNotificationPermission() {
        runOnUiThread(() -> {
            if (android.os.Build.VERSION.SDK_INT >= 33 && !notificationPermissionAsked && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionAsked = true; requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 60);
            }
        });
    }
    private static JSONObject chatSafety() throws Exception {
        JSONObject safety = data.optJSONObject("chat_safety");
        if (safety == null) safety = new JSONObject();
        if (!safety.has("blocked")) safety.put("blocked", new JSONArray());
        if (!safety.has("order")) safety.put("order", new JSONArray());
        if (!safety.has("rules_accepted")) safety.put("rules_accepted", false);
        return safety;
    }

    static boolean blockedSender(String id) {
        try { JSONArray blocked = chatSafety().getJSONArray("blocked"); for (int i = 0; i < blocked.length(); i++) if (blocked.getString(i).equals(id)) return true; } catch (Exception ignored) { }
        return false;
    }

    private static void notifyMessage(JSONObject message) {
        try {
            String scope = message.getString("chat");
            if (blockedSender(message.getJSONObject("sender").optString("id"))) return;
            if (scope.startsWith("d:")) WatchBridge.chatUpdate(application, scope);
            MainActivity activity = active.get();
            boolean reading = visible && activity != null && activity.appLock != null && !activity.appLock.needsUnlock() && scope.equals(activeConversation);
            if (!scope.startsWith("d:") || reading || Math.abs(System.currentTimeMillis() - message.getLong("created_ms")) > 120000) return;
            // Settings › Notifications: "New private messages" and "Show alerts while the app is open" (iOS parity).
            if (!WalletAlerts.on(application, WalletAlerts.MESSAGES) || visible && !WalletAlerts.on(application, WalletAlerts.FOREGROUND)) return;
            if (message.getJSONObject("sender").getString("id").equals(data.getJSONObject("profile").getString("id"))) return;
            android.app.NotificationManager manager = application.getSystemService(android.app.NotificationManager.class);
            if (!manager.areNotificationsEnabled()) return;
            manager.createNotificationChannel(new android.app.NotificationChannel("messages", "Private messages", android.app.NotificationManager.IMPORTANCE_DEFAULT));
            int id = 1000 + Math.floorMod(scope.hashCode(),1000000);
            Intent intent = new Intent(application,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_chat",scope);
            android.app.PendingIntent open = android.app.PendingIntent.getActivity(application,id,intent,android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            boolean privatePreview = AppLock.enabled(application);
            WatchBridge.messageEvent(application, scope, message.getJSONObject("sender").optString("name", "Contact"));
            // Like iOS: alerts never carry message text (it would land in Android's notification history and on bridged watches).
            String body = message.getJSONObject("message").optString("type").equals("transaction") ? "Payment message. Open Parano1d to review it." : "Open Parano1d to read it.";
            manager.notify(id,new android.app.Notification.Builder(application,"messages").setLocalOnly(WatchBridge.watchAppSeen).setSmallIcon(R.drawable.ic_message).setContentTitle(privatePreview ? "New private message" : message.getJSONObject("sender").getString("name")).setContentText(body).setVisibility(android.app.Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).setGroup("paramo1d_messages").build());
        } catch (Exception ignored) { }
    }

    private static String checkEndpoint(String endpoint) throws Exception {
        URL url = new URL(endpoint); String host = url.getHost();
        boolean local = host.equals("127.0.0.1") || host.equals("localhost");
        if (!(url.getProtocol().equals("https") || url.getProtocol().equals("http") && local) || url.getUserInfo() != null || url.getRef() != null || url.getQuery() != null) throw new Exception("Use an HTTPS address or loopback HTTP");
        return endpoint.replaceAll("/+$", "");
    }
    private static JSONObject http(String base, String path, JSONObject body, JSONObject auth) throws Exception {
        if (protectedRoute) {
            JSONObject headers = new JSONObject().put("Accept", "application/json");
            if (body != null) headers.put("Content-Type", "application/json");
            if (auth != null) { headers.put("x-paramo1d-time", auth.getString("time_ms")); headers.put("x-paramo1d-signature", auth.getString("signature")); }
            return tunnelCore(new JSONObject().put("op", "request").put("url", checkEndpoint(base) + path).put("method", body == null ? "GET" : "POST").put("body", body == null ? "" : body.toString()).put("headers", headers));
        }
        return httpDirect(base, path, body, auth);
    }
    private static final okhttp3.OkHttpClient directClient = new okhttp3.OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .dns(host -> {
            java.util.ArrayList<java.net.InetAddress> addresses = new java.util.ArrayList<>();
            try { addresses.addAll(okhttp3.Dns.SYSTEM.lookup(host)); } catch (java.net.UnknownHostException unavailable) { if (!host.equals("chat.parano1d.org")) throw unavailable; }
            if (host.equals("chat.parano1d.org")) { java.net.InetAddress fallback = java.net.InetAddress.getByAddress(new byte[]{(byte)159, (byte)198, 45, 62}); if (!addresses.contains(fallback)) addresses.add(fallback); }
            return addresses;
        }).build();
    private static JSONObject httpDirect(String base, String path, JSONObject body, JSONObject auth) throws Exception {
        okhttp3.Request.Builder request = new okhttp3.Request.Builder().url(checkEndpoint(base) + path).header("Accept", "application/json");
        if (auth != null) request.header("x-paramo1d-time", auth.getString("time_ms")).header("x-paramo1d-signature", auth.getString("signature"));
        if (body != null) {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 1024 * 1024) throw new Exception("Message exceeds delivery limit");
            request.post(okhttp3.RequestBody.create(bytes, okhttp3.MediaType.get("application/json")));
        }
        okhttp3.OkHttpClient routeClient = directClient;
        if (protectedRoute) {
            android.net.Network physical = physicalNetwork();
            routeClient = directClient.newBuilder().socketFactory(physical.getSocketFactory()).dns(host -> {
                java.util.ArrayList<java.net.InetAddress> addresses = new java.util.ArrayList<>();
                try { addresses.addAll(java.util.Arrays.asList(physical.getAllByName(host))); } catch (java.net.UnknownHostException unavailable) { if (!host.equals("chat.parano1d.org")) throw unavailable; }
                if (host.equals("chat.parano1d.org")) { java.net.InetAddress fallback = java.net.InetAddress.getByAddress(new byte[]{(byte)159,(byte)198,45,62}); if (!addresses.contains(fallback)) addresses.add(fallback); }
                return addresses;
            }).build();
        }
        try (okhttp3.Response response = routeClient.newCall(request.build()).execute()) {
            if (!response.isSuccessful()) throw new Exception("Relay returned " + response.code());
            if (response.body() == null) throw new Exception("Relay returned an empty reply");
            try (InputStream input = response.body().byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) >= 0) { if (output.size() + count > 4 * 1024 * 1024) throw new Exception("Delivery reply exceeds limit"); output.write(buffer, 0, count); }
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
        }
    }

    private static android.net.Network physicalNetwork() throws Exception {
        android.net.ConnectivityManager connectivity = application.getSystemService(android.net.ConnectivityManager.class);
        android.net.Network fallback = null;
        for (android.net.Network network : connectivity.getAllNetworks()) {
            android.net.NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps == null || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return network;
            if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) fallback = network;
        }
        if (fallback == null) throw new Exception("Connect to Wi-Fi or mobile data first");
        return fallback;
    }
    private static JSONObject tunnelCore(JSONObject request) throws Exception {
        JSONObject response = new JSONObject(NativeTunnel.call(request.toString()));
        if (!response.optBoolean("ok")) throw new Exception(response.optString("error", "Protected connection unavailable"));
        return response.getJSONObject("result");
    }
    private static void beginProtection() throws Exception {
        if (tunnelStarting) return;
        nextProtectionAttempt = System.currentTimeMillis() + 20000;
        if (data.getJSONObject("chain").optBoolean("running")) throw new Exception("Wait for the current wallet operation");
        requireIdentity();
        if (!data.has("tunnel_config")) data.put("tunnel_config", tunnelCore(new JSONObject().put("op", "keypair")));
        JSONObject config = new JSONObject(data.getJSONObject("tunnel_config").toString());
        config.put("token", randomHex(32));
        config.put("network_handle", physicalNetwork().getNetworkHandle());
        javax.net.ssl.TrustManagerFactory trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        trust.init((java.security.KeyStore)null); JSONArray roots = new JSONArray();
        for (javax.net.ssl.TrustManager manager : trust.getTrustManagers()) if (manager instanceof javax.net.ssl.X509TrustManager) for (java.security.cert.X509Certificate certificate : ((javax.net.ssl.X509TrustManager)manager).getAcceptedIssuers()) roots.put(android.util.Base64.encodeToString(certificate.getEncoded(), android.util.Base64.NO_WRAP));
        config.put("root_certificates", roots);
        data.put("protected_route", true); connectionGeneration++; data.put("connection", new JSONObject().put("online", false)); nextInboxAttempt = 0; store.save(data); protectedRoute = true; directClient.dispatcher().cancelAll();
        core("route_set", new JSONObject().put("port", 65534).put("token", config.getString("token")));
        tunnelStarting = true; tunnelState = new JSONObject().put("enabled", true).put("checking", true);
        JSONObject auth = core("read_auth", new JSONObject().put("path", "/v1/tunnel/" + config.getString("public_key")));
        JSONObject enrollment = new JSONObject().put("id", data.getJSONObject("profile").getString("id")).put("public_key", config.getString("public_key")).put("time_ms", Long.parseLong(auth.getString("time_ms"))).put("signature", auth.getString("signature"));
        deliveryWorker.execute(() -> {
            JSONObject result = null; String problem = null;
            try {
                if (!config.has("relay_token")) { JSONObject registered = httpDirect("https://chat.parano1d.org", "/v1/tunnel/enroll", enrollment, null); for (String key : new String[]{"address", "endpoint", "server_public_key", "parameters", "relay_id", "relay_token"}) config.put(key, registered.get(key)); }
                result = tunnelCore(new JSONObject().put("op", "start").put("config", config));
                tunnelCore(new JSONObject().put("op", "request").put("url", "https://chat.parano1d.org/healthz").put("method", "GET"));
                result = tunnelCore(new JSONObject().put("op", "status"));
                if (!protectedRoute) throw new InterruptedException("Connection cancelled");
                nativeResult(new JSONObject().put("op", "route_set").put("port", result.getInt("proxy_port")).put("token", config.getString("token")));
            } catch (Exception error) { problem = error.getMessage(); if (BuildConfig.DEBUG) android.util.Log.d("Paramo1dConnection", "Protected route: " + error.getClass().getSimpleName() + ": " + problem); }
            final JSONObject checked = result; final String failure = problem;
            chatWorker.execute(() -> { try {
                tunnelStarting = false;
                if (!protectedRoute) { core("route_set", new JSONObject()); tunnelCore(new JSONObject().put("op", "stop")); tunnelState = new JSONObject().put("enabled", false); publishView(); return; }
                protectionFailures = failure == null ? 0 : protectionFailures + 1; nextProtectionAttempt = System.currentTimeMillis() + Math.min(60000, 10000L * (1 + protectionFailures)); config.remove("token"); config.remove("root_certificates"); config.remove("network_handle"); data.put("tunnel_config", config); store.save(data);
                tunnelState = new JSONObject().put("enabled", true).put("checking", false).put("connected", failure == null);
                if (failure == null && checked != null) tunnelState.put("transport", checked.optString("transport"));
                if (failure != null) tunnelState.put("problem", failure.contains("EPERM") || failure.contains("Binding socket") ? "Your other VPN blocks direct connections. Exclude Parano1d in its per-app settings, then retry." : "Protected connection could not reach the network. Retry or connect nearby.");
                if(failure==null) nextNodeAttempt=0;
                publishView(); if (failure == null) poll();
            } catch (Exception ignored) { } });
        });
    }

    private void paranoidModeHaptic() {
        runOnUiThread(() -> {
            if (android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return;
            try {
                android.os.Vibrator vibrator;
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    android.os.VibratorManager manager = getSystemService(android.os.VibratorManager.class);
                    vibrator = manager == null ? null : manager.getDefaultVibrator();
                } else vibrator = getSystemService(android.os.Vibrator.class);
                if (vibrator == null || !vibrator.hasVibrator()) return;
                android.os.VibrationEffect effect = android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE);
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    vibrator.vibrate(effect, new android.os.VibrationAttributes.Builder().setUsage(android.os.VibrationAttributes.USAGE_TOUCH).build());
                } else {
                    vibrator.vibrate(effect, new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build());
                }
            } catch (RuntimeException unavailable) {
                web.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            }
        });
    }

    private void walletScreenAwake(boolean awake) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            if (awake) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }
    private void startChainAction(String id, JSONObject request) throws Exception {
        startChainAction(id,request,false);
    }
    private void startChainAction(String id, JSONObject request, boolean automatic) throws Exception {
        if (watchMode()) { if (!request.getString("op").equals("sync")) throw new Exception("This is a watch-only address, so it can't send or sign. Add its master key to use it fully."); }
        else requireIdentity();
        if (data.getJSONObject("chain").optBoolean("running")) {
            if (!automatic && request.getString("op").equals("sync") && data.getJSONObject("chain").optBoolean("automatic")) {
                if (pendingRefresh!=null || pendingChain!=null) throw new Exception("Wait for the current wallet operation");
                if (nativeResult(new JSONObject().put("op","node_status")).optBoolean("renewing_authorization")) { reply(id,success(view()));return; }
                pendingRefresh=new PendingChain(this,id,new JSONObject(request.toString()));
                walletScreenAwake(true);
                android.util.Log.i("Paramo1dWallet","Balance refresh joined the active check");
                return;
            }
            if (!automatic && data.getJSONObject("chain").optBoolean("automatic") && pendingChain==null) { pendingChain=new PendingChain(this,id,new JSONObject(request.toString()));nextNodeAttempt=System.currentTimeMillis()+10000;return; }
            throw new Exception("Wait for the current wallet operation");
        }
        boolean fromWatch = request.optBoolean("watch"); request.remove("watch");
        boolean watchReady = fromWatch && !getSystemService(android.app.KeyguardManager.class).isDeviceLocked();
        if (!nodeAllowed() && !request.getString("op").equals("payment_send") && !watchReady) throw new Exception("Open Parano1d to update your wallet");
        if (nodeAllowed()) nodeControl(true);
        if (!automatic) nextNodeAttempt=System.currentTimeMillis()+10000;
        if (request.getString("op").equals("payment_send")) reserveStorage(2 * 1024 * 1024);
        if(automatic) request.put("follow",true);
        JSONObject snapshot = new JSONObject().put("seed_hex",watchMode()?data.getString("watch_key"):WalletAccounts.activeSeed(data)).put("account_index", watchMode() ? 0 : WalletAccounts.activeIndex(data)).put("owner",watchMode()?data.getString("watch_address"):ownerAddress()).put("settings",new JSONObject(data.getJSONObject("settings").toString())).put("paranoid_mode",data.optBoolean("paranoid_mode"));
        snapshot.put("online",data.getJSONObject("connection").optBoolean("online"));
        if (watchMode()) snapshot.put("owner_address", data.getString("watch_address"));
        if (request.getString("op").equals("payment_preview")) snapshot.put("prepared_payments",new JSONArray(data.getJSONArray("prepared_payments").toString())).put("receipts",new JSONArray(data.getJSONArray("receipts").toString()));
        if (request.getString("op").equals("payment_send")) snapshot.put("pending_review",new JSONObject(data.getJSONObject("pending_review").toString()));
        if (request.getString("op").startsWith("asset_send_") || request.getString("op").equals("asset_reconcile")) {
            snapshot.put("profile", new JSONObject(data.getJSONObject("profile").toString()).put("address", ownerAddress())).put("backup_saved_ms", data.optLong("backup_saved_ms"));
            if (data.has("observations")) snapshot.put("observations", new JSONArray(data.getJSONArray("observations").toString()));
        }
        if (request.getString("op").equals("broadcast_preview") || request.getString("op").equals("broadcast_send")) {
            JSONObject found = null; JSONArray messages = data.getJSONArray("messages");
            for (int i = 0; i < messages.length(); i++) if (messages.getJSONObject(i).getString("id").equals(request.getString("message_id"))) found = messages.getJSONObject(i);
            if (found == null || !found.getJSONObject("message").getString("type").equals("transaction")) throw new Exception("Payment packet is unavailable");
            request.put("intent_hex", found.getJSONObject("message").getString("intent_hex"));
        }
        data.getJSONObject("chain").put("running", true).put("automatic",automatic);
        activeChainOperation=request.getString("op");
        try {
        publishView();
        if (!automatic) walletScreenAwake(true);
        chainWorker.execute(() -> {
            final long operationStarted=android.os.SystemClock.elapsedRealtime();
            if (!automatic && request.optString("op").equals("sync")) android.util.Log.i("Paramo1dWallet","Balance refresh started");
            try {
                final JSONObject completed = chainAction(request, snapshot);
                postChainResult(() -> {
                    Exception completionError = null;
                    try {
                        if(snapshot.optBoolean("paranoid_mode")!=data.optBoolean("paranoid_mode")) {
                            nextNodeAttempt=0; data.getJSONObject("chain").put("retrying",false);
                            reply(id,success(view())); return;
                        }
                        JSONObject result = completed;
                        if(request.optString("op").equals("sync")) {
                            connectionAdvisor.walletConnected();
                            if(snapshot.optBoolean("paranoid_mode")&&!result.getJSONObject("chain").optBoolean("deferred"))logWalletMetrics("Completed P2P check");
                        }
                        if (request.optString("op").equals("sync") && (!automatic || pendingRefresh!=null || !result.getJSONObject("chain").optBoolean("deferred"))) android.util.Log.i("Paramo1dWallet","Balance refresh finished in "+(android.os.SystemClock.elapsedRealtime()-operationStarted)+" ms: "+(result.getJSONObject("chain").optBoolean("deferred")?"unchanged":"verified"));
                        if(result.optJSONObject("chain")!=null && result.getJSONObject("chain").optBoolean("deferred")) {
                            data.getJSONObject("chain").put("retrying",false).put("watched_at_ms",System.currentTimeMillis()).put("watch_ms",android.os.SystemClock.elapsedRealtime()-operationStarted).put("running",false).put("automatic",false);
                            data.getJSONObject("chain").remove("mode_pending");
                            data.getJSONObject("chain").put("transport",data.optBoolean("paranoid_mode")?"direct-p2p":"rpc");
                            nextNodeAttempt=System.currentTimeMillis()+10000;
                            reply(id, success(view()));
                            return;
                        }
                        JSONObject oldChain=data.getJSONObject("chain");
                        boolean changed=!request.getString("op").equals("sync") || oldChain.optLong("height")!=result.getJSONObject("chain").optLong("height") || !oldChain.optString("block_hash").equals(result.getJSONObject("chain").optString("block_hash"));
                        data.getJSONObject("chain").put("running", false);
        if ((data.optBoolean("background_delivery") || data.optBoolean("protected_route")) && !DeliveryService.isRunning()) {
            try { application.startForegroundService(new Intent(application, DeliveryService.class)); } catch (RuntimeException requiresForeground) { }
        }
        if (data.optBoolean("protected_route") && !protectedRoute) beginProtection();
                        String op = request.getString("op");
                        if (op.equals("sync")) data.put("chain", result.getJSONObject("chain").put("running", false).put("automatic",false).put("checked_at_ms", System.currentTimeMillis()));
                        if (op.equals("sync")) try { JSONObject verified = data.getJSONObject("chain"); WalletAlerts.observeBalance(application, ownerAddress(), verified.optString("balance"), verified.optLong("balance_height", verified.optLong("height")), visible); } catch (Exception ignored) { }
                        if (op.equals("payment_preview")) {
                            JSONObject wallet=result.optJSONObject("wallet"),current=data.getJSONObject("chain");
                            if(wallet!=null && wallet.optLong("balance_height")>=current.optLong("balance_height")) {
                                wallet.put("running",false).put("automatic",false).put("observed_height",current.optLong("observed_height")).put("checked_at_ms",result.optBoolean("using_saved_state")?current.optLong("checked_at_ms"):System.currentTimeMillis());
                                data.put("chain",wallet);
                            }
                            data.put("pending_review", result);
                            JSONObject publicReview = new JSONObject(result.toString()); publicReview.remove("unsigned_intent"); result = publicReview;
                        }
                        if (op.equals("payment_send")) {
                            data.remove("pending_review"); data.getJSONArray("prepared_payments").put(result);
                            if (!result.optString("replaces_txid").isEmpty()) {
                                JSONArray packets = data.getJSONArray("prepared_payments");
                                for (int i = 0; i < packets.length(); i++) {
                                    JSONObject previous = packets.getJSONObject(i);
                                    if (previous.optString("txid").equals(result.getString("replaces_txid")) && epochRejection(previous.optString("delivery_error"))) previous.put("replaced_by", result.getString("txid"));
                                }
                            }
                            store.save(data);
                            String destination = result.optString("chat");
                            if (destination.startsWith("d:")) try { sendMessage(destination, new JSONObject().put("type", "transaction").put("intent_hex", result.getString("intent_hex"))); } catch (Exception deferredChat) { result.put("chat_delivery_error", deferredChat.getMessage()); }
                            watch(result.getString("txid"), destination.startsWith("d:") ? destination : "wallet");
                            store.save(data); queueOwnBroadcast(result);
                            flushOutbox(); result = new JSONObject().put("state", view()).put("txid", result.getString("txid")).put("submitted", false).put("delivery_queued", data.optBoolean("paranoid_mode") || !data.getJSONObject("settings").optString("rpc").isEmpty());
                        }
                        if ((op.equals("asset_send_approve") || op.equals("asset_send_retry")) && result.optBoolean("submitted")) watch(result.getString("txid"), "wallet");
                        if (op.equals("asset_sync")) WatchBridge.nftUpdate(application);
                        if (op.equals("receipt_verify")) { JSONObject receipt = result.getJSONObject("receipt"); boolean exists = false; JSONArray receipts = data.getJSONArray("receipts"); for (int i = 0; i < receipts.length(); i++) if (receipts.getJSONObject(i).getString("txid").equals(receipt.getString("txid"))) { JSONObject previous = receipts.getJSONObject(i); for (String key : new String[]{"message_id","invoice_id"}) if (receipt.optString(key).isEmpty()) receipt.put(key,previous.optString(key)); receipts.put(i,receipt); exists = true; break; } if (!exists) receipts.put(receipt); result.put("state", view()); }
                        if (op.equals("broadcast_send")) { String destination = "g:parano1d"; JSONArray messages = data.getJSONArray("messages"); for (int i = 0; i < messages.length(); i++) if (messages.getJSONObject(i).getString("id").equals(request.getString("message_id"))) { destination = messages.getJSONObject(i).getString("chat"); messages.getJSONObject(i).remove("broadcast_error"); } watch(result.getString("txid"), destination); sendMessage(destination, new JSONObject().put("type", "relay_update").put("txid", result.getString("txid")).put("stage", "submitted").put("receipt_hex", JSONObject.NULL)); flushOutbox(); }
                        data.getJSONObject("chain").put("retrying",false);
                        if (!automatic || changed) store.save(data);
                        if (automatic) nextNodeAttempt=System.currentTimeMillis()+3000;
                        publishView(); reply(id, success(op.equals("sync") ? view() : result));
                    } catch (Exception error) { completionError=error;if(automatic)nextNodeAttempt=System.currentTimeMillis()+15000;reply(id, failure(error)); }
                    finally { finishChainAction(completionError); }
                });
            } catch (Exception error) { postChainResult(() -> { boolean modeChanged=snapshot.optBoolean("paranoid_mode")!=data.optBoolean("paranoid_mode"),routeChanged=restartingWalletRoute; try { if(modeChanged||routeChanged) {nextNodeAttempt=0;data.getJSONObject("chain").put("retrying",false);reply(id,success(view()));return;} if (request.optString("op").equals("sync") && (!automatic || pendingRefresh!=null)) android.util.Log.i("Paramo1dWallet","Balance refresh failed after "+(android.os.SystemClock.elapsedRealtime()-operationStarted)+" ms: "+(String.valueOf(error.getMessage()).startsWith("Peers cannot supply")?error.getMessage():error.getClass().getSimpleName()));recordBroadcastFailure(request, error); if(request.optString("op").equals("sync")&&transportFailure(error)) connectionAdvisor.walletFailed(System.currentTimeMillis()); if(automatic){nextNodeAttempt=System.currentTimeMillis()+15000;data.getJSONObject("chain").put("retrying",nodeAllowed());} reply(id, failure(error)); } catch(Exception ignored){} finally {finishChainAction(modeChanged||routeChanged?null:error);} }); }
        });
        } catch (Exception error) {
            finishChainAction(error);
            throw error;
        }
    }
    private static void postChainResult(Runnable result) {
        // A completed wallet operation must reach the state worker even when
        // the bounded queue is temporarily full. Only the wallet worker waits.
        try { chatWorker.execute(result); }
        catch (java.util.concurrent.RejectedExecutionException full) {
            try { ((ThreadPoolExecutor)chatWorker).getQueue().put(result); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
    private void finishChainAction(Exception error) {
        boolean routeChanged=restartingWalletRoute;restartingWalletRoute=false;activeChainOperation="";
        if(routeChanged) nextNodeAttempt=0;
        PendingChain refresh=pendingRefresh; pendingRefresh=null;
        PendingChain pending=pendingChain; pendingChain=null;
        walletScreenAwake(false);
        try {
            data.getJSONObject("chain").put("running",false).put("automatic",false);
            if(pendingProtection) {
                pendingProtection=false;
                if(data.optBoolean("protected_route")) {
                    try { beginProtection(); }
                    catch(Exception unavailable) {tunnelState=new JSONObject().put("enabled",true).put("checking",false).put("connected",false).put("problem","Protected connection could not start. Open Connections to retry.");}
                }
            }
            publishView();
            if(refresh!=null) refresh.owner.reply(refresh.id,error==null?refresh.owner.success(view()):refresh.owner.failure(error));
        } catch(Exception completionError) {
            if(refresh!=null) refresh.owner.reply(refresh.id,refresh.owner.failure(error==null?completionError:error));
        } finally {
            if(refresh!=null) refresh.owner.walletScreenAwake(false);
            if(pending!=null) {try{pending.owner.startChainAction(pending.id,pending.request,false);}catch(Exception nextError){pending.owner.reply(pending.id,pending.owner.failure(nextError));}}
        }
    }
    private JSONObject chainAction(JSONObject request, JSONObject snapshot) throws Exception {
        String op = request.getString("op");
        String rpc = snapshot.getJSONObject("settings").optString("rpc");
        boolean paranoid=snapshot.optBoolean("paranoid_mode");
        request.put("paranoid_mode",paranoid);
        File artifacts = new File(getFilesDir(), "artifacts"); File chain = new File(getFilesDir(), "chain");
        if (op.equals("broadcast_send") && !paranoid && rpc.isEmpty()) throw new Exception("Add a chain data endpoint in Settings");
        if (op.equals("sync") || op.equals("payment_preview")) {
            installParameters(artifacts);
            if (!new File(artifacts, "legacy").isFile()) throw new Exception("Install the released verification parameters to synchronize");
            if (op.equals("payment_preview") && !paranoid && rpc.isEmpty() && !request.optBoolean("use_cached")) throw new Exception("Add a chain data endpoint in Settings");
            JSONObject args = new JSONObject(request.toString()).put("artifacts", artifacts.getAbsolutePath()).put("data_dir", chain.getAbsolutePath()).put("rpc", rpc).put("seed_hex", snapshot.getString("seed_hex")).put("account_index", snapshot.optInt("account_index"));
            if (snapshot.has("owner_address")) { if (op.equals("payment_preview")) throw new Exception("Watch-only addresses can't send."); args.put("owner_address", snapshot.getString("owner_address")); }
            if (op.equals("payment_preview")) {
                args.put("online",snapshot.optBoolean("online"));
                // Like iOS 165: skip coins/slots that peers' pending transactions already claim, and stop
                // before signing if the relay floor is above the fixed fee. Best effort; planning works without it.
                if (!request.optBoolean("use_cached") && snapshot.optBoolean("online")) {
                    try { JSONObject pool = nativeResult(new JSONObject().put("op", "peer_mempool").put("artifacts", artifacts.getAbsolutePath())); if (pool.optInt("peers") > 0) args.put("mempool", pool); } catch (Exception ignored) { }
                }
                JSONArray reservations = new JSONArray(), prepared = snapshot.getJSONArray("prepared_payments"), receipts = snapshot.getJSONArray("receipts");
                for (int i = 0; i < prepared.length(); i++) {
                    JSONObject packet = prepared.getJSONObject(i);
                    if (!packet.has("reservation")) continue;
                    JSONObject reservation = new JSONObject(packet.getJSONObject("reservation").toString());
                    for (int j = 0; j < receipts.length(); j++) { JSONObject receipt = receipts.getJSONObject(j); if (receipt.optBoolean("verified") && receipt.optString("txid").equals(packet.getString("txid"))) reservation.put("included_height", receipt.getLong("height")); }
                    reservations.put(reservation);
                }
                return withEndpoints(snapshot.getJSONObject("settings"), args.put("reservations", reservations)).put("chat", request.optString("chat")).put("invoice_id", request.optString("invoice_id")).put("replaces_txid", request.optString("replaces_txid"));
            }
            if (paranoid || !rpc.isEmpty()) return new JSONObject().put("chain", withEndpoints(snapshot.getJSONObject("settings"), args.put("op", "wallet_sync")));
            JSONObject synced = nativeResult(args.put("op", "sync_once")); return new JSONObject().put("chain", synced.getJSONObject("verified"));
        }
        // Token and NFT sends through the iOS Node + wallet cores (see AssetSend). Same rules as iOS: verified
        // parent, canonical review, fresh approval, journal reservation until verified settlement or expiry.
        if (op.equals("asset_send_prepare") || op.equals("asset_send_approve") || op.equals("asset_send_retry") || op.equals("asset_reconcile")) {
            installParameters(artifacts);
            if (!new File(artifacts, "legacy").isFile()) throw new Exception("Install the released verification parameters to synchronize");
            File files = getFilesDir();
            // Runs on the chain worker: reads only the snapshot; the reply step registers status watches.
            if (op.equals("asset_send_prepare")) return AssetSend.prepare(snapshot, artifacts, chain, files, request);
            if (op.equals("asset_send_approve")) return AssetSend.approve(snapshot, artifacts, chain, files, request, snapshot.optBoolean("paranoid_mode"));
            if (op.equals("asset_send_retry")) return AssetSend.retry(snapshot, artifacts, files, request.getString("txid"), snapshot.optBoolean("paranoid_mode")).put("txid", request.getString("txid")).put("submitted", true);
            return AssetSend.reconcile(snapshot, artifacts, chain, files);
        }
        if (op.equals("asset_sync")) {
            installParameters(artifacts);
            if (!new File(artifacts, "legacy").isFile()) throw new Exception("Install the released verification parameters to synchronize");
            // Like the iOS device node: verify the chain from peers and install/catch up the Native State that holds tokens and NFTs.
            return new JSONObject().put("assets", nativeResult(new JSONObject().put("op", "sync_once").put("artifacts", artifacts.getAbsolutePath()).put("data_dir", chain.getAbsolutePath())));
        }
        if (op.equals("payment_send")) {
            JSONObject review = snapshot.getJSONObject("pending_review");
            if (!review.getString("review_id").equals(request.getString("review_id"))) throw new Exception("Payment review has changed");
            JSONObject authorized = nativeResult(WalletKeys.approval(new JSONObject().put("op", "payment_authorize"), request).put("paranoid_mode",paranoid).put("seed_hex", snapshot.getString("seed_hex")).put("account_index", snapshot.optInt("account_index")).put("review_id", review.getString("review_id")).put("unsigned_intent", review.getString("unsigned_intent")).put("data_dir", chain.getAbsolutePath()).put("artifacts", artifacts.getAbsolutePath()).put("online", snapshot.optBoolean("online")));
            authorized.put("chat", review.optString("chat")).put("invoice_id", review.optString("invoice_id")).put("replaces_txid", review.optString("replaces_txid"));
            if (review.has("valid_through_height")) authorized.put("valid_through_height", review.getLong("valid_through_height"));
            for (String key : new String[]{"to", "amount", "fee"}) authorized.put(key, review.get(key));
            authorized.put("submitted", false).put("created_ms", System.currentTimeMillis());
            return authorized;
        }
        if (op.equals("receipt_verify")) {
            installParameters(artifacts);
            JSONObject args = new JSONObject(request.toString()).put("op", "receipt_check").put("artifacts", artifacts.getAbsolutePath()).put("data_dir", chain.getAbsolutePath());
            JSONObject accepted = new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(chain, "accepted.json").toPath()), StandardCharsets.UTF_8));
            args.put("bundle", new File(chain, accepted.getString("bundle")).getAbsolutePath());
            JSONObject receipt = nativeResult(args).put("message_id", request.optString("message_id")).put("invoice_id", request.optString("invoice_id")).put("receipt_hex", request.getString("receipt_hex"));
            return new JSONObject().put("receipt", receipt);
        }
        JSONObject args = new JSONObject(request.toString()).put("rpc", rpc).put("seed_hex", snapshot.getString("seed_hex")).put("account_index", snapshot.optInt("account_index"));
        return withEndpoints(snapshot.getJSONObject("settings"), args);
    }

    private void installParameters(File directory) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new Exception("Cannot prepare verification storage");
        String[] files = getAssets().list("parameters");
        if (files == null || files.length == 0) throw new Exception("Build the APK with the released verification parameters");
        String release;
        try (java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(getAssets().open("parameters/release.id"), StandardCharsets.UTF_8))) { release = input.readLine(); }
        if (release == null || !release.matches("[0-9a-f]{64}")) throw new Exception("Invalid verification release identity");
        File marker = new File(directory, ".release"); boolean complete = marker.isFile() && release.equals(new String(java.nio.file.Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8));
        for (String name : files) complete &= new File(directory, name).isFile();
        if (complete) return;
        for (String name : files) {
            if (!name.matches("[a-z0-9.-]{1,64}")) throw new Exception("Invalid verification parameter name");
            File target = new File(directory, name);
            android.util.AtomicFile file = new android.util.AtomicFile(target); java.io.FileOutputStream output = null;
            try (InputStream input = getAssets().open("parameters/" + name)) {
                output = file.startWrite(); byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                file.finishWrite(output);
            } catch (Exception error) { if (output != null) file.failWrite(output); throw error; }
        }
        android.util.AtomicFile record = new android.util.AtomicFile(marker); java.io.FileOutputStream output = record.startWrite();
        try { output.write(release.getBytes(StandardCharsets.UTF_8)); record.finishWrite(output); } catch (Exception error) { record.failWrite(output); throw error; }
    }

    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (appLock.activityResult(request, result)) return;
        if (request == QR_SCAN_REQUEST) {
            String scanId = pendingScanId, kind = pendingScanKind;
            pendingScanId = null; pendingScanKind = null;
            boolean permitted = unlockedSystemFlows.remove(request);
            if (permitted) appLock.opened();
            if (scanId == null) return;
            String contents = com.journeyapps.barcodescanner.ScanIntentResult.parseActivityResult(result, intent).getContents();
            chatWorker.execute(() -> {
                try {
                    JSONObject response;
                    if (contents == null || !permitted) response = new JSONObject().put("cancelled", true);
                    else if ("mining".equals(kind)) {
                        String code = Mining.parseCode(contents);
                        response = code == null ? new JSONObject().put("error", "That isn't a Parano1d World mining pairing QR. Use the one on your mining computer's dashboard.") : new JSONObject().put("kind", "mining").put("text", code);
                    } else response = nativeResult(new JSONObject().put("op", "qr_parse").put("kind", kind).put("text", contents));
                    deliverScan(response.put("scan_id", scanId));
                } catch (Exception error) { try { deliverScan(new JSONObject().put("scan_id", scanId).put("error", error.getMessage())); } catch (Exception ignored) {} }
            });
            return;
        }
        if (unlockedSystemFlows.remove(request)) appLock.opened();
        if ((request == 40 || request == 41) && backupPickerActive) { backupPickerActive = false; appLock.opened(); }
        if (request == 40 && result == RESULT_OK && intent != null && pendingBackup != null) {
            String backup = pendingBackup; pendingBackup = null;
            chatWorker.execute(() -> { try {
                try (OutputStream output = getContentResolver().openOutputStream(intent.getData())) { if (output == null) throw new Exception("Cannot write backup"); output.write(backup.getBytes(StandardCharsets.UTF_8)); }
                data.put("backup_saved_ms", System.currentTimeMillis()); store.save(data); publishView();
                runOnUiThread(() -> android.widget.Toast.makeText(this, "Backup saved. Keep its password separately.", android.widget.Toast.LENGTH_LONG).show());
            } catch (Exception ignored) { runOnUiThread(() -> android.widget.Toast.makeText(this, "Backup could not be saved. Please try again.", android.widget.Toast.LENGTH_LONG).show()); } });
        } else if (request == 40) pendingBackup = null;
        if (request == 41 && result == RESULT_OK && intent != null && pendingRestorePassword != null) {
            String password = pendingRestorePassword; pendingRestorePassword = null;
            chatWorker.execute(() -> { try (InputStream input = getContentResolver().openInputStream(intent.getData()); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new Exception("Cannot read backup"); byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) { if (output.size() + count > 65 * 1024 * 1024) throw new Exception("Backup exceeds size limit"); output.write(buffer, 0, count); }
                handle(new JSONObject().put("op", "backup_restore").put("password", password).put("backup", output.toString(StandardCharsets.UTF_8.name())));
                runOnUiThread(() -> { if (!isDestroyed()) web.reload(); });
            } catch (Exception error) { runOnUiThread(() -> android.widget.Toast.makeText(this, error.getMessage(), android.widget.Toast.LENGTH_LONG).show()); } });
        } else if (request == 41) pendingRestorePassword = null;
        if (request == 62 && result == RESULT_OK) startNearby();
        if (request == 63) NearbyService.scan();
    }
    private void startNearby() {
        try {
            String[] required = android.os.Build.VERSION.SDK_INT >= 31 ? new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE"} : new String[]{"android.permission.ACCESS_FINE_LOCATION"};
            java.util.ArrayList<String> missing = new java.util.ArrayList<>(); for (String permission : required) if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) missing.add(permission);
            if (android.os.Build.VERSION.SDK_INT >= 33 && !notificationPermissionAsked && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) { notificationPermissionAsked = true; missing.add("android.permission.POST_NOTIFICATIONS"); }
            if (!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]), 61); return; }
            android.bluetooth.BluetoothAdapter adapter = getSystemService(android.bluetooth.BluetoothManager.class).getAdapter();
            if (adapter == null) throw new Exception("Bluetooth is unavailable on this device");
            if (!adapter.isEnabled()) { startActivityForResult(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), 62); return; }
            startForegroundService(new Intent(this, NearbyService.class));
            startActivityForResult(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120), 63);
        } catch (Exception error) { android.widget.Toast.makeText(this, error.getMessage(), android.widget.Toast.LENGTH_LONG).show(); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 60 || request == 61) appLock.opened();
        if (request == 60) { chatWorker.execute(() -> { try { if (data != null) publishView(); } catch (Exception ignored) {} }); return; }
        if (request != 61) return;
        boolean allowed = true;
        for (int i = 0; i < permissions.length; i++) if (!permissions[i].equals("android.permission.POST_NOTIFICATIONS") && grants[i] != android.content.pm.PackageManager.PERMISSION_GRANTED) allowed = false;
        if (allowed) {
            try { android.bluetooth.BluetoothAdapter adapter = getSystemService(android.bluetooth.BluetoothManager.class).getAdapter(); if (adapter == null) throw new Exception("Bluetooth unavailable"); if (!adapter.isEnabled()) startActivityForResult(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), 62); else { startForegroundService(new Intent(this, NearbyService.class)); startActivityForResult(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120), 63); } }
            catch (Exception error) { android.widget.Toast.makeText(this, error.getMessage(), android.widget.Toast.LENGTH_LONG).show(); }
        } else android.widget.Toast.makeText(this, "Nearby needs permission to connect to nearby devices. Internet chat remains available.", android.widget.Toast.LENGTH_LONG).show();
    }
    private static void watch(String txid, String chat) throws Exception {
        JSONArray old = data.getJSONArray("watches"), watches = new JSONArray(); long now = System.currentTimeMillis();
        for (int i = 0; i < old.length(); i++) { JSONObject item = old.getJSONObject(i); if (now - item.optLong("created_ms", now) < 3600000 && !item.optString("stage").equals("included")) watches.put(item); }
        data.put("watches", watches);
        for (int i = 0; i < watches.length(); i++) if (watches.getJSONObject(i).getString("txid").equals(txid) && watches.getJSONObject(i).getString("chat").equals(chat)) return;
        if (watches.length() < 20) watches.put(new JSONObject().put("txid", txid).put("chat", chat).put("created_ms", now).put("last_ms", 0).put("stage", "submitted"));
    }
    private static boolean epochRejection(String error) {
        return error.contains("BadEpochAnchor") || error.startsWith("Payment epoch changed.");
    }
    private static void recordBroadcastFailure(JSONObject request, Exception error) throws Exception {
        if (!request.optString("op").equals("broadcast_send") || !epochRejection(String.valueOf(error.getMessage()))) return;
        JSONArray messages = data.getJSONArray("messages");
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            if (message.optString("id").equals(request.optString("message_id"))) message.put("broadcast_error", String.valueOf(error.getMessage()).substring(0, Math.min(300, String.valueOf(error.getMessage()).length())));
        }
        store.save(data);
    }
    private static void queueOwnBroadcast(JSONObject packet) throws Exception {
        String rpc = data.getJSONObject("settings").optString("rpc"); boolean paranoid=data.optBoolean("paranoid_mode"); if (!paranoid && rpc.isEmpty() || paranoid && !visible) return;
        String txid = packet.getString("txid"); if (!sendingPayments.add(txid)) return;
        packet.put("last_delivery_ms", System.currentTimeMillis());
        final JSONObject frozen = new JSONObject(packet.toString());
        final JSONObject endpoints = new JSONObject(data.getJSONObject("settings").toString());
        try { paymentDeliveryWorker.execute(() -> {
            String failure = null;
            try { withEndpoints(endpoints, new JSONObject(frozen.toString()).put("op", "broadcast_send").put("rpc", rpc).put("paranoid_mode",paranoid).put("artifacts",new File(application.getFilesDir(),"artifacts").getAbsolutePath()).put("data_dir",new File(application.getFilesDir(),"chain").getAbsolutePath())); }
            catch (Exception error) { failure = error.getMessage(); }
            final String error = failure;
            chatWorker.execute(() -> { try {
                JSONArray packets = data.getJSONArray("prepared_payments");
                for (int i = 0; i < packets.length(); i++) if (packets.getJSONObject(i).getString("txid").equals(frozen.getString("txid"))) { packets.getJSONObject(i).put("submitted", error == null); if (error != null) packets.getJSONObject(i).put("delivery_error", error.substring(0, Math.min(300, error.length()))); else packets.getJSONObject(i).remove("delivery_error"); }
                if (error == null) { JSONObject message = new JSONObject().put("type", "relay_update").put("txid", frozen.getString("txid")).put("stage", "submitted").put("receipt_hex", JSONObject.NULL); if (frozen.optString("chat").startsWith("d:")) sendMessage(frozen.getString("chat"), message); else recordObservation(new JSONObject().put("id", randomHex(32)).put("sender", data.getJSONObject("profile")).put("chat", "wallet").put("created_ms", System.currentTimeMillis()).put("message", message)); }
                store.save(data); flushOutbox(); publishView();
            } catch (Exception ignored) { } finally { sendingPayments.remove(txid); } });
        }); } catch (java.util.concurrent.RejectedExecutionException busy) { sendingPayments.remove(txid); }
    }
    private static void retryPendingPayments(boolean reconnected) throws Exception {
        if (!data.getJSONObject("connection").optBoolean("online") || tunnelStarting) return;
        long now = System.currentTimeMillis(); JSONArray packets = data.getJSONArray("prepared_payments"), receipts = data.getJSONArray("receipts");
        for (int i = 0; i < packets.length(); i++) { JSONObject packet = packets.getJSONObject(i);
            if (packet.optBoolean("submitted") || packet.has("replaced_by") || epochRejection(packet.optString("delivery_error")) || sendingPayments.contains(packet.getString("txid")) || now - packet.optLong("created_ms",now) >= 3600000 || !reconnected && now - packet.optLong("last_delivery_ms") < 30000) continue;
            boolean checked = false; for (int j = 0; j < receipts.length(); j++) if (receipts.getJSONObject(j).optBoolean("verified") && receipts.getJSONObject(j).optString("txid").equals(packet.getString("txid"))) { checked = true; break; }
            if (!checked) { queueOwnBroadcast(packet); break; }
        }
    }
    private static void recordObservation(JSONObject observation) throws Exception {
        JSONArray old = data.getJSONArray("observations"), next = new JSONArray();
        String sender = observation.getJSONObject("sender").getString("id"); String txid = observation.getJSONObject("message").getString("txid");
        for (int i = 0; i < old.length(); i++) { JSONObject previous = old.getJSONObject(i); boolean same = previous.getJSONObject("sender").getString("id").equals(sender) && previous.getJSONObject("message").getString("txid").equals(txid);
            if (same && previous.getLong("created_ms") > observation.getLong("created_ms")) return;
            if (!same) next.put(previous);
        }
        if (next.length() < 2000) next.put(observation); data.put("observations", next);
    }
    private static void scheduleObservations() throws Exception {
        String rpc = data.getJSONObject("settings").optString("rpc"); boolean paranoid=data.optBoolean("paranoid_mode"); if (!paranoid && rpc.isEmpty() || paranoid && !visible) return;
        JSONArray watches = data.getJSONArray("watches"); long now = System.currentTimeMillis();
        for (int i = 0; i < watches.length(); i++) {
            JSONObject watched = watches.getJSONObject(i);
            if(!publicChatEnabled && watched.optString("chat").startsWith("g:"))continue;
            if (now - watched.optLong("last_ms") < 30000 || watched.optString("stage").equals("included") || now - watched.optLong("created_ms", now) >= 3600000) continue;
            watched.put("last_ms", now); String txid = watched.getString("txid"); String destination = watched.getString("chat");
            chainWorker.execute(() -> {
                try { JSONObject status = nativeResult(new JSONObject().put("op", "transaction_status").put("rpc", rpc).put("paranoid_mode",paranoid).put("artifacts",new File(application.getFilesDir(),"artifacts").getAbsolutePath()).put("data_dir",new File(application.getFilesDir(),"chain").getAbsolutePath()).put("txid", txid));
                    chatWorker.execute(() -> { try {
                        String stage = status.getString("stage"); if (!(stage.equals("included") || stage.equals("in_mempool"))) return;
                        boolean changed = !watched.getString("stage").equals(stage); if (!changed && System.currentTimeMillis() - watched.optLong("published_ms") < 120000) return;
                        watched.put("stage", stage).put("published_ms", System.currentTimeMillis());
                        if (changed && stage.equals("included")) WalletAlerts.finalizedSend(application, txid, visible);
                        JSONObject message = new JSONObject().put("type", "relay_update").put("txid", txid).put("stage", stage).put("receipt_hex", status.opt("receipt_hex"));
                        if (destination.equals("wallet")) recordObservation(new JSONObject().put("id", randomHex(32)).put("sender", data.getJSONObject("profile")).put("chat", "wallet").put("created_ms", System.currentTimeMillis()).put("message", message)); else sendMessage(destination, message);
                        flushOutbox(); store.save(data);
                    } catch (Exception ignored) {} });
                } catch (Exception ignored) {}
            });
            break;
        }
    }
    static void initializeDelivery(android.content.Context context) {
        application = context.getApplicationContext();
        chatWorker.execute(() -> { try {
            if (store == null) store = new SecureStore(application);
            if (data == null) { data = store.read(); initializeFields(); }
            if (!data.has("seed_hex") || !(data.optBoolean("background_delivery",true) || data.optBoolean("protected_route"))) application.stopService(new Intent(application,DeliveryService.class));
        } catch (Exception ignored) { } });
    }
    static void deliveryPoll() {
        chatWorker.execute(() -> { try { if (data != null && data.has("seed_hex")) { poll(); publishView(); } } catch (Exception ignored) { } });
    }
    static void stopDelivery() {
        chatWorker.execute(() -> { try {
            if (data == null) return;
            data.put("background_delivery", false).put("protected_route", false); store.save(data);
            if (protectedRoute) { connectionGeneration++; data.put("connection", new JSONObject().put("online", false)); nextInboxAttempt = 0; }
            protectedRoute = false; core("route_set", new JSONObject()); tunnelCore(new JSONObject().put("op", "stop"));
            tunnelState = new JSONObject().put("enabled", false); publishView();
        } catch (Exception ignored) { } });
    }
    private static void publishView() throws Exception {
        final JSONObject updated = view(); MainActivity activity = active.get();
        WatchBridge.publish(application);
        if (activity != null && activity.appLock != null && activity.appLock.needsUnlock()) return;
        if (pendingRefresh!=null && updated.optJSONObject("node").optBoolean("renewing_authorization") && updated.optJSONObject("chain").optBoolean("automatic")) {
            PendingChain refresh=pendingRefresh;pendingRefresh=null;refresh.owner.walletScreenAwake(false);refresh.owner.reply(refresh.id,refresh.owner.success(updated));
        }
        final boolean checking = updated.optJSONObject("chain").optBoolean("running") && (!updated.optJSONObject("chain").optBoolean("automatic") || pendingRefresh!=null);
        final String script = "window.paramo1dUpdate(" + updated.toString() + ")";
        if (activity != null) activity.runOnUiThread(() -> { if (!activity.isDestroyed()) {
            if (checking) activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            activity.web.evaluateJavascript(script, null);
        } });
    }
    /** R3: the Nearby carry queue is fair (16 per sender, others at most 96 of 128); your own packets always fit. Newest first. */
    private static void retainEnvelope(JSONObject envelope) throws Exception {
        JSONArray history = data.getJSONArray("envelopes"); java.util.List<JSONObject> queue = new java.util.ArrayList<>();
        for (int i = 0; i < history.length(); i++) queue.add(history.getJSONObject(i));
        java.util.List<JSONObject> next = ChatInbox.fairAdd(queue, envelope, ChatInbox.ownId(data), 4L * 1024 * 1024, System.currentTimeMillis());
        if (next == null) return; // the queue is entirely your own packets
        JSONArray out = new JSONArray(); for (int i = next.size() - 1; i >= 0; i--) out.put(next.get(i));
        data.put("envelopes", out);
    }
    /** Other people's packets waiting to go online, with the same fair rules. Never refuses a packet. */
    private static void relayEnvelope(JSONObject envelope) throws Exception {
        JSONArray outbox = data.getJSONArray("outbox"); java.util.List<JSONObject> queue = new java.util.ArrayList<>();
        for (int i = 0; i < outbox.length(); i++) queue.add(outbox.getJSONObject(i));
        java.util.List<JSONObject> next = ChatInbox.fairAdd(queue, envelope, ChatInbox.ownId(data), 4L * 1024 * 1024, Long.MIN_VALUE);
        if (next != null) data.put("outbox", new JSONArray(next));
    }
    static boolean allowsNearbyEnvelope(JSONObject envelope) {return ChatPolicy.allows(envelope,!publicChatEnabled);}
    static java.util.concurrent.CompletableFuture<JSONObject> nearbyCall(String action, JSONObject args) {
        java.util.concurrent.CompletableFuture<JSONObject> future = new java.util.concurrent.CompletableFuture<>();
        try { chatWorker.execute(() -> { try {
            requireIdentity(); JSONObject result = new JSONObject();
            if (action.equals("hello")) result.put("invite", data.getString("invite")).put("contact", data.getJSONObject("profile"));
            else if (action.equals("sign")) result = core("read_auth", args);
            else if (action.equals("verify")) result = core("verify_link", args);
            else if (action.equals("encrypt")) result = core("link_encrypt", args);
            else if (action.equals("decrypt")) result = core("link_decrypt", args);
            else if (action.equals("contact")) result = core("contact_import", args);
            else if (action.equals("connected")) { addContact(args.getJSONObject("contact")); publishView(); }
            else if (action.equals("inventory")) {
                JSONArray ids = new JSONArray(); Set<String> known = new HashSet<>(); long now = System.currentTimeMillis();
                for (String key : new String[]{"outbox", "envelopes"}) { JSONArray list = data.getJSONArray(key); for (int i = 0; i < list.length() && ids.length() < 128; i++) { JSONObject envelope = list.getJSONObject(i); if(!allowsNearbyEnvelope(envelope))continue; String id = envelope.getString("id"); if (envelope.getJSONObject("data").getLong("expires_ms") > now && known.add(id)) ids.put(id); } }
                result.put("ids", ids);
            } else if (action.equals("missing")) {
                JSONArray wanted = new JSONArray(), requested = args.getJSONArray("ids"); if (requested.length() > 128) throw new Exception("Nearby inventory exceeds limit"); Set<String> known = new HashSet<>();
                known.addAll(ChatHistory.clearedIds(data, System.currentTimeMillis()));
                for (String key : new String[]{"envelopes", "messages", "observations"}) { JSONArray list = data.getJSONArray(key); for (int i = 0; i < list.length(); i++) known.add(list.getJSONObject(i).getString("id")); }
                JSONArray waiting = requests().getJSONArray("messages"); for (int i = 0; i < waiting.length(); i++) known.add(waiting.getJSONObject(i).getString("id")); // already in Message requests
                for (int i = 0; i < requested.length() && wanted.length() < 8; i++) { String id = requested.getString(i); if (id.matches("[0-9a-f]{64}") && !known.contains(id)) wanted.put(id); }
                result.put("ids", wanted);
            } else if (action.equals("packets")) {
                JSONArray requested = args.getJSONArray("ids"); if (requested.length() > 8) throw new Exception("Nearby request exceeds limit"); Set<String> wanted = new HashSet<>(); for (int i = 0; i < requested.length(); i++) wanted.add(requested.getString(i));
                JSONArray packets = new JSONArray(); for (String key : new String[]{"outbox", "envelopes"}) { JSONArray list = data.getJSONArray(key); for (int i = 0; i < list.length(); i++) { JSONObject item = list.getJSONObject(i); if (allowsNearbyEnvelope(item) && wanted.remove(item.getString("id"))) packets.put(item); } } result.put("envelopes", packets);
            } else if (action.equals("accept")) result = acceptNearby(args.getJSONObject("envelope"));
            else if (action.equals("ack")) { JSONArray messages = data.getJSONArray("messages"); for (int i = 0; i < messages.length(); i++) if (messages.getJSONObject(i).getString("id").equals(args.getString("id"))) messages.getJSONObject(i).put("nearby_delivered", true); store.save(data); publishView(); }
            else if (action.equals("poll")) poll();
            else if (action.equals("updated")) publishView();
            else throw new Exception("Unknown nearby action");
            future.complete(new JSONObject(result.toString()));
        } catch (Exception error) { future.completeExceptionally(error); } }); } catch (Exception error) { future.completeExceptionally(error); }
        return future;
    }
    private static JSONObject acceptNearby(JSONObject envelope) throws Exception {
        String id = envelope.getString("id");
        if(!ChatPolicy.allows(envelope,!publicChatEnabled)) return new JSONObject().put("id",id).put("new",false);
        if (ChatHistory.wasCleared(data, id, System.currentTimeMillis())) return new JSONObject().put("id", id).put("new", false);
        for (String key : new String[]{"envelopes", "messages", "observations"}) { JSONArray list = data.getJSONArray(key); for (int i = 0; i < list.length(); i++) if (list.getJSONObject(i).getString("id").equals(id)) return new JSONObject().put("id", id).put("new", false); }
        JSONObject destination = envelope.getJSONObject("data").getJSONObject("destination"); boolean direct = destination.getString("type").equals("direct");
        if (!direct && (!destination.getString("type").equals("group") || !destination.getString("room").equals("parano1d"))) throw new Exception("Unknown nearby channel");
        boolean own = direct && destination.getJSONObject("contact").getString("id").equals(data.getJSONObject("profile").getString("id"));
        JSONObject opened = core(own || !direct ? "open" : "verify_envelope", new JSONObject().put("envelope", envelope));
        if (own) {
            // Audit 177: the same inbound gate as the online inbox, before anything is saved.
            JSONObject from = opened.getJSONObject("sender"); String route = ChatInbox.decide(data, from.getString("id"));
            if (route.equals(ChatInbox.DROP) || route.equals(ChatInbox.REQUEST) && opened.getJSONObject("message").optString("type").equals("relay_update")) {
                ChatInbox.tombstone(data, id, System.currentTimeMillis()); store.save(data); return new JSONObject().put("id", id).put("new", false);
            }
            if (route.equals(ChatInbox.REQUEST)) {
                JSONObject request = new JSONObject().put("id", id).put("chat", "d:" + from.getString("id")).put("sender", from).put("message", opened.getJSONObject("message")).put("created_ms", envelope.getJSONObject("data").getLong("created_ms")).put("status", "request").put("via_nearby", true);
                boolean added = ChatInbox.addRequest(requests(), request); if (added) saveRequests();
                return new JSONObject().put("id", id).put("new", added);
            }
        }
        if (own || !direct) {
            JSONObject sender = opened.getJSONObject("sender"); if (own) { addContact(sender); ChatInbox.accept(data, sender.getString("id")); }
            JSONObject message = opened.getJSONObject("message");
            JSONObject local = new JSONObject().put("id", id).put("chat", own ? "d:" + sender.getString("id") : "g:parano1d").put("sender", sender).put("message", message).put("created_ms", envelope.getJSONObject("data").getLong("created_ms")).put("status", "received").put("via_nearby", true);
            if (message.getString("type").equals("relay_update")) { reserveStorage(Math.multiplyExact(4,envelope.toString().getBytes(StandardCharsets.UTF_8).length)); recordObservation(local); }
            else { if (message.getString("type").equals("transaction")) local.put("payment", core("broadcast_preview", new JSONObject().put("intent_hex", message.getString("intent_hex")))); reserveStorage(Math.multiplyExact(4,envelope.toString().getBytes(StandardCharsets.UTF_8).length)); data.getJSONArray("messages").put(local); notifyMessage(local); }
        } else reserveStorage(Math.multiplyExact(4,envelope.toString().getBytes(StandardCharsets.UTF_8).length));
        retainEnvelope(envelope); relayEnvelope(envelope); rolloverHistory(); store.save(data); queueDelivery(); publishView();
        return new JSONObject().put("id", id).put("new", true);
    }
    @Override protected void onDestroy() { nodeTimer.removeCallbacks(nodeTick);try{unregisterReceiver(screenOff);}catch(Exception ignored){}if (appLock != null) appLock.destroy(); if (active.get() == this) {pauseNode();active.clear();} web.removeJavascriptInterface("Paramo1d"); web.destroy(); super.onDestroy(); }
    private void deliverScan(JSONObject result) {
        String script = "window.paramo1dScanResult(" + result.toString() + ")";
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed() && pageReady) web.evaluateJavascript(script, null); });
    }
    private void applyCapturePolicy() {
        boolean allow = getSharedPreferences("interface", MODE_PRIVATE).getBoolean("allow_screenshots", false);
        if (allow || BuildConfig.DEBUG && appLock != null && !appLock.needsUnlock()) getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void applySystemTheme(String selected) {
        boolean world = selected.equals("world"), cyber = selected.equals("cyber"), dark = world || cyber || selected.equals("dark") || selected.equals("system") && (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int paper=world?Color.BLACK:cyber?Color.rgb(6,15,12):dark?Color.rgb(2,7,6):Color.rgb(244,247,245);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(paper));
        getWindow().getDecorView().setBackgroundColor(paper);
        if(root!=null) root.setBackgroundColor(paper);
        if(launchScreen!=null) launchScreen.setBackgroundColor(paper);
        if(web!=null) web.setBackgroundColor(paper);
        getWindow().setStatusBarColor(paper);
        getWindow().setNavigationBarColor(paper);
        if(android.os.Build.VERSION.SDK_INT>=29) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        if(android.os.Build.VERSION.SDK_INT>=30) {
            android.view.WindowInsetsController controller=getWindow().getInsetsController();
            if(controller!=null) {
                int appearance=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark?0:appearance,appearance);
            }
        } else {
            int mask=android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            int flags=getWindow().getDecorView().getSystemUiVisibility();
            getWindow().getDecorView().setSystemUiVisibility((flags & ~mask) | (dark?0:mask));
        }
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applySystemTheme(getSharedPreferences("interface",MODE_PRIVATE).getString("theme","system"));
        if(root!=null) root.requestApplyInsets();
    }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level!=android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            try { NativeCore.call(new JSONObject().put("op","node_trim_memory").toString()); } catch(Throwable ignored) {}
        }
    }
    // Android 13+ (and always at targetSdk 36): back arrives through OnBackInvokedDispatcher, not onBackPressed().
    // Order is unchanged: open sheet/modal -> chat -> previous tab -> leave the app (moved to back, not closed).
    private android.window.OnBackInvokedCallback backCallback;
    private void registerBackHandler() {
        if (android.os.Build.VERSION.SDK_INT < 33 || backCallback != null) return;
        backCallback = this::handleBack;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
    }
    private void handleBack() {
        if (appLock.needsUnlock() || web == null || !pageReady) { moveTaskToBack(true); return; }
        web.evaluateJavascript("window.paramo1dBack ? window.paramo1dBack() : false", handled -> {
            if (!"true".equals(handled)) moveTaskToBack(true);
        });
    }
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { handleBack(); }

    // ---- Wear OS companion hooks (WatchBridge) ----
    static void onWorker(Runnable work) {
        try { chatWorker.execute(work); }
        catch (java.util.concurrent.RejectedExecutionException full) {
            new Thread(() -> { try { ((ThreadPoolExecutor) chatWorker).getQueue().put(work); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } }).start();
        }
    }
    static synchronized void ensureInitialized(android.content.Context context) {
        if (application == null) application = context.getApplicationContext();
        if (store == null) store = new SecureStore(application);
    }
    /** Live wallet state. Call only on the state worker (onWorker). */
    static JSONObject watchData() throws Exception {
        if (data == null) {
            if (store == null) throw new Exception("Open Parano1d on your phone once.");
            data = store.read(); initializeFields();
        }
        return data;
    }
    /** Runs a node/wallet action for the watch. Returns false when no phone activity is available. */
    static boolean watchChainCall(String id, JSONObject request) throws Exception {
        MainActivity activity = active.get();
        if (activity == null || activity.isDestroyed() || activity.isFinishing()) return false;
        activity.startChainAction(id, request);
        return true;
    }
    static void watchSendMessage(String chat, JSONObject message) throws Exception {
        sendMessage(chat, message); flushOutbox(); store.save(data); publishView();
    }
    static boolean communityPaused() { return !publicChatEnabled; }

    // ---- Backup chain endpoints (iOS 161 parity). Endpoint mode only: Paranoid mode talks to peers. ----
    private static final java.util.regex.Pattern ENDPOINT_FAILURE = java.util.regex.Pattern.compile("(?i)(time[d ]?out|connect|resolve|unreachable|network|endpoint|http|status [45]|refused|reset|tls|ssl|eof|unavailable|bad gateway|too many requests)");
    static final String BUILT_IN_RPC = "https://chat.parano1d.org/chain";
    static JSONObject withEndpoints(JSONObject settings, JSONObject args) throws Exception {
        if (args.optBoolean("paranoid_mode")) return nativeResult(args);
        java.util.ArrayList<String> endpoints = new java.util.ArrayList<>();
        String primary = settings.optString("rpc"); if (!primary.isEmpty()) endpoints.add(primary);
        JSONArray backups = settings.optJSONArray("rpc_backups");
        if (backups != null) for (int i = 0; i < backups.length() && i < 3; i++) { String endpoint = backups.optString(i); if (!endpoint.isEmpty() && !endpoints.contains(endpoint)) endpoints.add(endpoint); }
        // Your own node first, then your backups, and the built-in endpoint always last, so the wallet keeps
        // working if your node is down. Each endpoint only serves data this phone verifies; none can sign.
        if (!endpoints.contains(BUILT_IN_RPC)) endpoints.add(BUILT_IN_RPC);
        // Sending a payment (iOS design): every endpoint in order in one call, then, if none accepts it, the same
        // signed bytes go directly to mainnet peers (paramo1d broadcast_send with rpc_endpoints and artifacts).
        // Balance and other reads only fail over between endpoints, as on iOS.
        if ("broadcast_send".equals(args.optString("op"))) {
            JSONArray list = new JSONArray(); for (String endpoint : endpoints) list.put(endpoint);
            return nativeResult(new JSONObject(args.toString()).put("rpc", endpoints.get(0)).put("rpc_endpoints", list));
        }
        Exception last = null;
        for (String endpoint : endpoints) {
            try { return nativeResult(new JSONObject(args.toString()).put("rpc", endpoint)); }
            catch (Exception error) {
                last = error;
                String message = error.getMessage() == null ? "" : error.getMessage();
                if (!ENDPOINT_FAILURE.matcher(message).find()) throw error; // a wallet or consensus answer, not an unreachable endpoint
                android.util.Log.i("Parano1dNode", "Chain endpoint failed; trying the next one");
            }
        }
        // iOS 175/176: if no node answers, balance and payment preparation fall back to mainnet peers (the Peers
        // route), which this phone verifies the same way. Sending already falls back inside broadcast_send.
        try {
            JSONObject viaPeers = new JSONObject(args.toString()).put("paranoid_mode", true);
            viaPeers.remove("rpc");
            JSONObject result = nativeResult(viaPeers);
            android.util.Log.i("Parano1dNode", "No chain endpoint answered; answered by mainnet peers");
            return result;
        } catch (Exception peersFailed) { throw last; }
    }
}
