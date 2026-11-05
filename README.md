# cordova-plugin-webview-startup-guard

Keeps a Cordova Android app alive when the first Android 16 release fails to load the WebView
provider with `failed to redirect ResourcesImpl`.

## The problem

```
RuntimeException: Unable to start activity … MainActivity: RuntimeException: Failed to create webview.
Caused by: AndroidRuntimeException: Resources$NotFoundException: failed to redirect ResourcesImpl
    at android.webkit.WebViewFactory.getProvider
Caused by: Resources$NotFoundException: failed to redirect ResourcesImpl
    at android.app.ResourcesManager.redirectAllResourcesToNewImplLocked
    at android.app.ResourcesManager.appendLibAssetsLocked
    at android.app.ResourcesManager.registerResourcePaths
    at android.webkit.WebViewFactory.getProviderClass
```

When a process loads the WebView for the first time, Android adds the WebView paths to every live
`Resources` object in it. The first Android 16 release throws if one of those objects can no longer
load its APK. AOSP changed that in `814b239b` ("Fix registerResourcePaths crash with bad path") to
skip the object and carry on. The fix shipped from QPR1 on, so it reached Pixels quickly, but
devices on the base release still have the throw, most of all Samsung's One UI 8.0.

An app that runs in the background is the usual victim:

1. A push, a geofence or boot starts the process with no Activity. Firebase Analytics loads its
   dynamite module, which creates a `Resources` object for Google Play services with
   `getResourcesForApplication`. That records no package dependency.
2. Play services updates. Android does not kill the process, and the old `base.apk` is deleted.
3. The user opens the app. The first WebView in that process throws, and so the app dies.

The next launch works, so the crash looks random. Any package whose `Resources` is still alive in
the process can trigger it, not only Play services.

## What the plugin does

It sets the `webview` preference to `GuardedWebViewEngine`, a `SystemWebViewEngine` that creates its
`SystemWebView` like this:

- Any failure other than this one is rethrown unchanged, and so is any failure below API 30.
- It refreshes the `Resources` of the Application and of the Activity.
- It creates the WebView once more. If that also fails, the app crashes as it would have, with the
  first failure attached as suppressed.
- After a success it records the first failure in Firebase Crashlytics as a non-fatal, so the
  problem stays visible.

Nothing else changes: on every other device and on a normal start the engine is plain
`SystemWebViewEngine`.

## Why the refresh matters

A retry on its own starts the app and then breaks it. Android records the WebView paths before it
throws, so the second attempt gets past the failure, but every `Resources` created after the
failing one never received them, and that includes the Activity's. On the emulator a `<select>`
then crashed with `Resources$NotFoundException` in 4 of 5 attempts, and long-press selection stopped
working. It does not fail every time, so a quick manual test can pass.

Android rebuilds a `Resources` from its key when the set of registered paths has changed, and adding
and removing an empty `ResourcesLoader` is the public way to make it do that.

## Install

```
cordova plugin add https://github.com/ochakov/cordova-plugin-webview-startup-guard.git
```

The app needs Firebase Crashlytics on its classpath. There is no JavaScript API.

If the app obfuscates with R8, keep `com.ochakov.plugins.GuardedWebViewEngine` and its constructor,
because Cordova creates the engine by name.

Do not set the `webview` preference in `config.xml` as well, or use another plugin that sets it,
such as an alternative WebView engine.

## Testing it

This needs a framework that still has the throw: an Android 16 emulator image of the `BE2A` line
does, for example `BE2A.250530.026.F3`. Turn on airplane mode first, because a test crash goes to
the real Crashlytics project.

1. Start the app headless and keep it alive with a foreground service. A cached process is evicted
   during the next step, which looks as if the update had killed it.
2. Reinstall Play services from its own APKs: `adb install-multiple -r -d --no-incremental *.apk`.
   `--no-incremental` matters for any test package too: an incremental install leaves the old file
   "up to date" and nothing fails.
3. Open the app. Logcat shows `failed to add asset path` with the deleted APK, then
   `Retrying the first WebView`.
4. Try a `<select>`, a date and a time input, long-press selection and `alert()`. Check that the
   popup really appears: not crashing is not enough.
