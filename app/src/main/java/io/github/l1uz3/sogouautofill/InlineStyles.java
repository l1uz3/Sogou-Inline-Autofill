package io.github.l1uz3.sogouautofill;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;

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
 *
 * 渲染用的 Surface 是 PixelFormat.TRANSPARENT，所以 chip 背景可以是半透明的：
 * 原生模式下 chip 只是一层淡淡的色块，透出下面搜狗皮肤的背景。
 */
final class InlineStyles {
    private InlineStyles() {}

    /** chip 配色和字号。 */
    static final class Palette {
        final int chipBackground;
        final int title;
        final int subtitle;
        /** 图标着色，0 表示不着色（保留自动填充服务原本的图标颜色）。 */
        final int iconTint;
        final float titleSp;
        final float subtitleSp;
        /** 背景是否偏亮（文字是深色）。 */
        final boolean lightBackground;

        Palette(int chipBackground, int title, int subtitle, int iconTint,
                float titleSp, float subtitleSp, boolean lightBackground) {
            this.chipBackground = chipBackground;
            this.title = title;
            this.subtitle = subtitle;
            this.iconTint = iconTint;
            this.titleSp = titleSp;
            this.subtitleSp = subtitleSp;
            this.lightBackground = lightBackground;
        }

        /**
         * 原生配色：完全透明的 chip，文字/图标使用皮肤里候选词的颜色和字号，
         * 和搜狗剪贴板栏等原生内容一致。
         */
        static Palette fromSkin(int textColor, float titleSp) {
            int alpha = Color.alpha(textColor) == 0 ? 0xFF : Color.alpha(textColor);
            int opaque = (textColor & 0x00FFFFFF) | (alpha << 24);
            int sub = (opaque & 0x00FFFFFF) | (Math.round(alpha * 0.7f) << 24);
            return new Palette(Color.TRANSPARENT, opaque, sub, opaque, titleSp, titleSp * 0.8f,
                    luminance(opaque) < 0.5);
        }

        /** 读不到皮肤样式时：透明 chip，按工具栏亮度选黑/白字。 */
        static Palette translucent(boolean lightBackground) {
            int text = lightBackground ? 0xE6000000 : 0xF2FFFFFF;
            return fromSkin(text, 16);
        }

        /** 不透明配色：通用覆盖层（回退方案）使用。 */
        static Palette opaque(boolean night) {
            return night
                    ? new Palette(0xFF3C4043, 0xFFE8EAED, 0xFFBDC1C6, 0, 14, 12, false)
                    : new Palette(0xFFFFFFFF, 0xFF202124, 0xFF5F6368, 0, 14, 12, true);
        }

        /** 分隔线颜色（模仿搜狗剪贴板栏里的竖线）。 */
        int dividerColor() {
            return (title & 0x00FFFFFF) | (Math.round(Color.alpha(title) * 0.35f) << 24);
        }
    }

    static double luminance(int color) {
        return (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
    }

    static boolean isNight(Context ctx) {
        int mode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** chip 左右内边距（dp）。 */
    static final int CHIP_PADDING_DP = 8;

    static float spToPx(Context ctx, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, ctx.getResources().getDisplayMetrics());
    }

    static int dp(Context ctx, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    /**
     * 估计一个 View（搜狗工具栏）当前画面的亮度：缩小绘制到位图后取平均亮度。
     * 返回 null 表示 View 还没布局好。
     */
    static Boolean isLightBackground(View view) {
        int w = view.getWidth(), h = view.getHeight();
        if (w <= 0 || h <= 0) return null;
        final int scale = 8;
        int bw = Math.max(1, w / scale), bh = Math.max(1, h / scale);
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.scale(bw / (float) w, bh / (float) h);
            view.draw(c);
            int[] px = new int[bw * bh];
            bmp.getPixels(px, 0, bw, 0, 0, bw, bh);
            double sum = 0;
            int counted = 0;
            for (int p : px) {
                if (Color.alpha(p) < 16) continue; // 透明区域不计
                sum += 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p);
                counted++;
            }
            if (counted == 0) return null;
            return sum / counted > 140;
        } catch (Throwable t) {
            L.e("ime: failed to sample toolbar colors", t);
            return null;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    static Bundle build(Context ctx, Palette p) {
        ViewStyle chip = new ViewStyle.Builder()
                .setBackgroundColor(p.chipBackground)
                .setPadding(dp(ctx, CHIP_PADDING_DP), 0, dp(ctx, CHIP_PADDING_DP), 0)
                .build();

        int iconSize = dp(ctx, Math.max(18, Math.min(26, p.titleSp * 1.2f)));
        // 文字前的图标（Bitwarden 的地球图标）不显示：最大尺寸 0、无边距、透明着色
        ImageViewStyle startIcon = new ImageViewStyle.Builder()
                .setMaxWidth(0)
                .setMaxHeight(0)
                .setLayoutMargin(0, 0, 0, 0)
                .setPadding(0, 0, 0, 0)
                .setTintList(ColorStateList.valueOf(Color.TRANSPARENT))
                .build();
        // 只有图标的条目（例如 Bitwarden 的“打开密码库”）保留图标
        ImageViewStyle.Builder singleIconB = new ImageViewStyle.Builder()
                .setMaxWidth(iconSize)
                .setMaxHeight(iconSize);
        if (p.iconTint != 0) {
            singleIconB.setTintList(ColorStateList.valueOf(p.iconTint));
        }
        ImageViewStyle singleIcon = singleIconB.build();

        TextViewStyle title = new TextViewStyle.Builder()
                .setTextColor(p.title)
                .setTextSize(p.titleSp)
                .setLayoutMargin(0, 0, dp(ctx, 6), 0)
                .build();

        TextViewStyle subtitle = new TextViewStyle.Builder()
                .setTextColor(p.subtitle)
                .setTextSize(p.subtitleSp)
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

        Bundle bundle = UiVersions.newStylesBuilder().addStyle(style).build();
        // 给 system_server 的 TitleFit 用：名称太长时截短名称，保证用户名能显示出来
        int reserved = dp(ctx, CHIP_PADDING_DP) * 2 + dp(ctx, 6) + dp(ctx, 8);
        TitleFit.putParams(bundle, spToPx(ctx, p.titleSp), spToPx(ctx, p.subtitleSp), reserved);
        return bundle;
    }
}
