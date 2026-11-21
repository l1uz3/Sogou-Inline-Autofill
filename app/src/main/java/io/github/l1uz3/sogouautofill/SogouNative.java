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

    /** 工具栏右侧按钮组件：平时画“∨”（收起键盘），剪贴板栏出现时画“×”。显示建议时一并隐藏，由我们自己画 ×。 */
    private static final String RIGHT_BUTTON_CLASS = "com.sohu.inputmethod.sogou.l1";

    /** 皮肤里候选词的文字样式：k.m0("CandidateWordView").x0() → style.j0() 颜色 / style.p0() 字号。 */
    private static final String THEME_VIEW_CLASS = "com.sogou.theme.data.view.k";
    private static final String CANDIDATE_WORD_VIEW = "CandidateWordView";
    /** 键盘缩放：resize.b.a.a().I(size) 把皮肤字号换算成实际像素（与搜狗剪贴板栏一致）。 */
    private static final String RESIZE_CLASS = "com.sogou.imskit.core.ui.keyboard.resize.b";

    static volatile boolean ready;
    static Class<?> toolbarClass;
    private static ClassLoader classLoader;
    private static Class<?> functionClass;
    private static Class<?> rightButtonClass;
    private static Method getVisibility;
    private static volatile WeakReference<Object> functionView = new WeakReference<>(null);
    /** 为 true 时不绘制工具栏图标组件。 */
    static volatile boolean suppressFunctionDraw;
    /** 当前的 InlineController（输入法只有一个实例）。 */
    static volatile InlineController controller;

    private SogouNative() {}

    static void install(ClassLoader cl) {
        classLoader = cl;
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
                    if (suppressFunctionDraw && isSuppressed(param.thisObject)) {
                        param.setResult(null);
                    }
                }
            };
            boolean a = ImeHooks.hookEffective(functionClass, DRAW_CONTENT, new Class<?>[]{Canvas.class}, skipDraw);
            boolean b = ImeHooks.hookEffective(functionClass, DRAW_CHILDREN, new Class<?>[]{Canvas.class}, skipDraw);
            if (!a || !b) throw new NoSuchMethodException("draw methods not found");

            // 右侧按钮是可选的：找不到只是会残留一个“∨”，不影响其他功能
            try {
                rightButtonClass = XposedHelpers.findClass(RIGHT_BUTTON_CLASS, cl);
                ImeHooks.hookEffective(rightButtonClass, DRAW_CONTENT, new Class<?>[]{Canvas.class}, skipDraw);
                ImeHooks.hookEffective(rightButtonClass, DRAW_CHILDREN, new Class<?>[]{Canvas.class}, skipDraw);
            } catch (Throwable t) {
                rightButtonClass = null;
                L.e("ime: right toolbar button not found", t);
            }

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

    private static boolean isSuppressed(Object component) {
        return functionClass.isInstance(component)
                || (rightButtonClass != null && rightButtonClass.isInstance(component));
    }

    /** 皮肤里候选词的文字样式。 */
    static final class SkinText {
        final int color;
        final int sizePx;

        SkinText(int color, int sizePx) {
            this.color = color;
            this.sizePx = sizePx;
        }

        @Override
        public String toString() {
            return String.format("color=#%08X size=%dpx", color, sizePx);
        }
    }

    /** 读取当前皮肤的候选词颜色和字号（与搜狗剪贴板栏的取法一致）。失败返回 null。 */
    static SkinText candidateTextStyle() {
        if (classLoader == null) return null;
        try {
            Class<?> themeView = XposedHelpers.findClass(THEME_VIEW_CLASS, classLoader);
            Object view = themeView.getMethod("m0", String.class).invoke(null, CANDIDATE_WORD_VIEW);
            if (view == null) return null;
            Object style = view.getClass().getMethod("x0").invoke(view);
            if (style == null) return null;
            int color = (Integer) style.getClass().getMethod("j0").invoke(style);
            int size = (Integer) style.getClass().getMethod("p0").invoke(style);
            try {
                Class<?> resize = XposedHelpers.findClass(RESIZE_CLASS, classLoader);
                Object inst = resize.getField("a").get(null);
                Object data = resize.getMethod("a").invoke(inst);
                size = (Integer) data.getClass().getMethod("I", int.class).invoke(data, size);
            } catch (Throwable t) {
                L.e("ime: cannot scale skin text size, using raw value", t);
            }
            return new SkinText(color, size);
        } catch (Throwable t) {
            L.e("ime: cannot read skin candidate text style", t);
            return null;
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
