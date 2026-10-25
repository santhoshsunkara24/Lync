import AppKit
import Foundation

/// Something that went wrong, said plainly: why in a few words (`title`), what to do in one line
/// (`detail`), and a button that does it when there is one. Every user-facing problem string lives
/// in this file, so the wording stays consistent and the phone's codes map in one place.
struct Problem: Equatable {
    let id = UUID()
    var symbol = "exclamationmark.circle.fill"
    let title: String
    var detail: String?
    var actionTitle: String?
    var action: (() -> Void)?

    static func == (a: Problem, b: Problem) -> Bool { a.id == b.id }

    /// The same problem with a (different) button.
    func with(_ title: String, _ action: @escaping () -> Void) -> Problem {
        var copy = self
        copy.actionTitle = title
        copy.action = action
        return copy
    }
}

/// A why and a what-to-do without a button, for places that show their own (bubbles, notes).
struct ProblemText: Equatable {
    let title: String
    var detail: String?
}

/// The phone's `err` codes (docs/PROTOCOL.md, shared with the phone app). A missing or unknown
/// code falls back to the generic message for that action.
enum PhoneError {
    // File acks (both directions).
    static let storageFull = "storage_full"
    static let checksum = "checksum"
    static let writeFailed = "write_failed"
    static let permission = "permission"
    static let cancelled = "cancelled"
    // Texts.
    static let textsOff = "texts_off"
    static let noService = "no_service"
    static let airplane = "airplane"
    static let badNumber = "bad_number"
    static let limit = "limit"
    static let contactsPermission = "contacts_permission"
    static let failed = "failed"
    // Notifications.
    static let accessOff = "access_off"
    static let mirroringOff = "mirroring_off"
    static let gone = "gone"
    static let noReplyAction = "no_reply_action"
    // Calls.
    static let callsOff = "calls_off"
    static let noCall = "no_call"
    // Links and text.
    static let notificationsBlocked = "notifications_blocked"
    // Pairing (pair_no).
    static let version = "version"
    static let rate = "rate"
    static let busy = "busy"
    static let expired = "expired"
    static let declined = "declined"
    static let mismatch = "mismatch"
}

/// What a file error on this Mac means for the user.
enum FileErrorKind: Error {
    case outOfSpace, noPermission, missing, other

    init(_ error: Error) {
        let ns = error as NSError
        let posix = (ns.userInfo[NSUnderlyingErrorKey] as? NSError).flatMap { $0.domain == NSPOSIXErrorDomain ? $0.code : nil }
            ?? (ns.domain == NSPOSIXErrorDomain ? ns.code : nil)
        if ns.domain == NSCocoaErrorDomain, ns.code == NSFileWriteOutOfSpaceError { self = .outOfSpace; return }
        if let posix, posix == Int(ENOSPC) || posix == Int(EDQUOT) { self = .outOfSpace; return }
        if ns.domain == NSCocoaErrorDomain, [NSFileWriteNoPermissionError, NSFileReadNoPermissionError].contains(ns.code) {
            self = .noPermission; return
        }
        if let posix, posix == Int(EACCES) || posix == Int(EPERM) { self = .noPermission; return }
        if ns.domain == NSCocoaErrorDomain, [NSFileNoSuchFileError, NSFileReadNoSuchFileError].contains(ns.code) {
            self = .missing; return
        }
        if let posix, posix == Int(ENOENT) { self = .missing; return }
        self = .other
    }

    /// The file-ack code the Mac sends the phone for this error.
    var ackCode: String {
        switch self {
        case .outOfSpace: return PhoneError.storageFull
        case .noPermission: return PhoneError.permission
        case .missing, .other: return PhoneError.writeFailed
        }
    }
}

/// Opens the right page of System Settings, falling back to the app itself.
enum SystemSettings {
    static let wifi = "x-apple.systempreferences:com.apple.wifi-settings-extension"
    static let localNetwork = "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork"
    static let bluetooth = "x-apple.systempreferences:com.apple.BluetoothSettings"
    static let bluetoothPrivacy = "x-apple.systempreferences:com.apple.preference.security?Privacy_Bluetooth"
    static let filesAndFolders = "x-apple.systempreferences:com.apple.preference.security?Privacy_FilesAndFolders"
    static let firewall = "x-apple.systempreferences:com.apple.preference.security?Firewall"
    static let storage = "x-apple.systempreferences:com.apple.settings.Storage"

