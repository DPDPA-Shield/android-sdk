package com.dpdpashield.sdk.reactnative

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

/**
 * Register in the host app's `MainApplication` (old-arch autolinking normally
 * does this automatically if this module is consumed via a real npm package
 * with an `android/build.gradle` autolinking hooks into - see
 * mobile/react-native-sdk/README.md for the manual-linking fallback):
 * `override fun getPackages() = PackageList(this).packages + ShieldConsentPackage()`
 */
class ShieldConsentPackage : ReactPackage {
    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> =
        listOf(ShieldConsentModule(reactContext))

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
        emptyList()
}
