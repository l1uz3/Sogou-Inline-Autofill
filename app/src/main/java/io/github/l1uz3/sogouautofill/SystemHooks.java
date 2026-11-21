package io.github.l1uz3.sogouautofill;

import android.app.slice.Slice;
import android.service.autofill.InlinePresentation;
import android.view.inputmethod.InputMethodInfo;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * system_server 侧的 Hook。
 *
 * InputMethodManagerService（Android 14+ 为 AutofillSuggestionsController）在自动填充服务请求内联建议时会检查：
 * <pre>
 *     imi != null && imi.isInlineSuggestionsEnabled() && ...
 * </pre>
 * 这个值来自输入法 APK 里 input-method XML 的 android:supportsInlineSuggestions，搜狗没有声明，
 * 所以这里把搜狗对应的 InputMethodInfo 强制改成 true。
 *
 * 两手准备：
 *  - 构造函数后直接改字段 mInlineSuggestionsEnabled（不怕 getter 被 ART 内联）；
 *  - 同时 hook isInlineSuggestionsEnabled() 兜底。
 * InputMethodInfo 在开机时就被解析并缓存，所以启用/更新模块后需要重启手机。
 */
final class SystemHooks {
    private static final String FIELD = "mInlineSuggestionsEnabled";
    /** 内联建议里用户名（副标题）和条目名（标题）对调，用户名显示在前。对所有输入法生效。 */
    static final boolean SWAP_TITLE_AND_SUBTITLE = true;

    private SystemHooks() {}

    static void install(ClassLoader classLoader) {
        try {
            XposedBridge.hookAllConstructors(InputMethodInfo.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    enableIfSogou((InputMethodInfo) param.thisObject);
                }
            });
            L.i("system_server: hooked InputMethodInfo constructors");
        } catch (Throwable t) {
            L.e("system_server: failed to hook InputMethodInfo constructors", t);
        }

        // 用户名优先：自动填充服务返回的 InlinePresentation 在 system_server 里反序列化后、
        // 交给渲染服务之前，把 Slice 里的标题/副标题对调（见 SliceSwap）
        if (SWAP_TITLE_AND_SUBTITLE) {
            try {
                XposedBridge.hookAllConstructors(InlinePresentation.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Slice original = (Slice) XposedHelpers.getObjectField(param.thisObject, "mSlice");
                        Slice swapped = SliceSwap.swapTitleAndSubtitle(original);
                        if (swapped != original) {
                            XposedHelpers.setObjectField(param.thisObject, "mSlice", swapped);
                        }
                    }
                });
                L.i("system_server: hooked InlinePresentation (username first)");
            } catch (Throwable t) {
                L.e("system_server: failed to hook InlinePresentation", t);
            }
        }

        try {
            XposedHelpers.findAndHookMethod(InputMethodInfo.class, "isInlineSuggestionsEnabled",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            InputMethodInfo imi = (InputMethodInfo) param.thisObject;
                            if (MainHook.isSogou(safePackageName(imi))) {
                                param.setResult(true);
                            }
                        }
                    });
            L.i("system_server: hooked InputMethodInfo.isInlineSuggestionsEnabled");
        } catch (Throwable t) {
            L.e("system_server: failed to hook isInlineSuggestionsEnabled", t);
        }
    }

    private static void enableIfSogou(InputMethodInfo imi) {
        String pkg = safePackageName(imi);
        if (!MainHook.isSogou(pkg)) return;
        try {
            if (!XposedHelpers.getBooleanField(imi, FIELD)) {
                XposedHelpers.setBooleanField(imi, FIELD, true);
                L.d("system_server: forced supportsInlineSuggestions=true for " + imi.getId());
            }
        } catch (Throwable t) {
            L.e("system_server: cannot set " + FIELD, t);
        }
    }

    private static String safePackageName(InputMethodInfo imi) {
        try {
            return imi.getPackageName();
        } catch (Throwable t) {
            return null;
        }
    }
}
