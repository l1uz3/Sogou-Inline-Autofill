package io.github.l1uz3.sogouautofill;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.TypedValue;

import androidx.autofill.inline.UiVersions;
import androidx.autofill.inline.common.ImageViewStyle;
import androidx.autofill.inline.common.TextViewStyle;
import androidx.autofill.inline.common.ViewStyle;
import androidx.autofill.inline.v1.InlineSuggestionUi;

/**
 * 构造放进 InlinePresentationSpec 的样式 Bundle。
 *
 * 建议条目（chip）是由系统的 InlineSuggestionRenderService 按自动填充服务提供的 Slice 渲染的，
 * 输入法只能通过这个样式 Bundle 影响外观。Bitwarden 会检查样式里是否包含
 * UiVersions.INLINE_UI_VERSION_1，没有就不会返回内联建议（回退到下拉框）。
 */
final class InlineStyles {
    private InlineStyles() {}

    static boolean isNight(Context ctx) {
        int mode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    static int dp(Context ctx, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    static Bundle build(Context ctx) {
        boolean night = isNight(ctx);
        int chipBg = night ? 0xFF3C4043 : 0xFFFFFFFF;
        int titleColor = night ? 0xFFE8EAED : 0xFF202124;
        int subtitleColor = night ? 0xFFBDC1C6 : 0xFF5F6368;

        ViewStyle chip = new ViewStyle.Builder()
                .setBackgroundColor(chipBg)
                .setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
                .build();

        ImageViewStyle startIcon = new ImageViewStyle.Builder()
                .setMaxWidth(dp(ctx, 20))
                .setMaxHeight(dp(ctx, 20))
                .setLayoutMargin(0, 0, dp(ctx, 6), 0)
                .build();

        ImageViewStyle singleIcon = new ImageViewStyle.Builder()
                .setMaxWidth(dp(ctx, 22))
                .setMaxHeight(dp(ctx, 22))
                .build();

        TextViewStyle title = new TextViewStyle.Builder()
                .setTextColor(titleColor)
                .setTextSize(14)
                .setLayoutMargin(0, 0, dp(ctx, 4), 0)
                .build();

        TextViewStyle subtitle = new TextViewStyle.Builder()
                .setTextColor(subtitleColor)
                .setTextSize(12)
                .build();

        InlineSuggestionUi.Style style = InlineSuggestionUi.newStyleBuilder()
                .setChipStyle(chip)
                .setStartIconStyle(startIcon)
                .setEndIconStyle(startIcon)
                .setSingleIconChipStyle(chip)
                .setSingleIconChipIconStyle(singleIcon)
                .setTitleStyle(title)
                .setSubtitleStyle(subtitle)
                .build();

        return UiVersions.newStylesBuilder().addStyle(style).build();
    }
}
