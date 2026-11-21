package io.github.l1uz3.sogouautofill;

import android.graphics.Region;
import android.inputmethodservice.InputMethodService;
import android.os.Bundle;
import android.os.LocaleList;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.inline.InlineContentView;
import android.widget.inline.InlinePresentationSpec;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;

/**
 * 每个 InputMethodService 实例一个，负责构造 InlineSuggestionsRequest，
 * 并把 InlineSuggestionsResponse 里的建议显示在键盘里。
 *
 * 两种显示模式：
 *  - NATIVE（搜狗 20.10.0 已适配，见 {@link SogouNative}）：建议栏是加在搜狗 Toolbar 里的透明 View，
 *    显示时隐藏搜狗工具栏图标、保留皮肤背景，chip 半透明并按皮肤亮度选文字颜色，看起来和搜狗原生一致。
 *    搜狗切到候选词（正在打字）时自动让位，切回工具栏时恢复。
 *  - OVERLAY（回退方案，适配失败时使用）：不透明的建议栏覆盖在 inputArea 顶部。
 *
 * 两种模式都不改变键盘高度、不修改 contentTopInsets，所以建议出现/消失时页面不会跳动。
 *
 * 所有回调都在主线程。
 */
final class InlineController {
    private enum Mode { NONE, NATIVE, OVERLAY }

    private static final int MAX_SUGGESTIONS = 8;
    private static final int DEFAULT_CHIP_HEIGHT_DP = 36;
    private static final int OVERLAY_STRIP_HEIGHT_DP = 44;

    private final InputMethodService ims;

    private LinearLayout strip;          // 整条：[固定区][可横向滚动区][×]
    private LinearLayout pinnedContainer;
    private LinearLayout scrollContainer;
    private HorizontalScrollView scrollView;
    private TextView closeButton;

    private Mode mode = Mode.NONE;
    private WeakReference<ViewGroup> toolbarRef = new WeakReference<>(null);
    private ViewTreeObserver observedTreeObserver;
    private final ViewTreeObserver.OnPreDrawListener preDrawListener = () -> !sync();

    /** 每次收到新响应或清空时递增，用于丢弃过期的异步 inflate 结果。 */
    private int generation;
    private boolean hasSuggestions;
    /** 搜狗正在组字（仅 OVERLAY 模式使用；NATIVE 模式直接看搜狗工具栏组件的可见性）。 */
    private boolean composing;
    /** 用户在当前输入框点了 ×。 */
    private boolean dismissed;

    /** 本次请求使用的配色和 chip 高度。 */
    private InlineStyles.Palette palette;
    private int chipHeightPx;
    private Boolean lastLightBackground;

    /** OVERLAY 模式：搜狗报告的键盘顶部（窗口坐标），用于对齐。 */
    private int keyboardTopInWindow = -1;

    InlineController(InputMethodService ims) {
        this.ims = ims;
        SogouNative.controller = this;
    }

    // ---------------------------------------------------------------- request

    InlineSuggestionsRequest onCreateRequest(Bundle uiExtras) {
        try {
            ViewGroup toolbar = SogouNative.ready ? findToolbar() : null;
            chipHeightPx = InlineStyles.dp(ims, DEFAULT_CHIP_HEIGHT_DP);
            if (toolbar != null) {
                Boolean light = InlineStyles.isLightBackground(toolbar);
                if (light != null) lastLightBackground = light;
                boolean useLight = lastLightBackground != null ? lastLightBackground : !InlineStyles.isNight(ims);
                palette = InlineStyles.Palette.translucent(useLight);
                if (toolbar.getHeight() > 0) {
                    chipHeightPx = clamp(toolbar.getHeight() - InlineStyles.dp(ims, 8),
                            InlineStyles.dp(ims, 28), InlineStyles.dp(ims, 40));
                }
                L.d("ime: toolbar " + toolbar.getWidth() + "x" + toolbar.getHeight()
                        + " light=" + light + " chipHeight=" + chipHeightPx);
            } else {
                palette = InlineStyles.Palette.opaque(InlineStyles.isNight(ims));
            }

            Bundle style = InlineStyles.build(ims, palette);
            int screenW = ims.getResources().getDisplayMetrics().widthPixels;
            Size min = new Size(InlineStyles.dp(ims, 40), chipHeightPx);
            Size max = new Size(Math.max(min.getWidth(), screenW * 2 / 3), chipHeightPx);
            InlinePresentationSpec spec = new InlinePresentationSpec.Builder(min, max)
                    .setStyle(style)
                    .build();
            // 列表里最后一个 spec 会被重复用于后面所有条目，所以给一个就够
            InlineSuggestionsRequest request = new InlineSuggestionsRequest.Builder(
                    Collections.singletonList(spec))
                    .setMaxSuggestionCount(MAX_SUGGESTIONS)
                    .setSupportedLocales(LocaleList.getDefault())
                    .build();
            L.d("ime: onCreateInlineSuggestionsRequest -> " + request);
            return request;
        } catch (Throwable t) {
            L.e("ime: failed to build InlineSuggestionsRequest", t);
            return null;
        }
    }

