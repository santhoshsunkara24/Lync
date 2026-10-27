import Foundation

/// Development switches (`WALLHOP_*` environment variables), read only in debug and snapshot
/// builds. A release build ignores them all, so nothing set in its environment can make it accept
/// pairing by itself, keep its keys elsewhere, log, or let codes show in screen recordings.
enum DevEnv {
    static func value(_ name: String) -> String? {
        #if DEBUG || SNAPSHOTS
        return ProcessInfo.processInfo.environment[name]
        #else
        return nil
        #endif
    }

    static func on(_ name: String) -> Bool { value(name) == "1" }
}