    static func open(_ page: String) {
        if let url = URL(string: page), NSWorkspace.shared.open(url) { return }
        NSWorkspace.shared.open(URL(fileURLWithPath: "/System/Applications/System Settings.app"))
    }
}

/// The page with Lync for Android, for "Update Lync on your phone".
let lyncReleasesURL = URL(string: "https://github.com/santhoshsunkara24/Lync/releases")!

extension Problem {
    // MARK: Not connected

    /// An action that needs the phone, while it isn't connected. Over Bluetooth only, `wifiTitle`
    /// says Wi‑Fi is what's missing ("Files need Wi‑Fi").
    static func notConnected(bluetooth: Bool, wifiTitle: String) -> Problem {
        bluetooth
            ? Problem(symbol: "wifi.exclamationmark", title: wifiTitle, detail: "Join the same Wi‑Fi as your phone.")
            : Problem(symbol: "iphone.slash", title: "Phone not connected", detail: "Open Lync on your phone, same Wi‑Fi.")
    }

    static let macOffline = Problem(symbol: "wifi.slash", title: "This Mac is offline", detail: "Join Wi‑Fi.",
                                    actionTitle: "Open Wi‑Fi settings") { SystemSettings.open(SystemSettings.wifi) }

    static let localNetworkOff = Problem(symbol: "network.slash", title: "Local network access is off",
                                         detail: "Allow Lync to find your phone.", actionTitle: "Open Settings") {
        SystemSettings.open(SystemSettings.localNetwork)
    }

    /// The listener failed for another reason; `retry` starts it again.
    static func cantListen(retry: @escaping () -> Void) -> Problem {
        Problem(symbol: "exclamationmark.triangle.fill", title: "Can't open a connection",
                detail: "Try again, or restart your Mac.", actionTitle: "Try again", action: retry)
    }

    static func phoneNotConnected(_ name: String, waitedLong: Bool) -> ProblemText {
        ProblemText(title: "\(name) isn't connected",
                    detail: waitedLong ? "Check your phone is on this Mac's Wi‑Fi, not mobile data or a guest network."
                                       : "Open Lync on your phone. Both need the same Wi‑Fi.")
    }

    static let overBluetooth = ProblemText(title: "Connected over Bluetooth",
                                           detail: "Codes, calls and notifications only. Join the same Wi‑Fi for files and texts.")

    /// Help on buttons that are off while the phone isn't connected over Wi‑Fi.
    static func disabledHelp(bluetooth: Bool) -> String {
        bluetooth ? "Over Bluetooth: codes, calls and notifications only. Join the same Wi‑Fi for files and texts."
                  : "Open Lync on your phone, on the same Wi‑Fi as this Mac."
    }

    static let bluetoothOff = Problem(symbol: "antenna.radiowaves.left.and.right.slash", title: "Bluetooth is off",
                                      detail: "Turn it on so codes still arrive away from Wi‑Fi.", actionTitle: "Open Settings") {
        SystemSettings.open(SystemSettings.bluetooth)
    }

    static let bluetoothDenied = Problem(symbol: "antenna.radiowaves.left.and.right.slash", title: "Bluetooth not allowed",
                                         detail: "Allow Lync so codes still arrive away from Wi‑Fi.", actionTitle: "Open Settings") {
        SystemSettings.open(SystemSettings.bluetoothPrivacy)
    }

    /// Over Bluetooth for a while with no Wi‑Fi connection: the firewall is one likely cause.
    static let firewallMaybe = Problem(symbol: "flame", title: "Firewall may block Lync",
                                       detail: "Allow Lync in System Settings › Network › Firewall.", actionTitle: "Open") {
        SystemSettings.open(SystemSettings.firewall)
    }

    static func disconnected(_ name: String) -> String { "\(name) disconnected" }

    // MARK: Sending files

    /// Why one file didn't reach the phone.
    enum SendFailure: Equatable {
        case unreadable
        case refused(String?)
        case timedOut
        case disconnected
    }

