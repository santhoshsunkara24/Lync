import AppKit
import QuickLookThumbnailing
import SwiftUI

/// Caches Quick Look thumbnails for received files.
final class ThumbnailCache {
    static let shared = ThumbnailCache()
    private let cache = NSCache<NSURL, NSImage>()

    func cached(_ url: URL) -> NSImage? { cache.object(forKey: url as NSURL) }

    func load(_ url: URL, size: CGFloat, completion: @escaping (NSImage?) -> Void) {
        if let image = cached(url) {
            completion(image)
            return
        }
        let request = QLThumbnailGenerator.Request(
            fileAt: url,
            size: CGSize(width: size, height: size),
            scale: NSScreen.main?.backingScaleFactor ?? 2,
            representationTypes: .thumbnail
        )
        QLThumbnailGenerator.shared.generateBestRepresentation(for: request) { [weak self] representation, _ in
            let image = representation?.nsImage
            if let image { self?.cache.setObject(image, forKey: url as NSURL) }
            DispatchQueue.main.async { completion(image ?? NSWorkspace.shared.icon(forFile: url.path)) }
        }
    }
}

struct Thumbnail: View {
    let url: URL
    var size: CGFloat = 36
    var corner: CGFloat = 7

    @State private var image: NSImage?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: corner, style: .continuous)
                .fill(Color.white.opacity(0.08))
            if let image {
                Image(nsImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: corner, style: .continuous)
                .strokeBorder(Color.white.opacity(0.12), lineWidth: 0.5)
        )
        .onAppear {
            image = ThumbnailCache.shared.cached(url)
            if image == nil {
                ThumbnailCache.shared.load(url, size: size * 2) { image = $0 }
            }
        }
        .onDrag { NSItemProvider(contentsOf: url) ?? NSItemProvider() }
        .help(url.lastPathComponent)
    }
}

/// Up to three thumbnails, fanned out like a small stack.
struct ThumbnailStack: View {
    let urls: [URL]
    var size: CGFloat = 34

    var body: some View {
        ZStack {
            ForEach(Array(urls.prefix(3).enumerated().reversed()), id: \.element) { index, url in
                Thumbnail(url: url, size: size)
                    .rotationEffect(.degrees(Double(index) * 7))
                    .offset(x: CGFloat(index) * 5)
            }
        }
        .frame(width: size + 12, height: size + 6)
    }
}
