import Testing
import Foundation
@testable import ZEN

struct MotionClassifierTests {
    private let rate: TimeInterval = 0.02 // 50 Hz

    /// Alimenta `duration` segundos de muestras constantes empezando en `start`.
    @discardableResult
    private func feed(
        _ classifier: inout MotionClassifier,
        start: TimeInterval,
        duration: TimeInterval,
        acceleration: Double,
        rotation: Double
    ) -> MotionState {
        var state = classifier.state
        var time = start
        while time <= start + duration {
            state = classifier.ingest(MotionSample(
                timestamp: time,
                userAcceleration: acceleration,
                rotationRate: rotation
            ))
            time += rate
        }
        return state
    }

    @Test func phoneLyingOnTableIsStill() {
        var classifier = MotionClassifier()
        let state = feed(&classifier, start: 0, duration: 3, acceleration: 0.002, rotation: 0.01)
        #expect(state == .still)
    }

    @Test func walkingIsMoving() {
        var classifier = MotionClassifier()
        let state = feed(&classifier, start: 0, duration: 2, acceleration: 0.2, rotation: 0.5)
        #expect(state == .moving)
    }

    @Test func returnsToStillAfterMovementStops() {
        var classifier = MotionClassifier()
        feed(&classifier, start: 0, duration: 2, acceleration: 0.2, rotation: 0.5)
        let state = feed(&classifier, start: 2.02, duration: 3, acceleration: 0.002, rotation: 0.01)
        #expect(state == .still)
    }

    @Test func hysteresisKeepsPreviousStateInTheMiddleBand() {
        // Puntuación ~0.5: por debajo de "moving" (1.0) y por encima de "still" (0.4).
        let middle = (acceleration: 0.02, rotation: 0.0)

        var wasStill = MotionClassifier()
        let stayedStill = feed(&wasStill, start: 0, duration: 3, acceleration: middle.acceleration, rotation: middle.rotation)
        #expect(stayedStill == .still)

        var wasMoving = MotionClassifier()
        feed(&wasMoving, start: 0, duration: 2, acceleration: 0.2, rotation: 0.5)
        let stayedMoving = feed(&wasMoving, start: 2.02, duration: 3, acceleration: middle.acceleration, rotation: middle.rotation)
        #expect(stayedMoving == .moving)
    }

    @Test func singleBumpDoesNotLatchMovingForever() {
        var classifier = MotionClassifier()
        feed(&classifier, start: 0, duration: 1, acceleration: 0.002, rotation: 0.01)
        classifier.ingest(MotionSample(timestamp: 1.02, userAcceleration: 0.5, rotationRate: 1.0))
        let state = feed(&classifier, start: 1.04, duration: 4, acceleration: 0.002, rotation: 0.01)
        #expect(state == .still)
    }

    @Test func backwardsTimestampsResetTheWindowInsteadOfStalling() {
        var classifier = MotionClassifier()
        feed(&classifier, start: 100, duration: 2, acceleration: 0.2, rotation: 0.5)
        // El reloj de sensores se reinicia: las muestras viejas no deben quedar pegadas.
        let state = feed(&classifier, start: 0, duration: 3, acceleration: 0.002, rotation: 0.01)
        #expect(state == .still)
    }
}
