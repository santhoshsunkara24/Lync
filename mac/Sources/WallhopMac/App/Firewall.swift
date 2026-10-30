import Foundation

/// The macOS application firewall, asked through its own tool (no administrator rights needed
/// to read it): whether it's on and keeping other devices from reaching Lync.
enum Firewall {
    private static let tool = "/usr/libexec/ApplicationFirewall/socketfilterfw"

    /// True only when the firewall is on and blocks everything incoming, or blocks Lync itself.
    /// Runs the tool, so call it off the main thread. False when it can't be read.
    static func blocksLync() -> Bool {
        guard run("--getglobalstate").contains("enabled") else { return false }
        if run("--getblockall").contains("enabled") { return true }
        guard let executable = Bundle.main.executablePath else { return false }
        return run("--getappblocked", executable).contains("blocked")
    }

    private static func run(_ arguments: String...) -> String {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: tool)
        process.arguments = arguments
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = Pipe()
        guard (try? process.run()) != nil else { return "" }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return String(decoding: data, as: UTF8.self).lowercased()
    }
}
