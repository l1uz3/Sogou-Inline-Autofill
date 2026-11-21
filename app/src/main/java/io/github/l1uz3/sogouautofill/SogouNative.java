package io.github.l1uz3.sogouautofill;

import android.graphics.Canvas;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 搜狗 20.10.0 (2550) 专用的“原生集成”适配层（通过反编译确认的结构）。
 *
 * 搜狗键盘顶部那一行的结构：
 * <pre>
 * com.sogou.core.ui.view.Toolbar (FrameLayout, id=bae)
 *   └─ IMEInputCandidateViewContainer (RelativeLayout，绘制皮肤里的工具栏背景)
 *        └─ ImeInputCandidateRootView (RootComponentView，搜狗自研的虚拟组件框架)
 *             ├─ NewIMEFunctionCandidateView  —— 空闲时的工具栏图标（组件，不是 View）
 *             └─ NewCandidateView             —— 打字时的候选词（组件）
 * </pre>
 * 搜狗用 Component.y2(0/4/8) 在“工具栏图标”和“候选词”之间切换，Component.x1() 读可见性；
 * NewIMEFunctionCandidateView.N1(Canvas) 画图标，Component.L0(Canvas) 画子组件。
 *
 * 集成方式：
 *  - 建议栏作为透明 View 加进 Toolbar，盖在候选区上；
 *  - 显示建议时跳过工具栏图标组件的 N1/L0 绘制 —— 皮肤背景照常由搜狗绘制，看起来就像搜狗原生显示的建议；
 *  - 工具栏图标组件不可见（= 搜狗正在显示候选词）时自动让位。
 *
 * 类名 Toolbar / NewIMEFunctionCandidateView 是搜狗保留的（未混淆），但 x1 / N1 / L0 是混淆名，
 * 换版本可能失效：任何一步找不到就 ready=false，InlineController 会退回通用的覆盖层方案。
 */
final class SogouNative {
    static final String TOOLBAR_CLASS = "com.sogou.core.ui.view.Toolbar";
    static final String FUNCTION_VIEW_CLASS = "com.sohu.inputmethod.sogou.NewIMEFunctionCandidateView";
    private static final String GET_VISIBILITY = "x1";   // Component.x1(): int  (visibility)
    private static final String DRAW_CONTENT = "N1";     // Component.N1(Canvas) (onDraw)
    private static final String DRAW_CHILDREN = "L0";    // Component.L0(Canvas) (dispatchDraw)

    static volatile boolean ready;
    static Class<?> toolbarClass;
    private static Class<?> functionClass;
    private static Method getVisibility;
    private static volatile WeakReference<Object> functionView = new WeakReference<>(null);
    /** 为 true 时不绘制工具栏图标组件。 */
    static volatile boolean suppressFunctionDraw;
    /** 当前的 InlineController（输入法只有一个实例）。 */
    static volatile InlineController controller;

    private SogouNative() {}

    static void install(ClassLoader cl) {
        try {
            toolbarClass = XposedHelpers.findClass(TOOLBAR_CLASS, cl);
            functionClass = XposedHelpers.findClass(FUNCTION_VIEW_CLASS, cl);

            getVisibility = functionClass.getMethod(GET_VISIBILITY);
            if (getVisibility.getReturnType() != int.class) {
                throw new NoSuchMethodException(GET_VISIBILITY + " has unexpected return type");
            }

            XposedBridge.hookAllConstructors(functionClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    functionView = new WeakReference<>(param.thisObject);
                }
            });

            XC_MethodHook skipDraw = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (suppressFunctionDraw && functionClass.isInstance(param.thisObject)) {
                        param.setResult(null);
                    }
                }
            };
            boolean a = ImeHooks.hookEffective(functionClass, DRAW_CONTENT, new Class<?>[]{Canvas.class}, skipDraw);
            boolean b = ImeHooks.hookEffective(functionClass, DRAW_CHILDREN, new Class<?>[]{Canvas.class}, skipDraw);
            if (!a || !b) throw new NoSuchMethodException("draw methods not found");

            // Toolbar.setCandidatesView 会 removeAllViews()，建议栏要重新挂上
            XposedHelpers.findAndHookMethod(toolbarClass, "setCandidatesView", View.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    suppressFunctionDraw = false;
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    InlineController c = controller;
                    if (c != null) c.onToolbarContentReplaced();
                }
            });

            ready = true;
            L.i("ime: native Sogou toolbar integration ready");
        } catch (Throwable t) {
            ready = false;
            L.e("ime: native Sogou toolbar integration unavailable, falling back to overlay", t);
        }
    }

    /** 工具栏图标组件是否处于可见状态（不可见 = 搜狗正在显示候选词等）。未知时返回 true。 */
    static boolean isFunctionViewVisible() {
        Object fv = functionView.get();
        if (fv == null || getVisibility == null) return true;
        try {
            return (Integer) getVisibility.invoke(fv) == View.VISIBLE;
        } catch (Throwable t) {
            return true;
        }
    }
}