    static func sendFailed(_ failure: SendFailure) -> ProblemText {
        switch failure {
        case .disconnected: return ProblemText(title: "Phone disconnected", detail: "Reconnect, then send again.")
        case .timedOut: return ProblemText(title: "Phone stopped answering", detail: "Check Lync is open on your phone, then try again.")
        case .unreadable: return ProblemText(title: "Lync can't open these files", detail: "Allow access in System Settings › Privacy & Security › Files and Folders.")
        case .refused(let code):
            switch code {
            case PhoneError.storageFull: return ProblemText(title: "Phone storage full", detail: "Free up space on your phone, then try again.")
            case PhoneError.permission: return ProblemText(title: "Phone declined the file", detail: "Open Lync on your phone and allow saving files.")
            case PhoneError.checksum: return ProblemText(title: "File got damaged on the way", detail: "Try again.")
            case PhoneError.cancelled: return ProblemText(title: "Cancelled on your phone", detail: "Send it again if you still want it.")
            case PhoneError.writeFailed: return ProblemText(title: "Phone couldn't save it", detail: "Try again.")
            default: return ProblemText(title: "Couldn't send to your phone", detail: "Try again.")
            }
        }
    }

    /// "2 of 3 sent": the first failure says why.
    static func partlySent(sent: Int, total: Int, firstFailedName: String, failure: SendFailure) -> ProblemText {
        if failure == .unreadable { return ProblemText(title: "\(sent) of \(total) sent", detail: "\(firstFailedName) couldn't be read.") }
        let reason = sendFailed(failure)
        return ProblemText(title: "\(sent) of \(total) sent", detail: [reason.title, reason.detail].compactMap { $0 }.joined(separator: ". "))
    }

    static let cantOpenFiles = Problem(symbol: "lock.doc", title: "Lync can't open these files",
                                       detail: "Allow access in System Settings › Privacy & Security › Files and Folders.",
                                       actionTitle: "Open") { SystemSettings.open(SystemSettings.filesAndFolders) }
    static let notDownloaded = Problem(symbol: "icloud.and.arrow.down", title: "Not downloaded yet",
                                       detail: "Download it in Finder first.")
    static func skippedUnreadable(_ count: Int) -> String {
        "Skipped \(count == 1 ? "1 file" : "\(count) files") Lync can't open"
    }
    static let foldersOnly = Problem(symbol: "folder.badge.minus", title: "Folders can't be sent", detail: "Drop the files inside instead.")
    static func skippedFolders(_ count: Int) -> String {
        "Skipped \(count == 1 ? "a folder" : "\(count) folders") · sending the files"
    }

    // MARK: Clipboard

    static let nothingToSend = Problem(symbol: "doc.on.clipboard", title: "Nothing to send",
                                       detail: "Copy text, a link, an image or files first.")
    static let imageNotSupported = Problem(symbol: "photo", title: "Image format not supported", detail: "Copy it as PNG or JPEG.")
    static let macStorageFull = Problem(symbol: "externaldrive.badge.exclamationmark", title: "Mac storage full",
                                        detail: "Free up space and try again.", actionTitle: "Open Storage") {
        SystemSettings.open(SystemSettings.storage)
    }
    static let couldntPrepare = Problem(title: "Couldn't prepare the file", detail: "Try again.")

    /// Text or a link the phone didn't take.
    static func textFailed(_ result: AckResult, what: String) -> Problem {
        switch result {
        case .refused(PhoneError.notificationsBlocked?):
            return Problem(symbol: "bell.slash", title: "\(what) not shown on phone", detail: "Allow notifications for Lync on your phone.")
        case .timedOut:
            return Problem(symbol: "iphone.slash", title: "Phone didn't answer", detail: "Keep Lync open on your phone and try again.")
        case .disconnected:
            return Problem(symbol: "iphone.slash", title: "Phone disconnected", detail: "Reconnect, then send again.")
        default:
            return Problem(title: "\(what) not sent", detail: "Try again.")
        }
    }

    // MARK: Notifications and calls

    static func replyFailed(_ result: AckResult) -> Problem {
        switch result {
        case .refused(PhoneError.gone?): return Problem(symbol: "bell.slash", title: "Notification is gone", detail: "Reply from your phone.")
        case .refused(PhoneError.accessOff?):
            return Problem(symbol: "bell.slash", title: "Notification access is off on your phone", detail: "Turn it on in Lync on your phone.")
        case .refused(PhoneError.mirroringOff?):
            return Problem(symbol: "bell.slash", title: "Notifications are off on your phone", detail: "Turn on Notifications in Lync on your phone.")
        case .refused(PhoneError.noReplyAction?): return Problem(symbol: "arrowshape.turn.up.left", title: "Can't reply to this one", detail: "Reply from your phone.")
        case .timedOut: return Problem(symbol: "iphone.slash", title: "Reply not confirmed", detail: "Check your phone before sending again.")
        case .disconnected: return Problem(symbol: "iphone.slash", title: "Reply not sent", detail: "Reconnect your phone, then try again.")
        default: return Problem(title: "Reply not sent", detail: "Try again.")
        }
    }