    // ---------------------------------------------------------------- response

    void onResponse(InlineSuggestionsResponse response) {
        final List<InlineSuggestion> list = response.getInlineSuggestions();
        L.d("ime: onInlineSuggestionsResponse, count=" + list.size());
        final int gen = ++generation;
        if (list.isEmpty()) {
            removeSuggestions();
            return;
        }

        final int n = list.size();
        final InlineContentView[] views = new InlineContentView[n];
        final int[] remaining = {n};
        // WRAP_CONTENT：由渲染服务在 spec 的 [min, max] 范围内按内容测量，
        // 渲染完成后系统会把真实尺寸写进 InlineContentView 的 LayoutParams
        Size size = new Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        for (int i = 0; i < n; i++) {
            final int index = i;
            try {
                list.get(i).inflate(ims, size, ims.getMainExecutor(), view -> {
                    if (gen != generation) return; // 已有更新的响应
                    views[index] = view;
                    if (--remaining[0] == 0) show(list, views);
                });
            } catch (Throwable t) {
                L.e("ime: inflate failed for suggestion " + i, t);
                if (--remaining[0] == 0) show(list, views);
            }
        }
    }

    private void show(List<InlineSuggestion> list, InlineContentView[] views) {
        if (!ensureStrip()) return;
        pinnedContainer.removeAllViews();
        scrollContainer.removeAllViews();

        int margin = InlineStyles.dp(ims, 4);
        int maxH = chipHeightPx > 0 ? chipHeightPx : InlineStyles.dp(ims, DEFAULT_CHIP_HEIGHT_DP);
        int added = 0;
        for (int i = 0; i < views.length; i++) {
            InlineContentView v = views[i];
            if (v == null) continue;
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            // 必须沿用系统写入的真实尺寸。InlineContentView 内部是 SurfaceView，
            // 给它 WRAP_CONTENT 会撑满父布局给的全部空间。
            ViewGroup.LayoutParams own = v.getLayoutParams();
            int w = own != null && own.width > 0 ? own.width : InlineStyles.dp(ims, 120);
            int h = own != null && own.height > 0 ? Math.min(own.height, maxH) : maxH;
            L.d("ime: suggestion " + i + " size=" + (own == null ? "null" : own.width + "x" + own.height)
                    + " -> " + w + "x" + h);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.setMargins(margin, 0, margin, 0);
            lp.gravity = Gravity.CENTER_VERTICAL;
            // “固定”条目（例如 Bitwarden 的打开密码库图标）放在左侧不随滚动
            boolean pinned = list.get(i).getInfo().isPinned();
            (pinned ? pinnedContainer : scrollContainer).addView(v, lp);
            added++;
        }

        if (added == 0) {
            removeSuggestions();
            return;
        }
        applyStripColors();
        pinnedContainer.setVisibility(pinnedContainer.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        scrollView.scrollTo(0, 0);
        hasSuggestions = true;
        sync();
    }

    /** 切换输入框 / 收起键盘：清空建议并重置“已关闭”状态。 */
    void clear() {
        generation++;
        dismissed = false;
        composing = false;
        removeSuggestions();
    }

    private void removeSuggestions() {
        hasSuggestions = false;
        if (strip == null) return;
        pinnedContainer.removeAllViews();
        scrollContainer.removeAllViews();
        sync();
    }

    /** 搜狗组字状态变化（来自 onUpdateSelection），仅 OVERLAY 模式使用。 */
    void setComposing(boolean value) {
        if (composing == value) return;
        composing = value;
        sync();
    }

    /** 搜狗调用了 setInputView（inputArea 被清空）。 */
    void onInputViewReplaced() {
        reattachIfNeeded();
    }

    /** 搜狗调用了 Toolbar.setCandidatesView（Toolbar 被清空）。 */
    void onToolbarContentReplaced() {
        reattachIfNeeded();
    }

    private void reattachIfNeeded() {
        if (strip == null) return;
        if (hasSuggestions && strip.getParent() == null) ensureStrip();
        sync();
    }

    /**
     * 根据当前状态决定建议栏是否显示，NATIVE 模式下同时控制是否隐藏搜狗工具栏图标。
     * 在每帧绘制前（OnPreDrawListener）和状态变化时调用。返回是否有改动。
     */
    private boolean sync() {
        if (strip == null) return false;
        boolean want = hasSuggestions && !dismissed && strip.getParent() != null;
        ViewGroup toolbar = mode == Mode.NATIVE ? toolbarRef.get() : null;
        if (mode == Mode.NATIVE) {
            // 工具栏图标组件不可见 = 搜狗在显示候选词等，让位
            want &= toolbar != null && candidateContainerVisible(toolbar) && SogouNative.isFunctionViewVisible();
        } else {
            want &= !composing;
        }

        boolean changed = false;
        int v = want ? View.VISIBLE : View.GONE;
        if (strip.getVisibility() != v) {
            strip.setVisibility(v);
            changed = true;
        }
        boolean suppress = want && mode == Mode.NATIVE;
        if (SogouNative.suppressFunctionDraw != suppress) {
            SogouNative.suppressFunctionDraw = suppress;
            if (toolbar != null) invalidateTree(toolbar);
            changed = true;
        }
        return changed;
    }

    // ---------------------------------------------------------------- view

    private boolean ensureStrip() {
        if (strip == null) createStrip();

        if (SogouNative.ready) {
            ViewGroup toolbar = findToolbar();
            if (toolbar != null) {
                attachNative(toolbar);
                return true;
            }
            L.i("ime: Sogou Toolbar not found in window, using overlay");
        }
        FrameLayout inputArea = findInputArea();
        if (inputArea == null) return false;
        attachOverlay(inputArea);
        return true;
    }

    private void attachNative(ViewGroup toolbar) {
        if (strip.getParent() != toolbar) {
            detachStrip();
            toolbar.addView(strip, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            L.d("ime: strip attached inside Sogou Toolbar");
        } else if (toolbar.getChildAt(toolbar.getChildCount() - 1) != strip) {
            strip.bringToFront();
        }
        mode = Mode.NATIVE;
        observe(toolbar);
    }

    private void attachOverlay(FrameLayout inputArea) {
        if (strip.getParent() != inputArea) {
            detachStrip();
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    InlineStyles.dp(ims, OVERLAY_STRIP_HEIGHT_DP), Gravity.TOP);
            inputArea.addView(strip, lp); // 最后添加 = 绘制在最上层
            L.d("ime: strip attached as overlay of inputArea");
        } else if (inputArea.getChildAt(inputArea.getChildCount() - 1) != strip) {
            strip.bringToFront();
        }
        mode = Mode.OVERLAY;
        observe(inputArea);
        updateOverlayPosition(inputArea);
    }

    private void detachStrip() {
        if (strip.getParent() instanceof ViewGroup) ((ViewGroup) strip.getParent()).removeView(strip);
    }

    private void observe(View v) {
        ViewTreeObserver vto = v.getViewTreeObserver();
        if (vto == observedTreeObserver && vto.isAlive()) return;
        if (observedTreeObserver != null && observedTreeObserver.isAlive()) {
            observedTreeObserver.removeOnPreDrawListener(preDrawListener);
        }
        vto.addOnPreDrawListener(preDrawListener);
        observedTreeObserver = vto;
    }

    private void applyStripColors() {
        InlineStyles.Palette p = palette != null ? palette : InlineStyles.Palette.opaque(InlineStyles.isNight(ims));
        if (mode == Mode.NATIVE) {
            strip.setBackground(null); // 透出搜狗皮肤
        } else {
            strip.setBackgroundColor(InlineStyles.isNight(ims) ? 0xFF202124 : 0xFFF1F3F4);
        }
        closeButton.setTextColor(p.subtitle);
    }

    private ViewGroup findToolbar() {
        ViewGroup cached = toolbarRef.get();
        if (cached != null && cached.isAttachedToWindow()) return cached;
        try {
            Window window = ims.getWindow().getWindow();
            if (window == null || SogouNative.toolbarClass == null) return null;
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(window.getDecorView());
            while (!queue.isEmpty()) {
                View v = queue.poll();
                if (SogouNative.toolbarClass.isInstance(v)) {
                    toolbarRef = new WeakReference<>((ViewGroup) v);
                    return (ViewGroup) v;
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) queue.add(g.getChildAt(i));
                }
            }
        } catch (Throwable t) {
            L.e("ime: failed to search Sogou Toolbar", t);
        }
        return null;
    }

