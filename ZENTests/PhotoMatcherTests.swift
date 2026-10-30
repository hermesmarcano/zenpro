import Testing
import UIKit
@testable import ZEN

struct PhotoMatcherTests {
    private func render(size: CGSize, scale: CGFloat = 1, draw: (CGContext, CGSize) -> Void) -> UIImage {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = scale
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            draw(context.cgContext, size)
        }
    }

    /// Escena con estructura reconocible: cielo azul, suelo rojo y un sol amarillo.
    private func patio(brightness: CGFloat = 1.0, shift: CGFloat = 0) -> UIImage {
        render(size: CGSize(width: 400, height: 300)) { context, size in
            UIColor(red: 0.2 * brightness, green: 0.4 * brightness, blue: 0.9 * brightness, alpha: 1).setFill()
            context.fill(CGRect(x: 0, y: 0, width: size.width, height: size.height / 2))
            UIColor(red: 0.8 * brightness, green: 0.2 * brightness, blue: 0.1 * brightness, alpha: 1).setFill()
            context.fill(CGRect(x: 0, y: size.height / 2, width: size.width, height: size.height / 2))
            UIColor.yellow.setFill()
            context.fillEllipse(in: CGRect(x: 280 + shift, y: 30, width: 70, height: 70))
            UIColor.darkGray.setFill()
            context.fill(CGRect(x: 60 + shift, y: 120, width: 90, height: 130))
        }
    }

    /// Una escena completamente distinta: rayas diagonales verdes y blancas.
    private func stripes() -> UIImage {
        render(size: CGSize(width: 400, height: 300)) { context, size in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            UIColor(red: 0.1, green: 0.6, blue: 0.2, alpha: 1).setStroke()
            context.setLineWidth(14)
            var x: CGFloat = -300
            while x < size.width {
                context.move(to: CGPoint(x: x, y: 0))
                context.addLine(to: CGPoint(x: x + 300, y: size.height))
                x += 34
            }
            context.strokePath()
        }
    }

    @Test func normalizerShrinksLargeImagesKeepingAspectRatio() throws {
        let large = render(size: CGSize(width: 1600, height: 1200)) { context, size in
            UIColor.blue.setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }
        let normalized = try #require(PhotoNormalizer.normalized(large))
        let cgImage = try #require(normalized.cgImage)
        #expect(cgImage.width == 800)
        #expect(cgImage.height == 600)
    }

    @Test func normalizerNeverUpscalesSmallImages() throws {
        let small = render(size: CGSize(width: 200, height: 100)) { context, size in
            UIColor.red.setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }
        let normalized = try #require(PhotoNormalizer.normalized(small))
        let cgImage = try #require(normalized.cgImage)
        #expect(cgImage.width == 200)
        #expect(cgImage.height == 100)
    }

    @Test func normalizerAppliesImageOrientation() throws {
        // Una foto de cámara en vertical llega como píxeles apaisados + orientación .right.
        let landscape = try #require(patio().cgImage)
        let rotated = UIImage(cgImage: landscape, scale: 1, orientation: .right)
        #expect(rotated.size.width == 300)
        #expect(rotated.size.height == 400)

        let normalized = try #require(PhotoNormalizer.normalized(rotated))
        let cgImage = try #require(normalized.cgImage)
        #expect(cgImage.width == 300)
        #expect(cgImage.height == 400)
    }

    @Test func normalizerRejectsEmptyImages() {
        #expect(PhotoNormalizer.normalized(UIImage()) == nil)
    }

    @Test func sameSceneIsCloserThanADifferentScene() throws {
        let reference = try #require(patio().cgImage)
        let sameScene = try #require(patio(brightness: 0.85, shift: 6).cgImage)
        let differentScene = try #require(stripes().cgImage)

        let identical = try PhotoMatcher.distances(candidate: reference, references: [reference])[0]
        let similar = try PhotoMatcher.distances(candidate: sameScene, references: [reference])[0]
        let different = try PhotoMatcher.distances(candidate: differentScene, references: [reference])[0]

        #expect(identical < 0.01)
        #expect(identical < similar)
        #expect(similar < different)
    }

    @Test func verifierUsesTheBestOfSeveralReferences() async throws {
        let reference = try #require(PhotoNormalizer.jpegData(from: patio()))
        let unrelated = try #require(PhotoNormalizer.jpegData(from: stripes()))

        let outcome = try #require(
            await PhotoVerifier.verify(
                candidate: patio(brightness: 0.9, shift: 4),
                references: [unrelated, reference]
            )
        )
        let farOutcome = try #require(
            await PhotoVerifier.verify(candidate: stripes(), references: [reference])
        )
        #expect(outcome.bestDistance < farOutcome.bestDistance)
    }

    @Test func verifierReportsUnanalyzableInputAsNilNotAsMismatch() async {
        let outcome = await PhotoVerifier.verify(candidate: patio(), references: [])
        #expect(outcome == nil)
        let garbage = await PhotoVerifier.verify(candidate: patio(), references: [Data([0, 1, 2])])
        #expect(garbage == nil)
    }
}
