import UIKit

/// Shares displayed artwork across the native discovery screens.
@MainActor
final class DiscoveryArtworkCache {
    static let shared = DiscoveryArtworkCache()
    private static let decodedBytesLimit = 48 * 1024 * 1024

    private struct Request {
        let id: UUID
        let task: Task<UIImage?, Never>
        var consumers: Set<UUID>
    }

    private let decoded: NSCache<NSURL, UIImage>
    private var memoryWarning: NSObjectProtocol?
    private var requests: [URL: Request] = [:]

    private init() {
        let cache = NSCache<NSURL, UIImage>()
        cache.totalCostLimit = Self.decodedBytesLimit
        decoded = cache
        memoryWarning = NotificationCenter.default.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.decoded.removeAllObjects() }
        }
    }

    deinit {
        if let memoryWarning { NotificationCenter.default.removeObserver(memoryWarning) }
    }

    func image(for url: URL) -> UIImage? {
        decoded.object(forKey: url as NSURL)
    }

    func load(_ url: URL) async -> UIImage? {
        guard !Task.isCancelled else { return nil }
        if let image = image(for: url) { return image }

        let consumer = UUID()
        let task: Task<UIImage?, Never>
        if let current = requests[url] {
            requests[url]?.consumers.insert(consumer)
            task = current.task
        } else {
            let id = UUID()
            task = Task(priority: .userInitiated) { [self] in
                let image = await download(url)
                guard requests[url]?.id == id else { return nil }
                requests.removeValue(forKey: url)
                if let image, let pixels = image.cgImage {
                    let cost = pixels.bytesPerRow * pixels.height
                    if cost <= Self.decodedBytesLimit {
                        decoded.setObject(image, forKey: url as NSURL, cost: cost)
                    }
                }
                return image
            }
            requests[url] = Request(id: id, task: task, consumers: [consumer])
        }

        return await withTaskCancellationHandler {
            let image = await task.value
            return Task.isCancelled ? nil : image
        } onCancel: {
            Task { @MainActor in self.release(consumer, for: url) }
        }
    }

    private func release(_ consumer: UUID, for url: URL) {
        guard var request = requests[url], request.consumers.remove(consumer) != nil else { return }
        if request.consumers.isEmpty {
            requests.removeValue(forKey: url)
            request.task.cancel()
        } else {
            requests[url] = request
        }
    }

    private func download(_ url: URL) async -> UIImage? {
        guard let (data, response) = try? await URLSession.shared.data(from: url), !Task.isCancelled else {
            return nil
        }
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) { return nil }
        return UIImage(data: data)
    }
}
