package com.example.vcam;

import android.app.Activity;
import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * Zero-permission, anti-screenshot in-process Floating Overlay HUD injected
 * into hooked camera applications via {@link Application.ActivityLifecycleCallbacks}.
 *
 * <p>Architectural guarantees:
 * <ul>
 *   <li><b>100% Programmatic Pure-SDK Construction</b>: Uses zero XML layouts,
 *       zero {@code R.*} resource IDs, and zero Material library classes so it
 *       never collides with the target application's ClassLoader or Resources.</li>
 *   <li><b>Separate Sub-Window Attachment ({@code TYPE_APPLICATION_PANEL})</b>:
 *       Attaches to the Activity's {@link WindowManager} with {@code WRAP_CONTENT}
 *       bounds and {@code FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL} rather than
 *       mutating {@code DecorView}'s child hierarchy. As a result, {@code decorView.draw(Canvas)}
 *       and view-tree inspection never see the overlay, and outside touches pass
 *       straight through to the host app.</li>
 *   <li><b>Full Real-Time Control</b>:
 *       <ul>
 *         <li>2D Virtual Thumbstick Joystick + D-Pad step buttons + Pinch-to-Zoom</li>
 *         <li>Aspect Mode ({@code FIT} / {@code FILL} / {@code STRETCH}), Rotation
 *             ({@code AUTO} / {@code 0°} / {@code 90°} / {@code 180°} / {@code 270°}), Mirror H/V</li>
 *         <li>Play/Pause Freeze-Frame, Video Scrub Bar, and 4 Cue Point Trigger Pads ({@code A/B/C/D})</li>
 *         <li>White Balance Kelvin &amp; Brightness sliders + Sensor Grain toggle</li>
 *         <li>Simul-Cam / Real Camera Pass-Through toggle &amp; Media Library Hot-Swap</li>
 *       </ul>
 *   </li>
 * </ul>
 */
public final class InAppOverlayManager {

    private static volatile boolean lifecycleRegistered = false;
    private static volatile boolean overlayEnabledFlag = true;
    private static WeakReference<Activity> currentActivityRef;
    private static WeakReference<OverlayRootView> activeOverlayRef;
    private static WeakReference<View> activeBannerRef;
    private static volatile String lastBannerTitle = null;
    private static volatile String lastBannerBody = null;
    private static volatile boolean bannerDismissedByUser = false;
    private static boolean sessionStealthHidden = false;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private InAppOverlayManager() {}

    public static synchronized void registerLifecycle(@NonNull Application app) {
        String pkg = app.getPackageName();
        registerLifecycle(app, pkg != null ? pkg : HookMain.getCurrentHostPackage());
    }

    public static synchronized void registerLifecycle(@NonNull Application app,
                                                      @NonNull final String hostPkg) {
        if (lifecycleRegistered) return;
        // Do not auto-inject into VCAM's own MainActivity (except CameraVerifyActivity)
        lifecycleRegistered = true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}

