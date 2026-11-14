import SwiftUI
import UIKit

/// Blättern mit `UIPageViewController`: "Buchseiten" nutzt das eingebaute Umblättern (die Seite
/// folgt dem Finger und biegt sich dort, wo man sie greift), "Slideshow" schiebt die Seiten.
struct PagerView: UIViewControllerRepresentable {
    let pageCount: Int
    let initialIndex: Int
    let curl: Bool
    let palette: ReaderPalette
    let darkPaper: Bool
    let provider: @MainActor (Int) async -> PageVisual?
    let onSettled: @MainActor (Int) -> Void
    let onTapCenter: @MainActor () -> Void
    let onTurn: @MainActor () -> Void
    @Binding var seek: Int?

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIViewController(context: Context) -> UIPageViewController {
        let pager = UIPageViewController(
            transitionStyle: curl ? .pageCurl : .scroll,
            navigationOrientation: .horizontal,
            options: curl ? [.spineLocation: NSNumber(value: UIPageViewController.SpineLocation.min.rawValue)] : nil
        )
        pager.isDoubleSided = false
        pager.dataSource = context.coordinator
        pager.delegate = context.coordinator
        pager.view.backgroundColor = palette.backdrop
        let start = min(max(0, initialIndex), max(0, pageCount - 1))
        context.coordinator.current = start
        pager.setViewControllers([context.coordinator.controller(start)], direction: .forward, animated: false)
        // Eigene Blätter-Zonen statt der eingebauten Tipp-Erkennung.
        for recognizer in pager.gestureRecognizers where recognizer is UITapGestureRecognizer {
            recognizer.isEnabled = false
        }
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        pager.view.addGestureRecognizer(tap)
        context.coordinator.pager = pager
        return pager
    }

    func updateUIViewController(_ pager: UIPageViewController, context: Context) {
        context.coordinator.parent = self
        if let target = seek {
            DispatchQueue.main.async { seek = nil }
            context.coordinator.go(to: target, animated: false)
        }
    }

    @MainActor
    final class Coordinator: NSObject, UIPageViewControllerDataSource, UIPageViewControllerDelegate {
        var parent: PagerView
        weak var pager: UIPageViewController?
        var current = 0
        private var animating = false

        init(_ parent: PagerView) { self.parent = parent }

        func controller(_ index: Int) -> PageContentController {
            PageContentController(index: index, parent: parent)
        }

        func pageViewController(_ pageViewController: UIPageViewController, viewControllerBefore viewController: UIViewController) -> UIViewController? {
            guard let page = viewController as? PageContentController, page.index > 0 else { return nil }
            return controller(page.index - 1)
        }

        func pageViewController(_ pageViewController: UIPageViewController, viewControllerAfter viewController: UIViewController) -> UIViewController? {
            guard let page = viewController as? PageContentController, page.index + 1 < parent.pageCount else { return nil }
            return controller(page.index + 1)
        }

        func pageViewController(_ pageViewController: UIPageViewController, willTransitionTo pendingViewControllers: [UIViewController]) {
            parent.onTurn()
        }

        func pageViewController(_ pageViewController: UIPageViewController, didFinishAnimating finished: Bool,
                                previousViewControllers: [UIViewController], transitionCompleted completed: Bool) {
            guard completed, let page = pageViewController.viewControllers?.first as? PageContentController else { return }
            current = page.index
            parent.onSettled(page.index)
        }

        @objc func tapped(_ recognizer: UITapGestureRecognizer) {
            guard let view = recognizer.view else { return }
            let x = recognizer.location(in: view).x
            let width = view.bounds.width
            if x < width * 0.25 {
                turn(by: -1)
            } else if x > width * 0.75 {
                turn(by: 1)
            } else {
                parent.onTapCenter()
            }
        }

        private func turn(by delta: Int) {
            let target = current + delta
            guard !animating, target >= 0, target < parent.pageCount else { return }
            parent.onTurn()
            go(to: target, animated: true)
        }

        func go(to target: Int, animated: Bool) {
            guard let pager, target >= 0, target < parent.pageCount, target != current || !animated else { return }
            let direction: UIPageViewController.NavigationDirection = target >= current ? .forward : .reverse
            animating = animated
            current = target
            pager.setViewControllers([controller(target)], direction: direction, animated: animated) { [weak self] _ in
                self?.animating = false
            }
            parent.onSettled(target)
        }
    }
}

/// Eine Seite im Pager: Blatt Papier mit der gerenderten Seite, bei hohen Seiten scrollbar.
final class PageContentController: UIViewController {
    let index: Int
    private let parentView: PagerView
    private let sheet = SheetView()
    private let scroll = UIScrollView()
    private var visual: PageVisual?
    private var loadTask: Task<Void, Never>?

