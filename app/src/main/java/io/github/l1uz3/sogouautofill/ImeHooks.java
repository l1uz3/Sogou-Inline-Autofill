package io.github.l1uz3.sogouautofill;

import android.inputmethodservice.InputMethodService;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InlineSuggestionsResponse;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 搜狗输入法进程里的 Hook。
 *
 * 不依赖搜狗自身的类名/方法名（搜狗有加固和混淆，版本间也会变），只 hook 框架类 InputMethodService：
 *  1. 先 hook InputMethodService.onCreate()——任何输入法都必须调用 super.onCreate()，
 *     借此拿到搜狗真正的 InputMethodService 子类；
 *  2. 再在这个子类继承链上找到“最终生效”的那个方法声明去 hook。
 *     例如搜狗重写了 onComputeInsets 且没调用 super，只 hook 父类就会失效，
 *     所以必须 hook 虚分派实际会落到的那个类。
 */
final class ImeHooks {
    private static final String CONTROLLER_KEY = "sogou_inline_autofill_controller";
    private static final Set<Method> HOOKED = new HashSet<>();

    private ImeHooks() {}

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        L.i("ime: loaded into " + lpparam.packageName + " / " + lpparam.processName);
        try {
            XposedHelpers.findAndHookMethod(InputMethodService.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    InputMethodService ims = (InputMethodService) param.thisObject;
                    XposedHelpers.setAdditionalInstanceField(ims, CONTROLLER_KEY, new InlineController(ims));
                    hookServiceClass(ims.getClass());
                }
            });
        } catch (Throwable t) {
            L.e("ime: failed to hook InputMethodService.onCreate", t);
        }
    }

    static InlineController controllerOf(Object ims) {
        return (InlineController) XposedHelpers.getAdditionalInstanceField(ims, CONTROLLER_KEY);
    }

    private static synchronized void hookServiceClass(Class<?> cls) {
        L.i("ime: InputMethodService implementation = " + cls.getName());

        // 系统向输入法索要内联建议请求：返回我们构造的 InlineSuggestionsRequest
        hookEffective(cls, "onCreateInlineSuggestionsRequest", new Class<?>[]{Bundle.class},
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        InlineController c = controllerOf(param.thisObject);
                        return c == null ? null : c.onCreateRequest((Bundle) param.args[0]);
                    }
                });

        // 自动填充服务（Bitwarden）返回的建议
        hookEffective(cls, "onInlineSuggestionsResponse", new Class<?>[]{InlineSuggestionsResponse.class},
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        InlineController c = controllerOf(param.thisObject);
                        if (c == null) return false;
                        c.onResponse((InlineSuggestionsResponse) param.args[0]);
                        return true;
                    }
                });

        // 让键盘窗口的可触摸区域 / 内容区域把建议条也算进去
        hookEffective(cls, "onComputeInsets", new Class<?>[]{InputMethodService.Insets.class},
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        InlineController c = controllerOf(param.thisObject);
                        if (c != null) c.adjustInsets((InputMethodService.Insets) param.args[0]);
                    }
                });

        // 切换输入框 / 收起键盘时清掉旧的建议
        XC_MethodHook clear = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                InlineController c = controllerOf(param.thisObject);
                if (c != null) c.clear();
            }
        };
        hookEffective(cls, "onStartInputView", new Class<?>[]{EditorInfo.class, boolean.class}, clear);
        hookEffective(cls, "onFinishInputView", new Class<?>[]{boolean.class}, clear);
        hookEffective(cls, "onDestroy", new Class<?>[0], clear);
    }

    /** 在继承链上找到最先声明该方法的类（即虚分派实际调用的实现）并 hook。 */
    private static void hookEffective(Class<?> cls, String name, Class<?>[] types, XC_MethodHook hook) {
        for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
            Method m;
            try {
                m = k.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException e) {
                continue;
            } catch (Throwable t) {
                L.e("ime: cannot inspect " + k.getName() + "#" + name, t);
                continue;
            }
            if (!HOOKED.add(m)) return;
            try {
                XposedBridge.hookMethod(m, hook);
                L.d("ime: hooked " + k.getName() + "#" + name);
            } catch (Throwable t) {
                L.e("ime: failed to hook " + k.getName() + "#" + name, t);
            }
            return;
        }
        L.i("ime: method not found: " + name);
    }
}
