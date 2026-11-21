package io.github.l1uz3.sogouautofill;

import android.app.slice.Slice;
import android.app.slice.SliceItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 交换内联建议里的标题和副标题，让用户名显示在前面。
 *
 * 自动填充服务用 androidx.autofill 的 InlineSuggestionUi.Content 把内容打包成 Slice，
 * 标题/副标题是带 "inline_title" / "inline_subtitle" hint 的文本项，由系统的渲染服务按“标题在前、副标题在后”排版。
 * Bitwarden 的标题是条目名（通常就是域名），副标题是用户名；同一个网站的建议本来就都是这个域名，
 * 所以把两者对调，用户名作为主文字显示在前，域名作为暗一点的副文字跟在后面。
 *
 * 只在标题和副标题都存在且非空时才交换（只有标题的条目保持原样）。
 */
final class SliceSwap {
    static final String HINT_TITLE = "inline_title";
    static final String HINT_SUBTITLE = "inline_subtitle";

    private SliceSwap() {}

    /** 返回交换后的新 Slice；不需要交换或失败时返回原 Slice。 */
    static Slice swapTitleAndSubtitle(Slice slice) {
        if (slice == null) return null;
        try {
            if (!hasText(slice, HINT_TITLE) || !hasText(slice, HINT_SUBTITLE)) return slice;

            Slice.Builder b = new Slice.Builder(slice.getUri(), slice.getSpec());
            b.addHints(slice.getHints());
            for (SliceItem item : slice.getItems()) {
                List<String> hints = swapHints(item.getHints());
                String sub = item.getSubType();
                switch (item.getFormat()) {
                    case SliceItem.FORMAT_TEXT:
                        b.addText(item.getText(), sub, hints);
                        break;
                    case SliceItem.FORMAT_IMAGE:
                        b.addIcon(item.getIcon(), sub, hints);
                        break;
                    case SliceItem.FORMAT_ACTION:
                        b.addAction(item.getAction(), item.getSlice(), sub);
                        break;
                    case SliceItem.FORMAT_SLICE:
                        b.addSubSlice(item.getSlice(), sub);
                        break;
                    case SliceItem.FORMAT_INT:
                        b.addInt(item.getInt(), sub, hints);
                        break;
                    case SliceItem.FORMAT_LONG:
                        b.addLong(item.getLong(), sub, hints);
                        break;
                    case SliceItem.FORMAT_REMOTE_INPUT:
                        b.addRemoteInput(item.getRemoteInput(), sub, hints);
                        break;
                    case SliceItem.FORMAT_BUNDLE:
                        b.addBundle(item.getBundle(), sub, hints);
                        break;
                    default:
                        // 不认识的格式：放弃交换，保持原样最安全
                        return slice;
                }
            }
            return b.build();
        } catch (Throwable t) {
            L.e("system_server: failed to swap inline title/subtitle", t);
            return slice;
        }
    }

    private static boolean hasText(Slice slice, String hint) {
        for (SliceItem item : slice.getItems()) {
            if (SliceItem.FORMAT_TEXT.equals(item.getFormat()) && item.getHints().contains(hint)) {
                CharSequence text = item.getText();
                return text != null && text.toString().trim().length() > 0;
            }
        }
        return false;
    }

    private static List<String> swapHints(List<String> hints) {
        List<String> out = new ArrayList<>(hints.size());
        for (String h : hints) {
            if (HINT_TITLE.equals(h)) out.add(HINT_SUBTITLE);
            else if (HINT_SUBTITLE.equals(h)) out.add(HINT_TITLE);
            else out.add(h);
        }
        return out;
    }
}
