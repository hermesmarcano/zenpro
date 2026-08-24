import Testing
@testable import ZEN

struct AlarmConfigTests {
    @Test func repeatDaysRoundTrip() {
        let alarm = AlarmConfig(hour: 6, minute: 30, repeatDays: [.monday, .wednesday, .friday])
        #expect(alarm.repeatDays == [.monday, .wednesday, .friday])
    }

    @Test func defaultsAreSensible() {
        let alarm = AlarmConfig(hour: 7, minute: 0)
        #expect(alarm.isEnabled)
        #expect(alarm.graceSeconds == 120)
        #expect(alarm.repeatDays.isEmpty)
    }
}
