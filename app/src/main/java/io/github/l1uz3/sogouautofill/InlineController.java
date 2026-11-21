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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.inline.InlineContentView;
import android.widget.inline.InlinePresentationSpec;

import java.util.Collections;
import java.util.List;

/**
 * 每个 InputMethodService 实例一个，负责：
 *  - 构造 InlineSuggestionsRequest；
 *  - 把 InlineSuggestionsResponse 里的建议 inflate 成 InlineContentView 并放进键盘上方的建议条；
 *  - 修正 Insets，让建议条可点击、且不遮挡输入框。
 *
 * 建议条插在输入法窗口的 android:id/inputArea（承载搜狗键盘 View 的 FrameLayout）之前，
 * 完全不碰搜狗自己的 View 层级。
 *
 * 所有回调都在主线程。
 */
final class InlineController {
    private static final int MAX_SUGGESTIONS = 8;
    private static final int CHIP_HEIGHT_DP = 36;

    private final InputMethodService ims;

    private LinearLayout strip;          // 整条：[固定区][可横向滚动区]
    private LinearLayout pinnedContainer;
    private LinearLayout scrollContainer;
    private HorizontalScrollView scrollView;

    /** 每次收到新响应或清空时递增，用于丢弃过期的异步 inflate 结果。 */
    private int generation;

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
            hideStrip();
            return;
        }

        final int n = list.size();
        final InlineContentView[] views = new InlineContentView[n];
        final int[] remaining = {n};
        // WRAP_CONTENT：由渲染服务在 spec 的 [min, max] 范围内按内容测量
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
        int added = 0;
        for (int i = 0; i < views.length; i++) {
            InlineContentView v = views[i];
            if (v == null) continue;
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(margin, 0, margin, 0);
            lp.gravity = Gravity.CENTER_VERTICAL;
            // “固定”条目（例如 Bitwarden 的打开密码库图标）放在左侧不随滚动
            boolean pinned = list.get(i).getInfo().isPinned();
            (pinned ? pinnedContainer : scrollContainer).addView(v, lp);
            added++;
        }

        if (added == 0) {
            hideStrip();
            return;
        }
        strip.setBackgroundColor(InlineStyles.isNight(ims) ? 0xFF202124 : 0xFFF1F3F4);
        pinnedContainer.setVisibility(pinnedContainer.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        scrollView.scrollTo(0, 0);
        strip.setVisibility(View.VISIBLE);
    }

    void clear() {
        generation++;
        hideStrip();
    }

    private void hideStrip() {
        if (strip == null) return;
        pinnedContainer.removeAllViews();
        scrollContainer.removeAllViews();
        strip.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- view

    /** 确保建议条已插入输入法窗口中 inputArea 的上方。 */
    private boolean ensureStrip() {
        View inputArea;
        try {
            Window window = ims.getWindow().getWindow();
            inputArea = window == null ? null : window.getDecorView().findViewById(android.R.id.inputArea);
        } catch (Throwable t) {
            L.e("ime: cannot find inputArea", t);
            return false;
        }
        if (inputArea == null || !(inputArea.getParent() instanceof ViewGroup)) {
            L.i("ime: inputArea not available");
            return false;
        }
        ViewGroup parent = (ViewGroup) inputArea.getParent();

        if (strip == null) createStrip();
        if (strip.getParent() != parent) {
            if (strip.getParent() instanceof ViewGroup) ((ViewGroup) strip.getParent()).removeView(strip);
            parent.addView(strip, parent.indexOfChild(inputArea),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            L.d("ime: strip attached to " + parent.getClass().getName());
        }
        return true;
    }

    private void createStrip() {
        int pad = InlineStyles.dp(ims, 4);

        strip = new LinearLayout(ims);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(pad, pad, pad, pad);
        strip.setMinimumHeight(InlineStyles.dp(ims, CHIP_HEIGHT_DP) + pad * 2);
        strip.setVisibility(View.GONE);

        pinnedContainer = new LinearLayout(ims);
        pinnedContainer.setOrientation(LinearLayout.HORIZONTAL);
        pinnedContainer.setGravity(Gravity.CENTER_VERTICAL);
        strip.addView(pinnedContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scrollView = new HorizontalScrollView(ims);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollContainer = new LinearLayout(ims);
        scrollContainer.setOrientation(LinearLayout.HORIZONTAL);
        scrollContainer.setGravity(Gravity.CENTER_VERTICAL);
        scrollView.addView(scrollContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        strip.addView(scrollView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    // ---------------------------------------------------------------- insets

    /**
     * 在搜狗算完 Insets 之后再修正：
     *  - visibleTopInsets / contentTopInsets 上移到建议条顶部，应用会把输入框顶上来而不是被建议条盖住；
     *  - 如果搜狗用的是 TOUCHABLE_INSETS_REGION（悬浮键盘等），把建议条区域并进可触摸区域。
     */
    void adjustInsets(InputMethodService.Insets out) {
        if (strip == null || strip.getVisibility() != View.VISIBLE
                || !strip.isAttachedToWindow() || strip.getHeight() == 0) {
            return;
        }
        int[] loc = new int[2];
        strip.getLocationInWindow(loc);
        int top = loc[1];

        View decor = strip.getRootView();
        int decorHeight = decor == null ? Integer.MAX_VALUE : decor.getHeight();

        if (out.visibleTopInsets > top) out.visibleTopInsets = top;
        // contentTopInsets == 窗口高度通常表示搜狗处于悬浮/全屏模式，不希望应用被顶起，这种情况不动它
        if (out.contentTopInsets > top && out.contentTopInsets < decorHeight) out.contentTopInsets = top;
        if (out.touchableInsets == InputMethodService.Insets.TOUCHABLE_INSETS_REGION) {
            out.touchableRegion.op(loc[0], top, loc[0] + strip.getWidth(), top + strip.getHeight(),
                    Region.Op.UNION);
        }
    }
}
