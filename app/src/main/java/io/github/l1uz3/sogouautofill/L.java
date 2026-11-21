package io.github.l1uz3.sogouautofill;

import de.robv.android.xposed.XposedBridge;

/** 日志统一输出到 LSPosed 日志，方便在管理器里查看。 */
final class L {
    private static final String TAG = "[SogouInlineAutofill] ";
    /** 调试时改为 true 可以看到更详细的流程日志（建议、尺寸、对齐等）。 */
    static final boolean VERBOSE = false;

    private L() {}

    static void i(String msg) {
        XposedBridge.log(TAG + msg);
    }

    static void d(String msg) {
        if (VERBOSE) XposedBridge.log(TAG + msg);
    }

    static void e(String msg, Throwable t) {
        XposedBridge.log(TAG + msg);
        if (t != null) XposedBridge.log(t);
    }
}
