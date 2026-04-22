import Foundation
import Capacitor

/// iOS stub for SilentUpdate.
///
/// The plugin's JS surface is intentionally cross-platform so apps can wire
/// it once and enable iOS without code changes when support lands. Until
/// then every method rejects with `unimplemented()`, which surfaces to
/// JavaScript as a standard Capacitor unimplemented error.
///
/// Tracking: https://github.com/svssdeva/cap-silent-update/issues (tag: ios)
@objc(SilentUpdatePlugin)
public class SilentUpdatePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "SilentUpdatePlugin"
    public let jsName = "SilentUpdate"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "getState", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setLastCheckTs", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkManifest", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "downloadUpdate", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "applyNow", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "notifyReady", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "rollback", returnType: CAPPluginReturnPromise),
    ]

    private static let iosMessage = "SilentUpdate: iOS support not yet implemented. Follow https://github.com/svssdeva/cap-silent-update"

    @objc func getState(_ call: CAPPluginCall) {
        call.unimplemented(SilentUpdatePlugin.iosMessage)
    }

    @objc func setLastCheckTs(_ call: CAPPluginCall) {
        call.unimplemented(SilentUpdatePlugin.iosMessage)
    }

    @objc func checkManifest(_ call: CAPPluginCall) {
        call.unimplemented(SilentUpdatePlugin.iosMessage)
    }

    @objc func downloadUpdate(_ call: CAPPluginCall) {
        call.unimplemented(SilentUpdatePlugin.iosMessage)
    }

    @objc func applyNow(_ call: CAPPluginCall) {
        call.unimplemented(SilentUpdatePlugin.iosMessage)
    }

    @objc func notifyReady(_ call: CAPPluginCall) {
        call.unimplemented(SilentUpdatePlugin.iosMessage)
    }

    @objc func rollback(_ call: CAPPluginCall) {
        call.unimplemented(SilentUpdatePlugin.iosMessage)
    }

    /// No-op mirror of Android's static `prepareBoot(Context)`. Present so
    /// consumers can wire a single call-site pattern in AppDelegate without
    /// platform conditionals once iOS support ships.
    @objc public static func prepareBoot() {
        // intentionally empty until iOS support lands
    }
}