    static func actionFailed(_ result: AckResult) -> Problem {
        switch result {
        case .refused(PhoneError.gone?): return Problem(symbol: "bell.slash", title: "Notification is gone", detail: "It was cleared on your phone.")
        case .refused(PhoneError.accessOff?):
            return Problem(symbol: "bell.slash", title: "Notification access is off on your phone", detail: "Turn it on in Lync on your phone.")
        default: return Problem(symbol: "iphone.slash", title: "Couldn't reach phone", detail: "Try again.")
        }
    }

    /// Shown on the call card itself, so the card stays and says what to do instead.
    static func callFailed(_ action: String, _ result: AckResult) -> ProblemText {
        let onPhone = action == "answer" ? "Answer on your phone." : action == "decline" ? "Decline on your phone." : "End the call on your phone."
        switch result {
        case .refused(PhoneError.permission?): return ProblemText(title: "Couldn't answer from Mac", detail: "Allow Phone in Lync on your phone, or answer there.")
        case .refused(PhoneError.callsOff?): return ProblemText(title: "Calls are off on your phone", detail: onPhone)
        case .refused(PhoneError.noCall?): return ProblemText(title: "Call already ended", detail: nil)
        case .refused: return ProblemText(title: action == "answer" ? "Couldn't answer from Mac" : "Phone couldn't do that", detail: onPhone)
        default: return ProblemText(title: action == "answer" ? "Couldn't answer from Mac" : "Couldn't reach phone", detail: onPhone)
        }
    }

    static let clearFailed = Problem(symbol: "iphone.slash", title: "Couldn't reach phone", detail: "They may still be on your phone.")
    static let couldntRing = Problem(symbol: "bell.slash", title: "Couldn't ring phone", detail: "Check it's connected.")

    // MARK: Files from the phone

    /// Why files from the phone didn't (all) arrive, for the card and Recent.
    static func receiveFailed(_ code: String?) -> ProblemText {
        switch code {
        case PhoneError.storageFull: return ProblemText(title: "Mac storage full", detail: "Free up space, then send again from your phone.")
        case PhoneError.checksum: return ProblemText(title: "File arrived damaged", detail: "Send it again from your phone.")
        case PhoneError.permission: return ProblemText(title: "Lync can't save files", detail: "Restart Lync, then send again from your phone.")
        default: return ProblemText(title: "Transfer failed", detail: "Send it again from your phone.")
        }
    }

    static func interrupted(arrived: Int, expected: Int) -> ProblemText {
        ProblemText(title: "Phone disconnected · \(arrived) of \(expected) arrived",
                    detail: arrived == 0 ? "Send them again from your phone." : "Send the rest again.")
    }

    static func partlyArrived(arrived: Int, expected: Int) -> String { "\(arrived) of \(expected) arrived" }

    // MARK: Saving to Downloads

    static func saveFailed(_ kind: FileErrorKind, count: Int) -> Problem {
        switch kind {
        case .outOfSpace: return macStorageFull
        case .noPermission:
            return Problem(symbol: "lock.doc", title: "Lync can't open Downloads",
                           detail: "Allow it in System Settings › Privacy & Security › Files and Folders.", actionTitle: "Open") {
                SystemSettings.open(SystemSettings.filesAndFolders)
            }
        case .missing:
            return Problem(symbol: "clock.badge.xmark", title: "File expired",
                           detail: "Received files are kept for a day. Ask the phone to send it again.")
        case .other:
            return Problem(title: "Couldn't save \(count == 1 ? "1 file" : "\(count) files")", detail: "Try again.")
        }
    }

    static let expiredSubtitle = "Expired · not saved"

    // MARK: Pairing

    static let pairingExpired = Problem(symbol: "clock.badge.xmark", title: "Pairing request expired",
                                        detail: "Start again from your phone, or scan the code.")
    static let pairingCancelled = Problem(symbol: "iphone.slash", title: "Phone cancelled pairing",
                                          detail: "Start again from your phone, or scan the code.")
    static let qrExpired = Problem(symbol: "qrcode", title: "That code expired", detail: "Scan the new code.")
    static let qrMismatch = Problem(symbol: "qrcode", title: "That code didn't match", detail: "Scan the new code.")
    static let phoneTooOld = Problem(symbol: "arrow.down.app", title: "Phone app too old to pair",
                                     detail: "Update Lync on your phone, then scan again.", actionTitle: "Get Lync") {
        NSWorkspace.shared.open(lyncReleasesURL)
    }
    static let pairingNotSaved = Problem(symbol: "externaldrive.badge.exclamationmark", title: "Couldn't save pairing",
                                         detail: "Free up space; you may need to pair again.")
    static func unpairedThisMac(_ name: String) -> Problem {
        Problem(symbol: "iphone.slash", title: "\(name) unpaired this Mac", detail: "Pair again to use it with Lync.")
    }