    init(index: Int, parent: PagerView) {
        self.index = index
        parentView = parent
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not used") }

    deinit { loadTask?.cancel() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = parentView.palette.backdrop
        scroll.showsVerticalScrollIndicator = false
        scroll.alwaysBounceVertical = false
        scroll.contentInsetAdjustmentBehavior = .never
        view.addSubview(scroll)
        scroll.addSubview(sheet)
        sheet.configure(palette: parentView.palette, darkPaper: parentView.darkPaper)
        sheet.isAccessibilityElement = true
        sheet.accessibilityLabel = "Seite \(index + 1)"
        let provider = parentView.provider
        let index = self.index
        loadTask = Task { @MainActor [weak self] in
            let visual = await provider(index)
            guard let self, let visual else { return }
            self.visual = visual
            self.sheet.show(visual)
            self.view.setNeedsLayout()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        scroll.frame = view.bounds
        let bounds = view.bounds
        guard let visual else {
            // Noch nicht geladen: leeres Blatt in voller Größe.
            sheet.frame = bounds.insetBy(dx: 12, dy: 16)
            scroll.contentSize = bounds.size
            return
        }
        let size = visual.sheetSize
        if visual.scrollable {
            let x = (bounds.width - size.width) / 2
            sheet.frame = CGRect(x: x, y: 16, width: size.width, height: size.height)
            scroll.contentSize = CGSize(width: bounds.width, height: size.height + 32)
        } else {
            sheet.frame = CGRect(x: (bounds.width - size.width) / 2, y: (bounds.height - size.height) / 2,
                                 width: size.width, height: size.height)
            scroll.contentSize = bounds.size
        }
    }
}

/// Ein Blatt Papier: Schatten, Blattkanten links (gelesen) und rechts (noch zu lesen) und
/// Schattierung am Bundsteg wie bei einem aufgeschlagenen Buch.
final class SheetView: UIView {
    private let edgeLayer = CALayer()
    private let paper = UIView()
    private let imageView = UIImageView()
    private let spine = CAGradientLayer()
    private var palette = ReaderPalette.light
    private var readFraction: CGFloat = 0

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        // Zuerst die Kanten, darüber das Blatt.
        layer.addSublayer(edgeLayer)
        addSubview(paper)
        imageView.contentMode = .scaleToFill
        paper.addSubview(imageView)
        paper.layer.addSublayer(spine)
        paper.layer.shadowColor = UIColor.black.cgColor
        paper.layer.shadowOpacity = 0.25
        paper.layer.shadowRadius = 3
        paper.layer.shadowOffset = CGSize(width: 0, height: 1)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not used") }

    func configure(palette: ReaderPalette, darkPaper: Bool) {
        self.palette = palette
        paper.backgroundColor = palette.paper
        let alpha: CGFloat = darkPaper ? 0.35 : 0.13
        spine.colors = [
            palette.shade.withAlphaComponent(alpha).cgColor,
            palette.shade.withAlphaComponent(alpha * 0.35).cgColor,
            palette.shade.withAlphaComponent(0).cgColor,
        ]
        spine.locations = [0, 0.35, 1]
        spine.startPoint = CGPoint(x: 0, y: 0.5)
        spine.endPoint = CGPoint(x: 1, y: 0.5)
    }

    func show(_ visual: PageVisual) {
        imageView.image = visual.image
        readFraction = visual.readFraction
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        paper.frame = bounds
        imageView.frame = paper.bounds
        spine.frame = CGRect(x: 0, y: 0, width: bounds.width * 0.045, height: bounds.height)
        paper.layer.shadowPath = UIBezierPath(rect: paper.bounds).cgPath
        edgeLayer.frame = bounds
        layoutEdges()
    }

    /// Blattkanten: je nach Fortschritt bis zu drei Blätter links und rechts.
    private func layoutEdges() {
        edgeLayer.sublayers?.forEach { $0.removeFromSuperlayer() }
        let maxSheets: CGFloat = 3
        let left = Int(ceil(maxSheets * readFraction))
        let right = Int(ceil(maxSheets * (1 - readFraction)))
        let step: CGFloat = 2
        let inset: CGFloat = 1
        func sheet(_ i: Int, _ direction: CGFloat) {
            let edge = CALayer()
            edge.backgroundColor = palette.paperEdge.cgColor
            edge.borderColor = palette.shade.withAlphaComponent(0.18).cgColor
            edge.borderWidth = 0.5
            let dx = CGFloat(i) * step * direction
            let dy = CGFloat(i) * inset
            edge.frame = CGRect(x: dx, y: dy, width: bounds.width, height: bounds.height - 2 * dy)
            edgeLayer.addSublayer(edge)
        }
        // Von außen nach innen, damit nähere Blätter die äußeren überdecken.
        for i in stride(from: min(3, right), through: 1, by: -1) { sheet(i, 1) }
        for i in stride(from: min(3, left), through: 1, by: -1) { sheet(i, -1) }
    }
}
