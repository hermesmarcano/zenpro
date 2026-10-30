import Testing
import Foundation
@testable import ZEN

struct SmartVolumeControllerTests {
    private let step: TimeInterval = 0.1

    /// Avanza el controlador de `from` a `to` (inclusive) con las señales dadas y
    /// devuelve la última salida.
    @discardableResult
    private func run(
        _ controller: inout SmartVolumeController,
        from: TimeInterval,
        to: TimeInterval,
        motion: MotionState,
        engaged: Bool = false
    ) -> SmartVolumeOutput {
        var output = SmartVolumeOutput(volume: controller.volume, vibrationInterval: nil)
        var time = from
        while time <= to + 0.0001 {
            output = controller.update(now: time, motion: motion, userEngaged: engaged)
            time += step
        }
        return output
    }

    @Test func startsLowAndVibrating() {
        var controller = SmartVolumeController(startTime: 0)
        let output = controller.update(now: 0, motion: .still, userEngaged: false)
        #expect(abs(output.volume - 0.15) < 0.001)
        #expect(output.vibrationInterval != nil)
    }

    @Test func rampsUpToFullVolumeWhenUntouched() {
        var controller = SmartVolumeController(startTime: 0)
        let midway = run(&controller, from: 0, to: 45, motion: .still)
        #expect(abs(midway.volume - 0.575) < 0.01)
        let end = run(&controller, from: 45.1, to: 95, motion: .still)
        #expect(abs(end.volume - 1.0) < 0.001)
    }

    @Test func volumeNeverDecreasesWhileUntouched() {
        var controller = SmartVolumeController(startTime: 0)
        var previous: Float = 0
        var neverDecreased = true
        var time: TimeInterval = 0
        while time <= 100 {
            let output = controller.update(now: time, motion: .still, userEngaged: false)
            if output.volume + 0.0001 < previous { neverDecreased = false }
            previous = output.volume
            time += step
        }
        #expect(neverDecreased)
    }

    @Test func pickupHalvesVolumeThenDropsToTenPercent() {
        var controller = SmartVolumeController(startTime: 0)
        let before = run(&controller, from: 0, to: 60, motion: .still)

        // Poco después de coger el teléfono (antes de que termine la fase "mitad").
        let justPickedUp = run(&controller, from: 60.1, to: 61.2, motion: .moving)
        #expect(abs(justPickedUp.volume - before.volume * 0.5) < 0.02)

        // Con el teléfono en movimiento sostenido baja al volumen de marcha y deja de vibrar.
        let carried = run(&controller, from: 61.3, to: 66, motion: .moving)
        #expect(abs(carried.volume - 0.10) < 0.005)
        #expect(carried.vibrationInterval == nil)
    }

    @Test func leftBehindReturnsToHalfThenRampsToFull() {
        var controller = SmartVolumeController(startTime: 0)
        run(&controller, from: 0, to: 30, motion: .still)
        run(&controller, from: 30.1, to: 36, motion: .moving)

        // 5 s quieto sin interactuar => "lo dejó por ahí".
        run(&controller, from: 36.1, to: 41.2, motion: .still)
        guard case .leftBehind = controller.phase else {
            Issue.record("Se esperaba la fase leftBehind, fue \(controller.phase)")
            return
        }

        let soon = run(&controller, from: 41.3, to: 43.7, motion: .still)
        #expect(soon.volume >= 0.5)
        #expect(soon.volume <= 0.56)
        #expect(soon.vibrationInterval != nil)

        let end = run(&controller, from: 43.8, to: 102, motion: .still)
        #expect(abs(end.volume - 1.0) < 0.001)
    }

    @Test func engagementPreventsLeftBehind() {
        var controller = SmartVolumeController(startTime: 0)
        run(&controller, from: 0, to: 30, motion: .still)
        run(&controller, from: 30.1, to: 36, motion: .moving)

        // Quieto pero con la cámara de escaneo abierta: no debe subir el volumen.
        let output = run(&controller, from: 36.1, to: 70, motion: .still, engaged: true)
        #expect(abs(output.volume - 0.10) < 0.005)
        guard case .carried = controller.phase else {
            Issue.record("Se esperaba seguir en fase carried, fue \(controller.phase)")
            return
        }
    }

    @Test func engagementWhileRestingCountsAsCarried() {
        var controller = SmartVolumeController(startTime: 0)
        run(&controller, from: 0, to: 20, motion: .still)
        let output = run(&controller, from: 20.1, to: 26, motion: .still, engaged: true)
        #expect(abs(output.volume - 0.10) < 0.005)
    }

    @Test func pickingUpAgainAfterLeftBehindDropsVolumeAgain() {
        var controller = SmartVolumeController(startTime: 0)
        run(&controller, from: 0, to: 10, motion: .moving)
        run(&controller, from: 10.1, to: 16, motion: .still)
        let rampedUp = run(&controller, from: 16.1, to: 40, motion: .still)
        #expect(rampedUp.volume > 0.6)

        run(&controller, from: 40.1, to: 41.2, motion: .moving)
        let afterPickup = run(&controller, from: 41.3, to: 47, motion: .moving)
        #expect(abs(afterPickup.volume - 0.10) < 0.005)
    }

    @Test func volumeStaysWithinBoundsWithOddTimestamps() {
        var controller = SmartVolumeController(startTime: 100)
        let times: [TimeInterval] = [100, 99, 100, 5_000, 4_999, 100_000, 100_000, 0]
        var inBounds = true
        for time in times {
            for motion in [MotionState.still, .moving] {
                let output = controller.update(now: time, motion: motion, userEngaged: false)
                if output.volume < 0 || output.volume > 1 { inBounds = false }
            }
        }
        #expect(inBounds)
    }

    @Test func zeroLengthRampDoesNotProduceNaN() {
        var profile = SmartVolumeProfile()
        profile.initialRampDuration = 0
        var controller = SmartVolumeController(profile: profile, startTime: 0)
        let output = run(&controller, from: 0, to: 5, motion: .still)
        #expect(output.volume.isFinite)
        #expect(abs(output.volume - 1.0) < 0.001)
    }
}