    /** Toolbar 里搜狗的候选区（除建议栏外的子 View）是否可见。 */
    private boolean candidateContainerVisible(ViewGroup toolbar) {
        if (!toolbar.isShown()) return false;
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            View c = toolbar.getChildAt(i);
            if (c != strip) return c.getVisibility() == View.VISIBLE;
        }
        return false;
    }

    private static void invalidateTree(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateTree(g.getChildAt(i));
        }
    }

    private FrameLayout findInputArea() {
        try {
            Window window = ims.getWindow().getWindow();
            View v = window == null ? null : window.getDecorView().findViewById(android.R.id.inputArea);
            if (v instanceof FrameLayout) return (FrameLayout) v;
            L.i("ime: inputArea not available: " + v);
        } catch (Throwable t) {
            L.e("ime: cannot find inputArea", t);
        }
        return null;
    }

    /** OVERLAY 模式：用搜狗报告的 contentTopInsets 把建议栏对齐到键盘可见顶部。 */
    private void updateOverlayPosition(View inputArea) {
        if (mode != Mode.OVERLAY || !(strip.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        int stripH = InlineStyles.dp(ims, OVERLAY_STRIP_HEIGHT_DP);
        int margin = 0;
        if (keyboardTopInWindow >= 0 && inputArea.getHeight() > 0) {
            int[] loc = new int[2];
            inputArea.getLocationInWindow(loc);
            int y = keyboardTopInWindow - loc[1];
            if (y > 0 && y + stripH <= inputArea.getHeight()) margin = y;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) strip.getLayoutParams();
        if (lp.topMargin != margin) {
            lp.topMargin = margin;
            strip.setLayoutParams(lp);
            L.d("ime: overlay strip topMargin=" + margin);
        }
    }

    private void createStrip() {
        int pad = InlineStyles.dp(ims, 4);

        strip = new LinearLayout(ims);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(pad, 0, 0, 0);
        // 吃掉触摸事件，避免点到下面被盖住的搜狗工具栏
        strip.setClickable(true);
        strip.setVisibility(View.GONE);

        pinnedContainer = new LinearLayout(ims);
        pinnedContainer.setOrientation(LinearLayout.HORIZONTAL);
        pinnedContainer.setGravity(Gravity.CENTER_VERTICAL);
        strip.addView(pinnedContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        scrollView = new HorizontalScrollView(ims);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollContainer = new LinearLayout(ims);
        scrollContainer.setOrientation(LinearLayout.HORIZONTAL);
        scrollContainer.setGravity(Gravity.CENTER_VERTICAL);
        scrollView.addView(scrollContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        strip.addView(scrollView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // × ：在当前输入框内关闭建议，恢复搜狗原本的工具栏
        closeButton = new TextView(ims);
        closeButton.setText("✕");
        closeButton.setTextSize(15);
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setContentDescription("关闭自动填充建议");
        closeButton.setOnClickListener(v -> {
            dismissed = true;
            sync();
        });
        strip.addView(closeButton, new LinearLayout.LayoutParams(
                InlineStyles.dp(ims, 40), ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---------------------------------------------------------------- insets

    /**
     * 在搜狗算完 Insets 之后调用。建议栏在键盘内部，所以<b>不修改</b> content/visible insets（应用不会被缩放）。
     *  - OVERLAY 模式记下键盘顶部用于对齐；
     *  - 如果搜狗用的是 TOUCHABLE_INSETS_REGION，把建议栏区域并进可触摸区域。
     */
    void adjustInsets(InputMethodService.Insets out) {
        if (mode == Mode.OVERLAY && out.contentTopInsets != keyboardTopInWindow) {
            keyboardTopInWindow = out.contentTopInsets;
            if (strip != null && strip.getParent() instanceof View) {
                final View parent = (View) strip.getParent();
                strip.post(() -> updateOverlayPosition(parent)); // 正处于遍历过程中，下一帧再改布局
            }
        }

        if (strip == null || strip.getVisibility() != View.VISIBLE
                || !strip.isAttachedToWindow() || strip.getHeight() == 0) {
            return;
        }
        if (out.touchableInsets == InputMethodService.Insets.TOUCHABLE_INSETS_REGION) {
            int[] loc = new int[2];
            strip.getLocationInWindow(loc);
            out.touchableRegion.op(loc[0], loc[1], loc[0] + strip.getWidth(), loc[1] + strip.getHeight(),
                    Region.Op.UNION);
        }
    }
}