    // MARK: Versions

    static let updatePhone = Problem(symbol: "arrow.down.app", title: "Update Lync on your phone",
                                     detail: "This Mac needs a newer version for Texts.", actionTitle: "Get Lync") {
        NSWorkspace.shared.open(lyncReleasesURL)
    }
    static let updateMac = Problem(symbol: "arrow.down.app", title: "Update Lync on this Mac",
                                   detail: "Your phone has a newer version.", actionTitle: "Get update") {
        NSWorkspace.shared.open(lyncReleasesURL)
    }

    // MARK: Settings

    static let hotkeyTaken = ProblemText(title: "⌥⌘L is taken by another app", detail: "Click the notch or menu bar icon instead.")

    static func loginItem(turningOn: Bool) -> ProblemText {
        turningOn
            ? ProblemText(title: "Couldn't turn on Open at login", detail: "Add Lync in System Settings › General › Login Items.")
            : ProblemText(title: "Couldn't turn off Open at login", detail: "Remove Lync in System Settings › General › Login Items.")
    }
    static let loginItemApproval = ProblemText(title: "Waiting for your approval", detail: "Allow Lync in System Settings › General › Login Items.")

    // MARK: Texts

    /// Why a text from the Mac wasn't sent (the phone's `sms_send` ack, or no ack).
    static func smsFailed(_ result: AckResult) -> ProblemText {
        switch result {
        case .refused(PhoneError.noService?), .refused(PhoneError.airplane?):
            return ProblemText(title: "No signal on phone", detail: "Try again when it has service.")
        case .refused(PhoneError.permission?): return ProblemText(title: "Texts not allowed on phone", detail: "In Lync on your phone, allow SMS.")
        case .refused(PhoneError.textsOff?): return ProblemText(title: "Texts are off on your phone", detail: "Turn on Texts in Lync on your phone.")
        case .refused(PhoneError.badNumber?): return ProblemText(title: "Check the number", detail: "Fix it, then send again.")
        case .refused(PhoneError.limit?): return ProblemText(title: "Carrier blocked this text", detail: "Send it from your phone instead.")
        case .timedOut: return ProblemText(title: "Not confirmed", detail: "Check your phone before sending again.")
        case .disconnected: return ProblemText(title: "Phone not connected", detail: "Reconnect, then try again.")
        default: return ProblemText(title: "Your phone couldn't send it", detail: "Try again.")
        }
    }

    /// The conversations or a conversation couldn't be loaded; `code` is the reply's `err`.
    static func textsUnavailable(_ code: String?) -> ProblemText {
        switch code {
        case PhoneError.permission: return ProblemText(title: "Allow texts on your phone", detail: "In Lync on your phone, tap Allow next to \u{201C}Texts on your Mac\u{201D}.")
        case PhoneError.textsOff: return ProblemText(title: "Texts are off on your phone", detail: "Turn on Texts in Lync on your phone.")
        default: return ProblemText(title: "Couldn't load texts", detail: "Try again.")
        }
    }

    static let textsNotConnected = ProblemText(title: "Phone not connected", detail: "Open Lync on your phone and keep it on the same Wi‑Fi.")
    static let textsNeedWifi = ProblemText(title: "Texts need Wi‑Fi", detail: "Join the same Wi‑Fi as this Mac.")
    static let textsTimedOut = ProblemText(title: "Phone didn't answer", detail: "Keep Lync open on your phone and try again.")
    static let textsOutdated = ProblemText(title: "Update Lync on your phone", detail: "Texts need the latest version.")
    static let conversationFailed = "Couldn't load this conversation"
    static let textsConnectHint = "Connect your phone to send texts: open Lync on it, on the same Wi‑Fi as this Mac."
    static let contactsFailed = ProblemText(title: "Couldn't search contacts", detail: "Type the number instead.")
    static let contactsPermission = ProblemText(title: "Allow Contacts for Texts on your phone to search names", detail: nil)
}
