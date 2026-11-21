package io.github.l1uz3.sogouautofill;

import android.content.Context;
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

    /** chip 配色。 */
    static final class Palette {
        final int chipBackground;
        final int title;
        final int subtitle;
        /** 背景是否偏亮（决定文字用深色还是浅色）。 */
        final boolean lightBackground;

        Palette(int chipBackground, int title, int subtitle, boolean lightBackground) {
            this.chipBackground = chipBackground;
            this.title = title;
            this.subtitle = subtitle;
            this.lightBackground = lightBackground;
        }

        /** 半透明配色：适合直接叠在搜狗皮肤上。 */
        static Palette translucent(boolean lightBackground) {
            return lightBackground
                    ? new Palette(0x1A000000, 0xE6000000, 0x99000000, true)
                    : new Palette(0x26FFFFFF, 0xF2FFFFFF, 0xB3FFFFFF, false);
        }

        /** 不透明配色：通用覆盖层（回退方案）使用。 */
        static Palette opaque(boolean night) {
            return night
                    ? new Palette(0xFF3C4043, 0xFFE8EAED, 0xFFBDC1C6, false)
                    : new Palette(0xFFFFFFFF, 0xFF202124, 0xFF5F6368, true);
        }
    }

    static boolean isNight(Context ctx) {
        int mode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
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
                .setTextColor(p.title)
                .setTextSize(14)
                .setLayoutMargin(0, 0, dp(ctx, 4), 0)
                .build();

        TextViewStyle subtitle = new TextViewStyle.Builder()
                .setTextColor(p.subtitle)
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
