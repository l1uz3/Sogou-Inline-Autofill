package io.github.l1uz3.sogouautofill;

import android.graphics.Region;
import android.inputmethodservice.InputMethodService;
import android.os.Bundle;
import android.os.LocaleList;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

import java.util.Collections;
import java.util.List;

/**
 * 每个 InputMethodService 实例一个，负责：
 *  - 构造 InlineSuggestionsRequest；
 *  - 把 InlineSuggestionsResponse 里的建议 inflate 成 InlineContentView，显示在键盘里；
 *
 * 显示方式参考 Gboard：建议栏是一层<b>覆盖</b>在搜狗键盘顶部工具栏上的 View，
 * 加在 android:id/inputArea（承载搜狗键盘的 FrameLayout）里、搜狗键盘 View 的上面。
 * 它不占用额外高度、不修改 contentTopInsets，所以建议栏出现/消失时键盘高度不变，
 * 应用不会被反复缩放（早期版本把建议栏作为单独一行插在键盘上方，手动输入时页面会乱跳）。
 *
 * 建议栏何时隐藏：
 *  - 切换输入框 / 收起键盘（clear）；
 *  - 自动填充服务返回空列表（例如手动输入后没有匹配项）；
 *  - 搜狗正在组字（拼音输入中，需要用工具栏位置显示候选词），组字结束后自动恢复；
 *  - 用户点了右侧的 ×，在当前输入框内不再显示。
 *
 * 所有回调都在主线程。
 */
final class InlineController {
    private static final int MAX_SUGGESTIONS = 8;
    private static final int CHIP_HEIGHT_DP = 36;
    private static final int STRIP_HEIGHT_DP = 44;

    private final InputMethodService ims;

    private LinearLayout strip;          // 整条：[固定区][可横向滚动区][×]
    private LinearLayout pinnedContainer;
    private LinearLayout scrollContainer;
    private HorizontalScrollView scrollView;
    private TextView closeButton;

    /** 每次收到新响应或清空时递增，用于丢弃过期的异步 inflate 结果。 */
    private int generation;
    /** 当前有可显示的建议。 */
    private boolean hasSuggestions;
    /** 搜狗正在组字（有 composing text）。 */
    private boolean composing;
    /** 用户在当前输入框点了 ×。 */
    private boolean dismissed;
    /** 搜狗自己算出的键盘可见区域顶部（窗口坐标），用于把建议栏对齐到键盘顶部。 */
    private int keyboardTopInWindow = -1;

    InlineController(InputMethodService ims) {
        this.ims = ims;
    }

    // ---------------------------------------------------------------- request

