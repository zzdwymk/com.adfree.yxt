package com.adfree.yxt;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 乐校通去广告 —— 基于 LibXposed(现代 Xposed API 102)。
 * 加固原因:onPackageLoaded 时真实类未解密,
 * 延迟到 Instrumentation.callApplicationOnCreate / Activity.onCreate 拿到运行时真实类加载器后再挂钩。
 */
public class Main extends XposedModule {
    private static final String PKG = "client.android.yixiaotong";
    private static final String ADV = "client.android.yixiaotong.v3.ui.adv.";
    private static final String TAG = "AdFree-YXT";
    private static final Uri LOG_URI = Uri.parse("content://com.adfree.yxt.logs");

    private static Context appCtx;
    private volatile boolean installed = false;

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!PKG.equals(param.getPackageName())) return;
        log(Log.INFO, TAG, "attached; deferring hooks until real dex decrypted");

        try {
            Method mApp = android.app.Instrumentation.class
                    .getDeclaredMethod("callApplicationOnCreate", Application.class);
            hook(mApp).intercept(chain -> {
                Object r = chain.proceed();
                tryInstallFrom(chain.getArg(0));
                return r;
            });
        } catch (Throwable t) { log(Log.ERROR, TAG, "instr hook fail", t); }

        try {
            Method mAct = android.app.Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            hook(mAct).intercept(chain -> {
                tryInstallFrom(chain.getThisObject());
                return chain.proceed();
            });
        } catch (Throwable t) { log(Log.ERROR, TAG, "activity hook fail", t); }
    }

    private void tryInstallFrom(Object ctx) {
        if (installed || !(ctx instanceof Context)) return;
        appCtx = ((Context) ctx).getApplicationContext();
        install(((Context) ctx).getClassLoader());
    }

    private synchronized void install(ClassLoader cl) {
        if (installed || cl == null) return;
        Class<?> advCtl;
        try { advCtl = cl.loadClass(ADV + "AdvControlUtil"); }
        catch (Throwable e) { return; }   // 真实类还没解密,等下一次回调
        installed = true;
        log(Log.INFO, TAG, "real classloader ready -> installing hooks");

        // 1) 总控:强制四类广告开关 false(开屏走 -1 跳过主页)
        try {
            hook(advCtl.getDeclaredMethod("isOpenAdv")).intercept(chain -> {
                Object t = chain.getThisObject();
                setBool(t, "mIsOpenSplashAdv", false);
                setBool(t, "mIsOpenInsertAdv", false);
                setBool(t, "mIsOpenBannerAdv", false);
                setBool(t, "mIsOpenNativeAdv", false);
                logEvent("开屏");
                return null;
            });
            log(Log.INFO, TAG, "OK AdvControlUtil.isOpenAdv");
        } catch (Throwable e) { log(Log.ERROR, TAG, "FAIL isOpenAdv", e); }

        // 2) v4 开屏门 -> false
        try {
            Method m = cl.loadClass("client.android.yixiaotong.v4.util.homeinfo.V4HomeInfoUtil")
                    .getDeclaredMethod("isOpenAdv");
            hook(m).intercept(chain -> Boolean.FALSE);
        } catch (Throwable e) { log(Log.ERROR, TAG, "FAIL V4HomeInfoUtil.isOpenAdv", e); }

        // 3) 开屏兜底:onSplash 被调用则立即回调 onAdClosed 进主页
        try {
            Method m = cl.loadClass(ADV + "SplashUtil").getDeclaredMethod("onSplash", FrameLayout.class);
            hook(m).intercept(chain -> {
                try {
                    Object self = chain.getThisObject();
                    Field lf = self.getClass().getDeclaredField("mAdvListener");
                    lf.setAccessible(true);
                    Object listener = lf.get(self);
                    if (listener != null) {
                        Class<?> at = cl.loadClass(ADV + "Common$AdvType");
                        Object advsplash = at.getField("advsplash").get(null);
                        Method onClosed = listener.getClass().getMethod("onAdClosed", at);
                        onClosed.setAccessible(true);
                        onClosed.invoke(listener, advsplash);
                    }
                } catch (Throwable e) { log(Log.ERROR, TAG, "splash skip fail", e); }
                return null;
            });
        } catch (Throwable e) { log(Log.ERROR, TAG, "FAIL SplashUtil.onSplash", e); }

        // 4) 插屏 / 信息流 / banner 入口 no-op + 记录
        nop(cl, ADV + "InsertUtil", "onInsert", "插屏");
        nop(cl, ADV + "ShouYeInsertUtil", "onInsert", "首页插屏");
        nop(cl, ADV + "NativeUtil", "onNative", "信息流", RelativeLayout.class);
        nop(cl, ADV + "ShouYeNativeUtil", "onNative", "首页信息流", RelativeLayout.class);
        nop(cl, ADV + "BannerUtil", "onBanner", "banner", FrameLayout.class);
    }

    private void nop(ClassLoader cl, String cls, String method, String label, Class<?>... params) {
        try {
            Method m = cl.loadClass(cls).getDeclaredMethod(method, params);
            hook(m).intercept(chain -> { logEvent(label); return null; });
            log(Log.INFO, TAG, "OK nop " + cls + "." + method);
        } catch (Throwable e) { log(Log.ERROR, TAG, "FAIL nop " + cls + "." + method, e); }
    }

    private static void setBool(Object o, String field, boolean v) {
        try {
            Field f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.setBoolean(o, v);
        } catch (Throwable ignore) {}
    }

    // 通过模块的 ContentProvider 写日志(无需 root)
    static void logEvent(String type) {
        try {
            Context c = appCtx;
            if (c == null) return;
            ContentValues v = new ContentValues();
            v.put("type", type);
            c.getContentResolver().insert(LOG_URI, v);
        } catch (Throwable ignore) {}
    }
}
