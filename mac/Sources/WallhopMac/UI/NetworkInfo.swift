import Foundation

enum NetworkInfo {
    /// Every IPv4 address a phone might reach this Mac on, Wi‑Fi and Ethernet first.
    /// Skips loopback and self-assigned (169.254.x.x) addresses.
    static func localIPv4s() -> [String] {
        var addresses: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&addresses) == 0, let first = addresses else { return [] }
        defer { freeifaddrs(addresses) }
        var found: [(name: String, ip: String)] = []
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let interface = pointer.pointee
            guard interface.ifa_addr.pointee.sa_family == UInt8(AF_INET),
                  interface.ifa_flags & UInt32(IFF_UP) != 0,
                  interface.ifa_flags & UInt32(IFF_LOOPBACK) == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len),
                              &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let ip = String(cString: host)
            if ip.hasPrefix("169.254.") || found.contains(where: { $0.ip == ip }) { continue }
            found.append((String(cString: interface.ifa_name), ip))
        }
        return found.filter { $0.name.hasPrefix("en") }.map(\.ip) + found.filter { !$0.name.hasPrefix("en") }.map(\.ip)
    }
}