    InlineSuggestionsRequest onCreateRequest(Bundle uiExtras) {
        try {
            Bundle style = InlineStyles.build(ims);
            int h = InlineStyles.dp(ims, CHIP_HEIGHT_DP);
            int screenW = ims.getResources().getDisplayMetrics().widthPixels;
            Size min = new Size(InlineStyles.dp(ims, 40), h);
            Size max = new Size(Math.max(min.getWidth(), screenW * 2 / 3), h);
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
        int chipHeight = InlineStyles.dp(ims, CHIP_HEIGHT_DP);
        int added = 0;
        for (int i = 0; i < views.length; i++) {
            InlineContentView v = views[i];
            if (v == null) continue;
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            // 必须沿用系统写入的真实尺寸。InlineContentView 内部是 SurfaceView，
            // 给它 WRAP_CONTENT 会撑满父布局给的全部空间。
            ViewGroup.LayoutParams own = v.getLayoutParams();
            int w = own != null && own.width > 0 ? own.width : InlineStyles.dp(ims, 120);
            int h = own != null && own.height > 0 ? Math.min(own.height, chipHeight) : chipHeight;
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
        boolean night = InlineStyles.isNight(ims);
        strip.setBackgroundColor(night ? 0xFF202124 : 0xFFF1F3F4);
        closeButton.setTextColor(night ? 0xFFBDC1C6 : 0xFF5F6368);
        pinnedContainer.setVisibility(pinnedContainer.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        scrollView.scrollTo(0, 0);
        hasSuggestions = true;
        updateVisibility();
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
        updateVisibility();
    }

    /** 搜狗组字状态变化（来自 onUpdateSelection 的 candidatesStart/End）。 */
    void setComposing(boolean value) {
        if (composing == value) return;
        composing = value;
        updateVisibility();
    }

    /** 搜狗调用了 setInputView：inputArea 被 removeAllViews，需要把建议栏重新挂上去。 */
    void onInputViewReplaced() {
        if (strip != null && strip.getParent() == null && hasSuggestions) {
            ensureStrip();
            updateVisibility();
        }
    }

    private void updateVisibility() {
        if (strip == null) return;
        boolean visible = hasSuggestions && !composing && !dismissed;
        int v = visible ? View.VISIBLE : View.GONE;
        if (strip.getVisibility() != v) strip.setVisibility(v);
    }

    // ---------------------------------------------------------------- view

    /** 确保建议栏已作为覆盖层加在 inputArea 里（搜狗键盘 View 之上）。 */
    private boolean ensureStrip() {
        FrameLayout inputArea = findInputArea();
        if (inputArea == null) return false;

        if (strip == null) createStrip();
        if (strip.getParent() != inputArea) {
            if (strip.getParent() instanceof ViewGroup) ((ViewGroup) strip.getParent()).removeView(strip);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, InlineStyles.dp(ims, STRIP_HEIGHT_DP), Gravity.TOP);
            inputArea.addView(strip, lp); // 最后添加 = 绘制在最上层
            L.d("ime: strip attached as overlay of inputArea");
        } else if (inputArea.getChildAt(inputArea.getChildCount() - 1) != strip) {
            strip.bringToFront();
        }
        updateStripPosition(inputArea);
        return true;
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

    /**
     * 把建议栏对齐到搜狗键盘的可见顶部。
     * 搜狗的键盘 View 顶部可能有一块透明区域（用于气泡等），所以不能简单放在 inputArea 顶部，
     * 而是用搜狗自己在 onComputeInsets 里报告的 contentTopInsets 作为键盘顶部。
     */
    private void updateStripPosition(View inputArea) {
        if (strip == null || !(strip.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        int stripH = InlineStyles.dp(ims, STRIP_HEIGHT_DP);
        int margin = 0;
        if (keyboardTopInWindow >= 0 && inputArea.getHeight() > 0) {
            int[] loc = new int[2];
            inputArea.getLocationInWindow(loc);
            int y = keyboardTopInWindow - loc[1];
            // 超出范围（悬浮键盘、全屏等异常情况）就退回到 inputArea 顶部
            if (y > 0 && y + stripH <= inputArea.getHeight()) margin = y;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) strip.getLayoutParams();
        if (lp.topMargin != margin) {
            lp.topMargin = margin;
            strip.setLayoutParams(lp);
            L.d("ime: strip topMargin=" + margin);
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

        // × ：在当前输入框内关闭建议栏，露出搜狗原本的工具栏
        closeButton = new TextView(ims);
        closeButton.setText("✕");
        closeButton.setTextSize(16);
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setContentDescription("关闭自动填充建议");
        closeButton.setOnClickListener(v -> {
            dismissed = true;
            updateVisibility();
        });
        strip.addView(closeButton, new LinearLayout.LayoutParams(
                InlineStyles.dp(ims, 44), ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---------------------------------------------------------------- insets

    /**
     * 在搜狗算完 Insets 之后调用。
     * 建议栏是键盘内部的覆盖层，所以<b>不修改</b> contentTopInsets / visibleTopInsets（保证应用不会被缩放），
     * 只做两件事：
     *  - 记下搜狗报告的键盘顶部，用于对齐建议栏；
     *  - 如果搜狗用的是 TOUCHABLE_INSETS_REGION，把建议栏区域并进可触摸区域。
     */
    void adjustInsets(InputMethodService.Insets out) {
        int top = out.contentTopInsets;
        if (top != keyboardTopInWindow) {
            keyboardTopInWindow = top;
            if (strip != null && strip.getParent() instanceof View) {
                final View parent = (View) strip.getParent();
                // 正处于 ViewRootImpl 的遍历过程中，改布局放到下一帧
                strip.post(() -> updateStripPosition(parent));
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
