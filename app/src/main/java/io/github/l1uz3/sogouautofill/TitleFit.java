package io.github.l1uz3.sogouautofill;

import android.app.slice.Slice;
import android.app.slice.SliceItem;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * 名称太长时截短名称，保证用户名（副标题）能显示出来。
 *
 * 建议条目由系统渲染服务里的 androidx InlineSuggestionUi 排版：标题（Bitwarden 的条目名）和副标题（用户名）横排一行，
 * 标题过长时会占满整行，副标题被挤到宽度为 0，同一网站的多个账号就分不清了。androidx 的样式里没有“标题最大宽度”，
 * 所以在 system_server 里、交给渲染服务之前，按实际像素测量：先给用户名留出完整宽度，名称放不下就在结尾截短加“…”。
 * 名称至少保留可用宽度的 {@link #MIN_TITLE_FRACTION}，用户名特别长时两者都会被截短。顺序不变（名称在前）。
 *
 * 只处理本模块发出的请求：搜狗侧在 InlinePresentationSpec 的样式 Bundle 里放了 {@link #KEY_ENABLED} 等参数，
 * 自动填充服务会原样带回，其他输入法的请求没有这些键，不受影响。
 */
final class TitleFit {
    static final String KEY_ENABLED = "io.github.l1uz3.sogouautofill.fit_title";
    static final String KEY_TITLE_PX = "io.github.l1uz3.sogouautofill.title_px";
    static final String KEY_SUBTITLE_PX = "io.github.l1uz3.sogouautofill.subtitle_px";
    /** chip 左右内边距 + 标题右边距 + 余量（像素）。 */
    static final String KEY_RESERVED_PX = "io.github.l1uz3.sogouautofill.reserved_px";

    private static final String HINT_TITLE = "inline_title";
    private static final String HINT_SUBTITLE = "inline_subtitle";
    private static final float MIN_TITLE_FRACTION = 0.35f;

    private TitleFit() {}

    /** 在样式 Bundle 里写入参数（搜狗进程调用）。 */
    static void putParams(Bundle style, float titlePx, float subtitlePx, int reservedPx) {
        style.putBoolean(KEY_ENABLED, true);
        style.putFloat(KEY_TITLE_PX, titlePx);
        style.putFloat(KEY_SUBTITLE_PX, subtitlePx);
        style.putInt(KEY_RESERVED_PX, reservedPx);
    }

    /**
     * 按需截短标题，返回新的 Slice；不需要处理（没有参数、没有副标题、放得下）或失败时返回原 Slice。
     *
     * @param style    InlinePresentationSpec 的样式 Bundle
     * @param maxWidth InlinePresentationSpec 的最大宽度（像素）
     */
    static Slice fit(Slice slice, Bundle style, int maxWidth) {
        if (slice == null || style == null || maxWidth <= 0) return slice;
        try {
            if (!style.getBoolean(KEY_ENABLED, false)) return slice;
            float titlePx = style.getFloat(KEY_TITLE_PX, 0);
            float subtitlePx = style.getFloat(KEY_SUBTITLE_PX, 0);
            int reserved = style.getInt(KEY_RESERVED_PX, 0);
            if (titlePx <= 0 || subtitlePx <= 0) return slice;

            CharSequence title = findText(slice, HINT_TITLE);
            CharSequence subtitle = findText(slice, HINT_SUBTITLE);
            if (isBlank(title) || isBlank(subtitle)) return slice;

            float available = maxWidth - reserved;
            if (available <= 0) return slice;

            TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            paint.setTextSize(subtitlePx);
            float subtitleWidth = paint.measureText(subtitle, 0, subtitle.length());
            float titleBudget = Math.max(available * MIN_TITLE_FRACTION, available - subtitleWidth);

            paint.setTextSize(titlePx);
            if (paint.measureText(title, 0, title.length()) <= titleBudget) return slice;
            CharSequence shortened = TextUtils.ellipsize(title, paint, titleBudget, TextUtils.TruncateAt.END);
            if (isBlank(shortened) || shortened.toString().equals(title.toString())) return slice;

            Slice rebuilt = replaceTitle(slice, shortened);
            if (rebuilt != slice) L.d("system_server: shortened inline title to fit username");
            return rebuilt;
        } catch (Throwable t) {
            L.e("system_server: failed to fit inline title", t);
            return slice;
        }
    }

    private static boolean isBlank(CharSequence s) {
        return s == null || s.toString().trim().isEmpty();
    }

    private static CharSequence findText(Slice slice, String hint) {
        for (SliceItem item : slice.getItems()) {
            if (SliceItem.FORMAT_TEXT.equals(item.getFormat()) && item.getHints().contains(hint)) {
                return item.getText();
            }
        }
        return null;
    }

    /** 复制 Slice，只替换标题文本。遇到不认识的格式时放弃（返回原 Slice）。 */
    private static Slice replaceTitle(Slice slice, CharSequence newTitle) {
        Slice.Builder b = new Slice.Builder(slice.getUri(), slice.getSpec());
        b.addHints(slice.getHints());
        for (SliceItem item : slice.getItems()) {
            String sub = item.getSubType();
            switch (item.getFormat()) {
                case SliceItem.FORMAT_TEXT:
                    CharSequence text = item.getHints().contains(HINT_TITLE) ? newTitle : item.getText();
                    b.addText(text, sub, item.getHints());
                    break;
                case SliceItem.FORMAT_IMAGE:
                    b.addIcon(item.getIcon(), sub, item.getHints());
                    break;
                case SliceItem.FORMAT_ACTION:
                    b.addAction(item.getAction(), item.getSlice(), sub);
                    break;
                case SliceItem.FORMAT_SLICE:
                    b.addSubSlice(item.getSlice(), sub);
                    break;
                case SliceItem.FORMAT_INT:
                    b.addInt(item.getInt(), sub, item.getHints());
                    break;
                case SliceItem.FORMAT_LONG:
                    b.addLong(item.getLong(), sub, item.getHints());
                    break;
                case SliceItem.FORMAT_REMOTE_INPUT:
                    b.addRemoteInput(item.getRemoteInput(), sub, item.getHints());
                    break;
                case SliceItem.FORMAT_BUNDLE:
                    b.addBundle(item.getBundle(), sub, item.getHints());
                    break;
                default:
                    return slice;
            }
        }
        return b.build();
    }
}
