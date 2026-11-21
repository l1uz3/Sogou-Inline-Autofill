package io.github.l1uz3.sogouautofill;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口（assets/xposed_init）。
 *
 * 需要同时作用在两个进程：
 *  1. system_server（作用域“系统框架 / android”）：让系统认为搜狗声明了 supportsInlineSuggestions，
 *     否则 InputMethodManagerService 根本不会向输入法索要 InlineSuggestionsRequest。
 *  2. 搜狗输入法进程：实现 onCreateInlineSuggestionsRequest / onInlineSuggestionsResponse，
 *     并把系统渲染好的建议条目显示在键盘上方。
 */
public class MainHook implements IXposedHookLoadPackage {

    /** 搜狗各渠道版的包名前缀（官方版、小米版、vivo 版等）。 */
    static final String SOGOU_PACKAGE_PREFIX = "com.sohu.inputmethod.sogou";

    static boolean isSogou(String packageName) {
        return packageName != null && packageName.startsWith(SOGOU_PACKAGE_PREFIX);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("android".equals(lpparam.packageName)) {
            SystemHooks.install(lpparam.classLoader);
        } else if (isSogou(lpparam.packageName)) {
            ImeHooks.install(lpparam);
        }
    }
}
