import Testing
import Foundation
@testable import ZEN

struct PhotoMatchPolicyTests {
    @Test func matchesWhenBestReferenceIsCloseEnough() {
        #expect(PhotoMatchPolicy.isMatch(distances: [1.4, 0.4, 1.1]))
    }

    @Test func doesNotMatchWhenEveryReferenceIsFar() {
        #expect(!PhotoMatchPolicy.isMatch(distances: [1.4, 0.9, 1.1]))
    }

    @Test func thresholdIsExclusive() {
        let threshold = PhotoMatchPolicy.defaultThreshold
        #expect(!PhotoMatchPolicy.isMatch(distances: [threshold]))
        #expect(PhotoMatchPolicy.isMatch(distances: [threshold - 0.001]))
    }

    @Test func noReferencesNeverMatches() {
        #expect(!PhotoMatchPolicy.isMatch(distances: []))
    }

    @Test func invalidDistancesCannotForceAMatch() {
        #expect(!PhotoMatchPolicy.isMatch(distances: [.nan]))
        #expect(!PhotoMatchPolicy.isMatch(distances: [-1, .nan, .infinity]))
        #expect(PhotoMatchPolicy.bestDistance([.nan, 0.5, -2]) == 0.5)
    }

    @Test func zeroDistanceFromADegenerateModelNeverMatches() {
        // El simulador de iOS devuelve 0 para imágenes distintas: no debe contar como acierto.
        #expect(!PhotoMatchPolicy.isMatch(distances: [0]))
        #expect(!PhotoMatchPolicy.isMatch(distances: [0, 0, 0]))
        #expect(PhotoMatchPolicy.bestDistance([0, 0]) == nil)
        // Un cero no tapa a una distancia real y válida.
        #expect(PhotoMatchPolicy.isMatch(distances: [0, 0.4]))
    }

    @Test func customThresholdIsHonored() {
        #expect(PhotoMatchPolicy.isMatch(distances: [0.9], threshold: 1.0))
        #expect(!PhotoMatchPolicy.isMatch(distances: [0.9], threshold: 0.5))
    }

    @Test func hintAppearsAfterRepeatedFailures() {
        #expect(!PhotoMatchPolicy.shouldShowHint(consecutiveFailures: 0))
        #expect(!PhotoMatchPolicy.shouldShowHint(consecutiveFailures: PhotoMatchPolicy.failuresBeforeHint - 1))
        #expect(PhotoMatchPolicy.shouldShowHint(consecutiveFailures: PhotoMatchPolicy.failuresBeforeHint))
    }
}