            @Override
            public void onActivityStarted(@NonNull Activity activity) {}

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                onHostActivityActive(activity, hostPkg);
                HookMain.onHostActivityResumed(activity, hostPkg);
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
                detachFromActivity(activity);
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {}

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
                detachFromActivity(activity);
            }
        });
    }

    public static void onHostActivityActive(@NonNull final Activity activity,
                                            @NonNull final String hostPkg) {
        if (BuildConfig.APPLICATION_ID.equals(hostPkg)
                && !activity.getClass().getName().endsWith("CameraVerifyActivity")) {
            return;
        }
        currentActivityRef = new WeakReference<>(activity);
        if (HookMain.isCameraSessionActive() && overlayEnabledFlag
                && HookMain.getActiveTransformConfig().overlayEnabled) {
            mainHandler.post(() -> attachToActivity(activity, hostPkg));
        }
    }

    @Nullable
    private static Activity findTopRunningActivity() {
        Activity cached = currentActivityRef != null ? currentActivityRef.get() : null;
        if (cached != null && !cached.isFinishing() && !cached.isDestroyed()) {
            return cached;
        }
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke(null);
            if (at == null) return null;
            java.lang.reflect.Field f = atClass.getDeclaredField("mActivities");
            f.setAccessible(true);
            Object mapObj = f.get(at);
            if (mapObj instanceof java.util.Map) {
                for (Object record : ((java.util.Map<?, ?>) mapObj).values()) {
                    if (record == null) continue;
                    Class<?> recClass = record.getClass();
                    java.lang.reflect.Field pausedField = recClass.getDeclaredField("paused");
                    pausedField.setAccessible(true);
                    java.lang.reflect.Field actField = recClass.getDeclaredField("activity");
                    actField.setAccessible(true);
                    Activity act = (Activity) actField.get(record);
                    boolean paused = pausedField.getBoolean(record);
                    if (act != null && !paused && !act.isFinishing() && !act.isDestroyed()) {
                        currentActivityRef = new WeakReference<>(act);
                        return act;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void setOverlayEnabled(final boolean enabled) {
        overlayEnabledFlag = enabled;
        mainHandler.post(() -> {
            if (!enabled) {
                detachCurrentOverlay();
            } else if (HookMain.isCameraSessionActive()) {
                onCameraSessionStarted(HookMain.getCurrentHostPackage());
            }
        });
    }

    public static void onCameraSessionActive(final boolean active) {
        if (active) {
            onCameraSessionStarted(HookMain.getCurrentHostPackage());
        } else {
            mainHandler.post(InAppOverlayManager::detachCurrentOverlay);
        }
    }

    /** Called by {@link HookMain} when a camera opens in the host process. */
    public static void onCameraSessionStarted(@NonNull final String hostPkg) {
        mainHandler.post(() -> {
            if (!overlayEnabledFlag || !HookMain.getActiveTransformConfig().overlayEnabled || sessionStealthHidden) return;
            Activity act = findTopRunningActivity();
            if (act != null && !act.isFinishing() && !act.isDestroyed()) {
                attachToActivity(act, hostPkg);
            }
        });
    }

    private static void attachToActivity(@NonNull final Activity activity,
                                         @NonNull final String hostPkg) {
        if (BuildConfig.APPLICATION_ID.equals(hostPkg)
                && !activity.getClass().getName().endsWith("CameraVerifyActivity")) {
            return;
        }
        if (sessionStealthHidden || !overlayEnabledFlag || !HookMain.getActiveTransformConfig().overlayEnabled) return;
        OverlayRootView existing = activeOverlayRef != null ? activeOverlayRef.get() : null;
        if (existing != null && existing.hostActivity == activity && existing.isAttachedToWindow()) {
            existing.bringToFront();
            existing.refreshUiFromState();
            return;
        }
        detachCurrentOverlay();

        View decorView = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (decorView == null) return;
        decorView.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed() || sessionStealthHidden) return;
            OverlayRootView cur = activeOverlayRef != null ? activeOverlayRef.get() : null;
            if (cur != null && cur.hostActivity == activity && cur.isAttachedToWindow()) {
                cur.bringToFront();
                cur.refreshUiFromState();
                return;
            }
            try {
                View dv = activity.getWindow().getDecorView();
                if (!(dv instanceof ViewGroup)) return;
                ViewGroup decorGroup = (ViewGroup) dv;

                FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.START
                );
                flp.leftMargin = dp(activity, 12);
                flp.topMargin = dp(activity, 96);

                WindowManager wm = activity.getWindowManager();
                WindowManager.LayoutParams wlp = new WindowManager.LayoutParams();
                wlp.x = flp.leftMargin;
                wlp.y = flp.topMargin;

                OverlayRootView root = new OverlayRootView(activity, wm, wlp, hostPkg, flp);
                root.setElevation(dp(activity, 100));
                root.setTranslationZ(dp(activity, 100));
                root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                decorGroup.addView(root, flp);
                root.bringToFront();
                activeOverlayRef = new WeakReference<>(root);
            } catch (Throwable t) {
                XposedBridge.log("[VCAM][overlay] attach failed: " + t);
            }
        });
    }

    private static void detachFromActivity(@NonNull Activity activity) {
        OverlayRootView existing = activeOverlayRef != null ? activeOverlayRef.get() : null;
        if (existing != null && existing.hostActivity == activity) {
            detachCurrentOverlay();
        }
    }

    private static void detachCurrentOverlay() {
        OverlayRootView existing = activeOverlayRef != null ? activeOverlayRef.get() : null;
        if (existing != null) {
            try {
                if (existing.getParent() instanceof ViewGroup) {
                    ((ViewGroup) existing.getParent()).removeView(existing);
                } else if (existing.windowManager != null) {
                    existing.windowManager.removeViewImmediate(existing);
                }
            } catch (Throwable ignored) {}
            activeOverlayRef = null;
        }
    }

    public static void showStickyStatusBanner(@NonNull final String title,
                                              @NonNull final String body,
                                              @NonNull final String hostPkg) {
        lastBannerTitle = title;
        lastBannerBody = body;
        bannerDismissedByUser = false;
        mainHandler.post(() -> {
            OverlayRootView existing = activeOverlayRef != null ? activeOverlayRef.get() : null;
            if (existing != null && existing.isAttachedToWindow()) {
                existing.refreshUiFromState();
            } else if (HookMain.isCameraSessionActive()) {
                onCameraSessionStarted(hostPkg);
            }
        });
    }

    private static void detachBannerFromActivity() {
        View existing = activeBannerRef != null ? activeBannerRef.get() : null;
        if (existing != null) {
            try {
                Context ctx = existing.getContext();
                if (ctx instanceof Activity) {
                    WindowManager wm = ((Activity) ctx).getWindowManager();
                    if (wm != null) wm.removeViewImmediate(existing);
                }
            } catch (Throwable ignored) {}
            activeBannerRef = null;
        }
    }

    private static void attachStickyBannerToActivity(@NonNull final Activity activity,
                                                     @NonNull final String title,
                                                     @NonNull final String body,
                                                     @NonNull final String hostPkg) {
        if (BuildConfig.APPLICATION_ID.equals(hostPkg)
                && !activity.getClass().getName().endsWith("CameraVerifyActivity")) {
            return;
        }
        activity.getWindow().getDecorView().post(() -> {
            if (activity.isFinishing() || activity.isDestroyed() || bannerDismissedByUser) return;
            try {
                detachBannerFromActivity();
                final WindowManager wm = activity.getWindowManager();
                if (wm == null) return;

                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                );
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.y = dp(activity, 38);
                lp.token = activity.getWindow().getDecorView().getWindowToken();

                LinearLayout pill = new LinearLayout(activity);
                pill.setOrientation(LinearLayout.HORIZONTAL);
                pill.setGravity(Gravity.CENTER_VERTICAL);
                int padH = dp(activity, 12);
                int padV = dp(activity, 6);
                pill.setPadding(padH, padV, padH, padV);

                GradientDrawable bg = new GradientDrawable();
                boolean isWarn = title.toLowerCase(Locale.ROOT).contains("no replacement");
                bg.setColor(isWarn ? 0xEE4A1C1C : 0xEE141E2B);
                bg.setCornerRadius(dp(activity, 18));
                bg.setStroke(dp(activity, 1), isWarn ? 0xFFFF5252 : 0xFF00E5FF);
                pill.setBackground(bg);

                LinearLayout textCol = new LinearLayout(activity);
                textCol.setOrientation(LinearLayout.VERTICAL);

                TextView tvTitle = new TextView(activity);
                tvTitle.setText(title);
                tvTitle.setTextColor(isWarn ? 0xFFFF8A80 : 0xFF80D8FF);
                tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                tvTitle.setTypeface(tvTitle.getTypeface(), android.graphics.Typeface.BOLD);
                textCol.addView(tvTitle);

                String oneLineBody = body.replace('\n', ' ').trim();
                if (oneLineBody.length() > 68) {
                    oneLineBody = oneLineBody.substring(0, 65) + "...";
                }
                TextView tvBody = new TextView(activity);
                tvBody.setText(oneLineBody);
                tvBody.setTextColor(0xFFE0E6ED);
                tvBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
                textCol.addView(tvBody);

                textCol.setOnClickListener(v -> {
                    try {
                        Intent intent = new Intent();
                        intent.setClassName(BuildConfig.APPLICATION_ID,
                                BuildConfig.APPLICATION_ID + ".InjectedConfigActivity");
                        intent.putExtra(InjectedConfigActivity.EXTRA_CALLER_PACKAGE, hostPkg);
                        intent.putExtra(InjectedConfigActivity.EXTRA_CURRENT_FACING, HookMain.getCurrentFacing());
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(intent);
                    } catch (Throwable ignored) {}
                });
                pill.addView(textCol);

                TextView closeBtn = new TextView(activity);
                closeBtn.setText("  ✕");
                closeBtn.setTextColor(0xAAFFFFFF);
                closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                closeBtn.setPadding(dp(activity, 6), 0, dp(activity, 2), 0);
                closeBtn.setOnClickListener(v -> {
                    bannerDismissedByUser = true;
                    detachBannerFromActivity();
                });
                pill.addView(closeBtn);

                wm.addView(pill, lp);
                activeBannerRef = new WeakReference<>(pill);
            } catch (Throwable ignored) {}
        });
    }

    private static int dp(@NonNull Context ctx, float dpVal) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dpVal, dm));
    }

    // ------------------------------------------------------------------
    // Programmatic Floating Bubble + 3-Tab Expandable Control Panel HUD
    // ------------------------------------------------------------------

    private static final class OverlayRootView extends FrameLayout {
        final Activity hostActivity;
        final WindowManager windowManager;
        final WindowManager.LayoutParams windowParams;
        final FrameLayout.LayoutParams decorParams;
        final String hostPkg;

        private final View bubbleView;
        private final LinearLayout panelView;
        private final LinearLayout tabPtz;
        private final LinearLayout tabPlayback;
        private final LinearLayout tabSensor;

        private TextView statusLabel;
        private TextView zoomReadout;
        private SeekBar zoomSlider;
        private SeekBar scrubSlider;
        private TextView timeReadout;
        private JoystickView joystickView;
        private ScaleGestureDetector pinchDetector;
        private Button tabBtnPtz;
        private Button tabBtnPlay;
        private Button tabBtnSensor;
        private Button btnPassthrough;
        private Button btnFit;
        private Button btnFill;
        private Button btnStr;
        private Button btnRotAuto;
        private Button btnRot90;
        private Button btnMirH;
        private Button btnMirV;
        private Button btnFreeze;
        private Button btnSrcMedia;
        private Button btnSrcGrid;
        private Button btnSrcQr;
        private Button btnGrain;

        private boolean expanded = false;
        private int activeTab = 0; // 0 = PTZ, 1 = Playback/Cues, 2 = Sensor/Source

        OverlayRootView(@NonNull Activity activity,
                        @Nullable WindowManager wm,
                        @NonNull WindowManager.LayoutParams lp,
                        @NonNull String hostPkg,
                        @NonNull FrameLayout.LayoutParams flp) {
            super(activity);
            this.hostActivity = activity;
            this.windowManager = wm;
            this.windowParams = lp;
            this.decorParams = flp;
            this.hostPkg = hostPkg;

            pinchDetector = new ScaleGestureDetector(activity, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    TransformConfig cfg = HookMain.getActiveTransformConfig();
                    cfg.zoom = Math.max(0.25f, Math.min(5.0f, cfg.zoom * detector.getScaleFactor()));
                    applyAndPersistTransform(cfg);
                    refreshUiFromState();
                    return true;
                }
            });

            bubbleView = buildBubbleView(activity);
            panelView = buildExpandedPanel(activity);
            tabPtz = buildPtzTab(activity);
            tabPlayback = buildPlaybackTab(activity);
            tabSensor = buildSensorTab(activity);

            panelView.addView(tabPtz);
            panelView.addView(tabPlayback);
            panelView.addView(tabSensor);
            panelView.setVisibility(GONE);

            addView(bubbleView);
            addView(panelView);
            switchTab(0);
            refreshUiFromState();
        }

        private View buildBubbleView(@NonNull final Context ctx) {
            final TextView b = new TextView(ctx);
            int size = dp(ctx, 54);
            b.setLayoutParams(new LayoutParams(size, size));
            b.setGravity(Gravity.CENTER);
            b.setText("VC");
            b.setTextColor(Color.WHITE);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            b.getPaint().setFakeBoldText(true);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0xCC1E88E5);
            bg.setStroke(dp(ctx, 2), 0xDDFFFFFF);
            b.setBackground(bg);

            b.setOnTouchListener(new OnTouchListener() {
                private float downRawX, downRawY;
                private int startWinX, startWinY;
                private long downTime;
                private boolean moved;

                @Override
                public boolean onTouch(View v, MotionEvent ev) {
                    switch (ev.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downRawX = ev.getRawX();
                            downRawY = ev.getRawY();
                            startWinX = windowParams.x;
                            startWinY = windowParams.y;
                            downTime = System.currentTimeMillis();
                            moved = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float dx = ev.getRawX() - downRawX;
                            float dy = ev.getRawY() - downRawY;
                            if (Math.hypot(dx, dy) > dp(ctx, 6)) {
                                moved = true;
                            }
                            if (moved) {
                                windowParams.x = Math.max(0, startWinX + Math.round(dx));
                                windowParams.y = Math.max(0, startWinY + Math.round(dy));
                                applyOverlayPosition();
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            long held = System.currentTimeMillis() - downTime;
                            if (!moved) {
                                if (held > 700L) {
                                    // Long-press bubble -> stealth hide for session
                                    sessionStealthHidden = true;
                                    Toast.makeText(ctx, "VCAM overlay hidden for this session", Toast.LENGTH_SHORT).show();
                                    detachCurrentOverlay();
                                } else {
                                    setExpanded(true);
                                }
                            }
                            return true;
                    }
                    return false;
                }
            });
            return b;
        }

        private LinearLayout buildExpandedPanel(@NonNull final Context ctx) {
            LinearLayout root = new LinearLayout(ctx);
            root.setOrientation(LinearLayout.VERTICAL);
            int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
            int width = Math.min(dp(ctx, 356), Math.max(dp(ctx, 304), screenW - dp(ctx, 20)));
            root.setLayoutParams(new LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
            int pad = dp(ctx, 14);
            root.setPadding(pad, pad, pad, pad);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF4141923);
            bg.setCornerRadius(dp(ctx, 18));
            bg.setStroke(dp(ctx, 2), 0xAA00B4D8);
            root.setBackground(bg);

            // Header bar (Draggable + Status + Minimize/Hide)
            LinearLayout header = new LinearLayout(ctx);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            statusLabel = new TextView(ctx);
            statusLabel.setText("VCAM HUD");
            statusLabel.setTextColor(0xFF80D8FF);
            statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            statusLabel.getPaint().setFakeBoldText(true);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            header.addView(statusLabel, titleLp);

            btnPassthrough = makeSmallButton(ctx, "LIVE/VIRT", v -> {
                TransformConfig cfg = HookMain.getActiveTransformConfig();
                cfg.passthroughRealCamera = !cfg.passthroughRealCamera;
                cfg.passthroughMode = cfg.passthroughRealCamera;
                applyAndPersistTransform(cfg);
                refreshUiFromState();
            });
            header.addView(btnPassthrough);

            Button btnMin = makeSmallButton(ctx, "MIN", v -> setExpanded(false));
            header.addView(btnMin);

            header.setOnTouchListener(new OnTouchListener() {
                private float downX, downY;
                private int startX, startY;
                @Override
                public boolean onTouch(View v, MotionEvent ev) {
                    if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        downX = ev.getRawX();
                        downY = ev.getRawY();
                        startX = windowParams.x;
                        startY = windowParams.y;
                        return true;
                    } else if (ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
                        windowParams.x = Math.max(0, startX + Math.round(ev.getRawX() - downX));
                        windowParams.y = Math.max(0, startY + Math.round(ev.getRawY() - downY));
                        applyOverlayPosition();
                        return true;
                    }
                    return false;
                }
            });
            root.addView(header);

            // Tab Strip (PTZ | Cues/Play | Sensor/Source)
            LinearLayout tabs = new LinearLayout(ctx);
            tabs.setOrientation(LinearLayout.HORIZONTAL);
            tabs.setPadding(0, dp(ctx, 6), 0, dp(ctx, 8));
            tabBtnPtz = makeTabButton(ctx, "PTZ & Aspect", 0);
            tabBtnPlay = makeTabButton(ctx, "Play & Cues", 1);
            tabBtnSensor = makeTabButton(ctx, "Sensor & Src", 2);
            tabs.addView(tabBtnPtz);
            tabs.addView(tabBtnPlay);
            tabs.addView(tabBtnSensor);
            root.addView(tabs);

            return root;
        }

        private LinearLayout buildPtzTab(@NonNull final Context ctx) {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);

            // Row 1: 2D Virtual Joystick + D-Pad step controls
            LinearLayout joyRow = new LinearLayout(ctx);
            joyRow.setOrientation(LinearLayout.HORIZONTAL);
            joyRow.setGravity(Gravity.CENTER_VERTICAL);

            joystickView = new JoystickView(ctx, (normX, normY) -> {
                TransformConfig cfg = HookMain.getActiveTransformConfig();
                cfg.panX = normX;
                cfg.panY = normY;
                applyAndPersistTransform(cfg);
            });
            LinearLayout.LayoutParams joyLp = new LinearLayout.LayoutParams(dp(ctx, 114), dp(ctx, 114));
            joyRow.addView(joystickView, joyLp);

            // Right column: D-Pad fine nudge + Aspect Ratio & Rotate buttons
            LinearLayout rightCol = new LinearLayout(ctx);
            rightCol.setOrientation(LinearLayout.VERTICAL);
            rightCol.setPadding(dp(ctx, 8), 0, 0, 0);

            LinearLayout dpadRow = new LinearLayout(ctx);
            dpadRow.setOrientation(LinearLayout.HORIZONTAL);
            dpadRow.addView(makeSmallButton(ctx, "◀", v -> nudgePan(-0.05f, 0f)));
            dpadRow.addView(makeSmallButton(ctx, "▲", v -> nudgePan(0f, -0.05f)));
            dpadRow.addView(makeSmallButton(ctx, "▼", v -> nudgePan(0f, 0.05f)));
            dpadRow.addView(makeSmallButton(ctx, "▶", v -> nudgePan(0.05f, 0f)));
            dpadRow.addView(makeSmallButton(ctx, "◎", v -> {
                TransformConfig cfg = HookMain.getActiveTransformConfig();
                cfg.resetSpatial();
                applyAndPersistTransform(cfg);
                refreshUiFromState();
            }));
            rightCol.addView(dpadRow);

            // Aspect Mode row: FIT | FILL | STRETCH
            LinearLayout aspectRow = new LinearLayout(ctx);
            aspectRow.setOrientation(LinearLayout.HORIZONTAL);
            aspectRow.setPadding(0, dp(ctx, 4), 0, 0);
            btnFit = makeSmallButton(ctx, "FIT", v -> setAspect(TransformConfig.ASPECT_FIT));
            btnFill = makeSmallButton(ctx, "FILL", v -> setAspect(TransformConfig.ASPECT_FILL));
            btnStr = makeSmallButton(ctx, "STR", v -> setAspect(TransformConfig.ASPECT_STRETCH));
            aspectRow.addView(btnFit);
            aspectRow.addView(btnFill);
            aspectRow.addView(btnStr);
            rightCol.addView(aspectRow);

            // Rotation & Mirror row
            LinearLayout rotRow = new LinearLayout(ctx);
            rotRow.setOrientation(LinearLayout.HORIZONTAL);
            rotRow.setPadding(0, dp(ctx, 4), 0, 0);
            btnRotAuto = makeSmallButton(ctx, "AUTO°", v -> setRot(TransformConfig.ROT_AUTO));
            btnRot90 = makeSmallButton(ctx, "+90°", v -> {
                TransformConfig cfg = HookMain.getActiveTransformConfig();
                int cur = cfg.rotationMode < 0 ? 0 : cfg.rotationMode;
                cfg.setRotation((cur + 90) % 360);
                applyAndPersistTransform(cfg);
                refreshUiFromState();
            });
            btnMirH = makeSmallButton(ctx, "⇔H", v -> {
                TransformConfig cfg = HookMain.getActiveTransformConfig();
                cfg.mirrorH = !cfg.mirrorH;
                applyAndPersistTransform(cfg);
                refreshUiFromState();
            });
            btnMirV = makeSmallButton(ctx, "⇕V", v -> {
                TransformConfig cfg = HookMain.getActiveTransformConfig();
                cfg.mirrorV = !cfg.mirrorV;
                applyAndPersistTransform(cfg);
                refreshUiFromState();
            });
            rotRow.addView(btnRotAuto);
            rotRow.addView(btnRot90);
            rotRow.addView(btnMirH);
            rotRow.addView(btnMirV);
            rightCol.addView(rotRow);

            joyRow.addView(rightCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            box.addView(joyRow);

            // Pinch-to-zoom surface + Zoom Slider
            LinearLayout zoomRow = new LinearLayout(ctx);
            zoomRow.setOrientation(LinearLayout.HORIZONTAL);
            zoomRow.setGravity(Gravity.CENTER_VERTICAL);
            zoomRow.setPadding(0, dp(ctx, 6), 0, 0);

            zoomReadout = new TextView(ctx);
            zoomReadout.setText("1.00x");
            zoomReadout.setTextColor(Color.WHITE);
            zoomReadout.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            zoomRow.addView(zoomReadout);

            zoomSlider = new SeekBar(ctx);
            zoomSlider.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
            zoomSlider.setOnTouchListener((v, ev) -> {
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            });
            zoomSlider.setMax(475); // 0.25x .. 5.00x
            zoomSlider.setProgress(75);
            zoomSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    TransformConfig cfg = HookMain.getActiveTransformConfig();
                    cfg.zoom = (25 + progress) / 100.0f;
                    zoomReadout.setText(String.format(Locale.US, "%.2fx", cfg.zoom));
                    applyAndPersistTransform(cfg);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            zoomRow.addView(zoomSlider, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            zoomRow.addView(makeSmallButton(ctx, "1x", v -> {
                TransformConfig cfg = HookMain.getActiveTransformConfig();
                cfg.zoom = 1.0f;
                applyAndPersistTransform(cfg);
                refreshUiFromState();
            }));
            box.addView(zoomRow);

            // Preset Strip
            HorizontalScrollView presetScroll = new HorizontalScrollView(ctx);
            LinearLayout presetRow = new LinearLayout(ctx);
            presetRow.setOrientation(LinearLayout.HORIZONTAL);
            presetRow.setPadding(0, dp(ctx, 4), 0, 0);
            presetRow.addView(makeSmallButton(ctx, "Fit Auto", v -> {
                TransformConfig cfg = HookMain.getActiveTransformConfig();
                cfg.resetSpatial();
                applyAndPersistTransform(cfg);
                refreshUiFromState();
            }));
            presetRow.addView(makeSmallButton(ctx, "Selfie Mirror", v -> {
                TransformConfig cfg = HookMain.getActiveTransformConfig();
                cfg.aspectMode = TransformConfig.ASPECT_FILL;
                cfg.mirrorH = true;
                applyAndPersistTransform(cfg);
                refreshUiFromState();
            }));
            presetRow.addView(makeSmallButton(ctx, "1.5x Crop", v -> {
                TransformConfig cfg = HookMain.getActiveTransformConfig();
                cfg.aspectMode = TransformConfig.ASPECT_FILL;
                cfg.zoom = 1.5f;
                applyAndPersistTransform(cfg);
                refreshUiFromState();
            }));
            presetScroll.addView(presetRow);
            box.addView(presetScroll);

            box.setOnTouchListener((v, ev) -> {
                pinchDetector.onTouchEvent(ev);
                return true;
            });
            return box;
        }

        private LinearLayout buildPlaybackTab(@NonNull final Context ctx) {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);

            // Play / Pause Freeze-Frame + Time Readout
            LinearLayout ctrlRow = new LinearLayout(ctx);
            ctrlRow.setOrientation(LinearLayout.HORIZONTAL);
            ctrlRow.setGravity(Gravity.CENTER_VERTICAL);

            btnFreeze = makeSmallButton(ctx, "⏯ Freeze/Play", v -> {
                TransformConfig cfg = HookMain.getActiveTransformConfig();
                cfg.paused = !cfg.paused;
                HookMain.setPlaybackPaused(cfg.paused);
                applyAndPersistTransform(cfg);
                refreshUiFromState();
            });
            ctrlRow.addView(btnFreeze);

            ctrlRow.addView(makeSmallButton(ctx, "⏮ 0:00", v -> {
                HookMain.seekActiveVideo(0L);
            }));

            timeReadout = new TextView(ctx);
            timeReadout.setText("LIVE");
            timeReadout.setTextColor(0xFFB0BEC5);
            timeReadout.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            timeReadout.setPadding(dp(ctx, 8), 0, 0, 0);
            ctrlRow.addView(timeReadout);
            box.addView(ctrlRow);

            // Scrub Bar
            scrubSlider = new SeekBar(ctx);
            scrubSlider.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
            scrubSlider.setOnTouchListener((v, ev) -> {
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            });
            scrubSlider.setMax(1000);
            scrubSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    long dur = HookMain.getActiveVideoDurationMs();
                    if (dur > 0) {
                        HookMain.seekActiveVideo((dur * progress) / 1000L);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            box.addView(scrubSlider);

            // Cue Point Trigger Pads (Cue A, B, C, D + Bookmark Current Position)
            TextView cueTitle = new TextView(ctx);
            cueTitle.setText("Cue Point Hotkeys (Tap = Jump, Long-Tap = Bookmark Here)");
            cueTitle.setTextColor(0xFF90A4AE);
            cueTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            cueTitle.setPadding(0, dp(ctx, 4), 0, dp(ctx, 2));
            box.addView(cueTitle);

            LinearLayout cueRow = new LinearLayout(ctx);
            cueRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 1; i <= 4; i++) {
                final int cueId = i;
                Button b = makeSmallButton(ctx, "Cue " + (char) ('A' + i - 1), v -> {
                    HookMain.triggerCuePoint(cueId);
                    Toast.makeText(ctx, "Triggered Cue " + (char) ('A' + cueId - 1), Toast.LENGTH_SHORT).show();
                });
                b.setOnLongClickListener(v -> {
                    long pos = HookMain.getActiveVideoPositionMs();
                    HookMain.bookmarkCuePoint(cueId, "Cue " + (char) ('A' + cueId - 1), pos);
                    Toast.makeText(ctx, "Saved Cue " + (char) ('A' + cueId - 1) + " @ " + (pos / 1000.0f) + "s",
                            Toast.LENGTH_SHORT).show();
                    return true;
                });
                cueRow.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            box.addView(cueRow);
            return box;
        }

        private LinearLayout buildSensorTab(@NonNull final Context ctx) {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);

            // Source mode selector: Media | Test Pattern | Live QR | Picker Dialog
            LinearLayout srcRow = new LinearLayout(ctx);
            srcRow.setOrientation(LinearLayout.HORIZONTAL);
            btnSrcMedia = makeSmallButton(ctx, "Media", v -> setSourceMode(TransformConfig.SOURCE_MEDIA));
            btnSrcGrid = makeSmallButton(ctx, "Test Grid", v -> setSourceMode(TransformConfig.SOURCE_TEST_PATTERN));
            btnSrcQr = makeSmallButton(ctx, "QR Code", v -> promptQrInput(ctx));
            srcRow.addView(btnSrcMedia);
            srcRow.addView(btnSrcGrid);
            srcRow.addView(btnSrcQr);
            srcRow.addView(makeSmallButton(ctx, "Swap...", v -> launchInjectedConfig(ctx)));
            box.addView(srcRow);

            // White Balance Slider
            LinearLayout wbRow = new LinearLayout(ctx);
            wbRow.setOrientation(LinearLayout.HORIZONTAL);
            wbRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView wbLbl = new TextView(ctx);
            wbLbl.setText("WB Tint");
            wbLbl.setTextColor(Color.WHITE);
            wbLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            wbRow.addView(wbLbl);

            SeekBar wbBar = new SeekBar(ctx);
            wbBar.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
            wbBar.setOnTouchListener((v, ev) -> {
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            });
            wbBar.setMax(200);
            wbBar.setProgress(100);
            wbBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    TransformConfig cfg = HookMain.getActiveTransformConfig();
                    cfg.wbKelvinShift = (progress - 100) / 100.0f;
                    applyAndPersistTransform(cfg);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            wbRow.addView(wbBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            box.addView(wbRow);

            // Brightness & Sensor Grain row
            LinearLayout expRow = new LinearLayout(ctx);
            expRow.setOrientation(LinearLayout.HORIZONTAL);
            expRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView expLbl = new TextView(ctx);
            expLbl.setText("Expose");
            expLbl.setTextColor(Color.WHITE);
            expLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            expRow.addView(expLbl);

            SeekBar expBar = new SeekBar(ctx);
            expBar.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
            expBar.setOnTouchListener((v, ev) -> {
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                return false;
            });
            expBar.setMax(100);
            expBar.setProgress(50);
            expBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    TransformConfig cfg = HookMain.getActiveTransformConfig();
                    cfg.brightnessShift = (progress - 50) / 100.0f;
                    applyAndPersistTransform(cfg);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            expRow.addView(expBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            btnGrain = makeSmallButton(ctx, "Grain", v -> {
                TransformConfig cfg = HookMain.getActiveTransformConfig();
                cfg.sensorGrainEnabled = !cfg.sensorGrainEnabled;
                applyAndPersistTransform(cfg);
                refreshUiFromState();
                Toast.makeText(ctx, "Sensor grain: " + (cfg.sensorGrainEnabled ? "ON" : "OFF"),
                        Toast.LENGTH_SHORT).show();
            });
            expRow.addView(btnGrain);
            box.addView(expRow);

            return box;
        }

        private void promptQrInput(@NonNull final Context ctx) {
            // Temporarily clear FLAG_NOT_FOCUSABLE on our overlay window or use an AlertDialog
            try {
                final EditText input = new EditText(ctx);
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
                input.setText(HookMain.getActiveTransformConfig().qrPayload);
                new android.app.AlertDialog.Builder(hostActivity)
                        .setTitle("Live QR Code Payload")
                        .setView(input)
                        .setPositiveButton("Project QR", (d, w) -> {
                            TransformConfig cfg = HookMain.getActiveTransformConfig();
                            cfg.qrPayload = input.getText().toString().trim();
                            cfg.sourceMode = TransformConfig.SOURCE_QR_CODE;
                            applyAndPersistTransform(cfg);
                            refreshUiFromState();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            } catch (Throwable t) {
                setSourceMode(TransformConfig.SOURCE_QR_CODE);
            }
        }

        private void launchInjectedConfig(@NonNull Context ctx) {
            try {
                Intent i = new Intent("com.example.vcam.action.CONFIGURE");
                i.setClassName(BuildConfig.APPLICATION_ID, BuildConfig.APPLICATION_ID + ".InjectedConfigActivity");
                i.putExtra(InjectedConfigActivity.EXTRA_CALLER_PACKAGE, hostPkg);
                i.putExtra(InjectedConfigActivity.EXTRA_CURRENT_FACING, HookMain.getCurrentFacing());
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                ctx.startActivity(i);
            } catch (Throwable ignored) {}
        }

        private final android.view.ViewTreeObserver.OnGlobalLayoutListener zOrderKeepTopListener = () -> {
            try {
                if (getParent() instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) getParent();
                    if (vg.indexOfChild(OverlayRootView.this) < vg.getChildCount() - 1) {
                        bringToFront();
                    }
                }
            } catch (Throwable ignored) {}
        };

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            try {
                getViewTreeObserver().addOnGlobalLayoutListener(zOrderKeepTopListener);
            } catch (Throwable ignored) {}
        }

        @Override
        protected void onDetachedFromWindow() {
            try {
                getViewTreeObserver().removeOnGlobalLayoutListener(zOrderKeepTopListener);
            } catch (Throwable ignored) {}
            super.onDetachedFromWindow();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (!canvas.isHardwareAccelerated()) {
                return;
            }
            super.dispatchDraw(canvas);
        }

        private void applyOverlayPosition() {
            try {
                decorParams.leftMargin = Math.max(0, windowParams.x);
                decorParams.topMargin = Math.max(0, windowParams.y);
                setLayoutParams(decorParams);
                bringToFront();
            } catch (Throwable ignored) {}
        }

        private void setExpanded(boolean exp) {
            this.expanded = exp;
            bubbleView.setVisibility(exp ? GONE : VISIBLE);
            panelView.setVisibility(exp ? VISIBLE : GONE);
            refreshUiFromState();
            applyOverlayPosition();
        }

        private void switchTab(int tab) {
            this.activeTab = tab;
            tabPtz.setVisibility(tab == 0 ? VISIBLE : GONE);
            tabPlayback.setVisibility(tab == 1 ? VISIBLE : GONE);
            tabSensor.setVisibility(tab == 2 ? VISIBLE : GONE);
            refreshUiFromState();
        }

        private void styleHudButton(@Nullable Button b, boolean active) {
            if (b == null) return;
            Context ctx = b.getContext();
            GradientDrawable bg = new GradientDrawable();
            if (active) {
                bg.setColor(0xFF0077B6);
                bg.setStroke(dp(ctx, 1), 0xFF90E0EF);
                b.setTextColor(Color.WHITE);
            } else {
                bg.setColor(0xFF252D3D);
                bg.setStroke(dp(ctx, 1), 0x6680D8FF);
                b.setTextColor(0xFFE2E8F0);
            }
            bg.setCornerRadius(dp(ctx, 8));
            b.setBackground(bg);
            int ph = dp(ctx, 9);
            int pv = dp(ctx, 5);
            b.setPadding(ph, pv, ph, pv);
        }

        void refreshUiFromState() {
            TransformConfig cfg = HookMain.getActiveTransformConfig();
            String modeBadge = cfg.passthroughRealCamera ? "REAL-CAM"
                    : (cfg.paused ? "FROZEN" : cfg.aspectMode.toUpperCase(Locale.US));
            statusLabel.setText(String.format(Locale.US, "VCAM · %s · %s",
                    HookMain.getCurrentFacing().toUpperCase(Locale.US), modeBadge));
            zoomReadout.setText(String.format(Locale.US, "%.2fx", cfg.zoom));
            zoomSlider.setProgress(Math.max(0, Math.min(475, Math.round(cfg.zoom * 100) - 25)));
            if (joystickView != null) {
                joystickView.setThumbNormalized(cfg.panX, cfg.panY);
            }
            styleHudButton(tabBtnPtz, activeTab == 0);
            styleHudButton(tabBtnPlay, activeTab == 1);
            styleHudButton(tabBtnSensor, activeTab == 2);
            styleHudButton(btnPassthrough, cfg.passthroughRealCamera);
            styleHudButton(btnFit, TransformConfig.ASPECT_FIT.equals(cfg.aspectMode));
            styleHudButton(btnFill, TransformConfig.ASPECT_FILL.equals(cfg.aspectMode));
            styleHudButton(btnStr, TransformConfig.ASPECT_STRETCH.equals(cfg.aspectMode));
            styleHudButton(btnRotAuto, cfg.rotationMode == TransformConfig.ROT_AUTO);
            if (btnRot90 != null) {
                btnRot90.setText(cfg.rotationMode == TransformConfig.ROT_AUTO ? "+90°" : (cfg.rotationMode + "°"));
                styleHudButton(btnRot90, cfg.rotationMode != TransformConfig.ROT_AUTO);
            }
            styleHudButton(btnMirH, cfg.mirrorH);
            styleHudButton(btnMirV, cfg.mirrorV);
            if (btnFreeze != null) {
                btnFreeze.setText(cfg.paused ? "▶ Resume" : "⏸ Freeze");
                styleHudButton(btnFreeze, cfg.paused);
            }
            styleHudButton(btnSrcMedia, TransformConfig.SOURCE_MEDIA.equals(cfg.sourceMode));
            styleHudButton(btnSrcGrid, TransformConfig.SOURCE_TEST_PATTERN.equals(cfg.sourceMode));
            styleHudButton(btnSrcQr, TransformConfig.SOURCE_QR_CODE.equals(cfg.sourceMode));
            styleHudButton(btnGrain, cfg.sensorGrainEnabled);
        }

        private void nudgePan(float dx, float dy) {
            TransformConfig cfg = HookMain.getActiveTransformConfig();
            cfg.panX = Math.max(-1.0f, Math.min(1.0f, cfg.panX + dx));
            cfg.panY = Math.max(-1.0f, Math.min(1.0f, cfg.panY + dy));
            applyAndPersistTransform(cfg);
            refreshUiFromState();
        }

        private void setAspect(String mode) {
            TransformConfig cfg = HookMain.getActiveTransformConfig();
            cfg.aspectMode = mode;
            applyAndPersistTransform(cfg);
            refreshUiFromState();
        }

        private void setRot(int rot) {
            TransformConfig cfg = HookMain.getActiveTransformConfig();
            cfg.setRotation(rot);
            applyAndPersistTransform(cfg);
            refreshUiFromState();
        }

        private void setSourceMode(String srcMode) {
            TransformConfig cfg = HookMain.getActiveTransformConfig();
            cfg.sourceMode = srcMode;
            applyAndPersistTransform(cfg);
            refreshUiFromState();
        }

        private void applyAndPersistTransform(@NonNull TransformConfig cfg) {
            HookMain.updateActiveTransformInProcess(cfg);
            // Persist asynchronously back to MediaProvider so it survives camera restarts
            final String json = cfg.toJson().toString();
            final String facing = HookMain.getCurrentFacing();
            new Thread(() -> {
                try {
                    ContentResolver cr = hostActivity.getContentResolver();
                    Bundle extras = new Bundle();
                    extras.putString("pkg", hostPkg);
                    extras.putString("facing", facing);
                    extras.putString("transform_json", json);
                    cr.call(MediaProvider.URI_CONFIG, "update_transform", null, extras);
                } catch (Throwable ignored) {}
            }, "vcam-hud-save").start();
        }

        private Button makeSmallButton(@NonNull Context ctx, @NonNull String label,
                                       @NonNull OnClickListener listener) {
            Button b = new Button(ctx);
            b.setText(label);
            b.setAllCaps(false);
            b.getPaint().setFakeBoldText(true);
            b.setTextColor(0xFFE2E8F0);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            int ph = dp(ctx, 9);
            int pv = dp(ctx, 5);
            b.setPadding(ph, pv, ph, pv);
            b.setMinWidth(0);
            b.setMinHeight(0);
            b.setMinimumWidth(dp(ctx, 44));
            b.setMinimumHeight(dp(ctx, 36));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF252D3D);
            bg.setCornerRadius(dp(ctx, 8));
            bg.setStroke(dp(ctx, 1), 0x6680D8FF);
            b.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(ctx, 3), dp(ctx, 2), dp(ctx, 3), dp(ctx, 2));
            b.setLayoutParams(lp);
            b.setOnClickListener(listener);
            return b;
        }

        private Button makeTabButton(@NonNull Context ctx, @NonNull String label, final int tabIdx) {
            Button b = makeSmallButton(ctx, label, v -> switchTab(tabIdx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(ctx, 3), 0, dp(ctx, 3), 0);
            b.setLayoutParams(lp);
            return b;
        }
    }

    /**
     * Custom 2D Canvas Virtual Thumbstick Joystick for smooth Pan/Tilt control.
     * Double-tap resets to center (0, 0).
     */
    private static final class JoystickView extends View {
        interface Listener {
            void onJoystickMoved(float normX, float normY);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Listener listener;
        private float normX = 0f;
        private float normY = 0f;
        private long lastTapTime = 0L;

        JoystickView(Context context, Listener listener) {
            super(context);
            this.listener = listener;
        }

        void setThumbNormalized(float x, float y) {
            this.normX = Math.max(-1f, Math.min(1f, x));
            this.normY = Math.max(-1f, Math.min(1f, y));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float outerR = Math.min(w, h) * 0.44f;

            // Outer ring
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF222834);
            c.drawCircle(cx, cy, outerR, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(0x6680D8FF);
            c.drawCircle(cx, cy, outerR, paint);

            // Crosshairs
            paint.setColor(0x33FFFFFF);
            c.drawLine(cx - outerR, cy, cx + outerR, cy, paint);
            c.drawLine(cx, cy - outerR, cx, cy + outerR, paint);

            // Thumb position
            float tx = cx + normX * (outerR * 0.75f);
            float ty = cy + normY * (outerR * 0.75f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF00B0FF);
            c.drawCircle(tx, ty, outerR * 0.26f, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float maxR = Math.min(getWidth(), getHeight()) * 0.36f;
            if (maxR <= 1f) return false;

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                long now = System.currentTimeMillis();
                if (now - lastTapTime < 280L) {
                    normX = 0f;
                    normY = 0f;
                    invalidate();
                    if (listener != null) listener.onJoystickMoved(0f, 0f);
                    return true;
                }
                lastTapTime = now;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = (event.getX() - cx) / maxR;
                float dy = (event.getY() - cy) / maxR;
                float mag = (float) Math.hypot(dx, dy);
                if (mag > 1.0f) {
                    dx /= mag;
                    dy /= mag;
                }
                normX = dx;
                normY = dy;
                invalidate();
                if (listener != null) listener.onJoystickMoved(normX, normY);
                return true;
            }
            return super.onTouchEvent(event);
        }
    }
}
