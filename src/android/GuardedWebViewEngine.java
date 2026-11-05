package com.ochakov.plugins;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

import org.apache.cordova.CordovaPreferences;
import org.apache.cordova.engine.SystemWebView;
import org.apache.cordova.engine.SystemWebViewEngine;

/**
 * A SystemWebViewEngine that survives "failed to redirect ResourcesImpl".
 *
 * Loading the WebView provider makes Android add the WebView paths to every live Resources object
 * in the process. The first Android 16 release throws if one of those objects can no longer load
 * its APK, which happens when another package was updated while this process was running, for
 * example Play services under a headless process. AOSP fixed that in 814b239b, which shipped from
 * QPR1 on, but devices on the base release, such as Samsung's One UI 8.0, still have the throw.
 *
 * A second attempt gets past it, because the paths are recorded before the throw. A retry alone
 * is not enough: every Resources created after the failing one, the Activity's included, never got
 * the paths, so the WebView's own popups crash or do nothing later. A Resources that is rebuilt
 * from its key picks them up, and adding and removing an empty loader is the public way to do that.
 */
public class GuardedWebViewEngine extends SystemWebViewEngine {
    private static final String TAG = "GuardedWebViewEngine";
    private static final String REDIRECT_FAILURE = "failed to redirect ResourcesImpl";

    /** Used when created via reflection, see CordovaWebViewImpl.createEngine(). */
    public GuardedWebViewEngine(Context context, CordovaPreferences preferences) {
        super(createWebView(context), preferences);
    }

    private static SystemWebView createWebView(Context context) {
        try {
            return new SystemWebView(context);
        } catch (RuntimeException failure) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !isRedirectFailure(failure)) {
                throw failure;
            }
            Log.w(TAG, "Retrying the first WebView", failure);
            ResourcesRefresher.refresh(context.getApplicationContext().getResources());
            ResourcesRefresher.refresh(context.getResources());
            try {
                SystemWebView webView = new SystemWebView(context);
                report(failure);
                return webView;
            } catch (RuntimeException secondFailure) {
                secondFailure.addSuppressed(failure);
                throw secondFailure;
            }
        }
    }

    private static boolean isRedirectFailure(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof Resources.NotFoundException && REDIRECT_FAILURE.equals(t.getMessage())) {
                return true;
            }
        }
        return false;
    }

    /** Keeps the recovered failures visible in Crashlytics as non-fatals. */
    private static void report(RuntimeException failure) {
        try {
            FirebaseCrashlytics.getInstance().recordException(failure);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not report the recovered failure", e);
        }
    }

    /** A class of its own so that nothing older than API 30 ever has to resolve ResourcesLoader. */
    @RequiresApi(Build.VERSION_CODES.R)
    private static final class ResourcesRefresher {
        static void refresh(Resources resources) {
            try {
                ResourcesLoader loader = new ResourcesLoader();
                resources.addLoaders(loader);
                resources.removeLoaders(loader);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not refresh " + resources, e);
            }
        }
    }
}
