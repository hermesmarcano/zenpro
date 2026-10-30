import Foundation

/// Calcula cuándo debe sonar la alarma a partir de su hora, minuto y días de repetición.
/// Es lógica pura (con `Calendar` inyectable) para poder probar días, medianoche y
/// horario de verano sin dispositivo.
enum NextFireCalculator {
    /// Próxima fecha **estrictamente posterior** a `date` en que suena la alarma.
    /// `weekdays` vacío = una sola vez: la próxima ocurrencia de hh:mm.
    static func nextFireDate(
        after date: Date,
        hour: Int,
        minute: Int,
        weekdays: Set<Weekday>,
        calendar: Calendar = .current
    ) -> Date? {
        guard (0...23).contains(hour), (0...59).contains(minute) else { return nil }

        let target = DateComponents(hour: hour, minute: minute, second: 0)
        var cursor = date
        // Con al menos un día elegido, el siguiente cae dentro de 7 días; el margen
        // cubre que `nextDate` devuelva el mismo día de la semana varias veces.
        for _ in 0..<8 {
            guard let next = calendar.nextDate(
                after: cursor,
                matching: target,
                matchingPolicy: .nextTime
            ) else {
                return nil
            }
            if weekdays.isEmpty || weekdays.contains(weekday(of: next, calendar: calendar)) {
                return next
            }
            cursor = next
        }
        return nil
    }

    private static func weekday(of date: Date, calendar: Calendar) -> Weekday {
        // `Weekday` usa la misma numeración que `Calendar` (1 = domingo … 7 = sábado).
        Weekday(rawValue: calendar.component(.weekday, from: date)) ?? .sunday
    }
}

struct ShiftedSchedule: Equatable {
    var hour: Int
    var minute: Int
    var weekdays: Set<Weekday>
}

/// Desplaza una hora de alarma unos minutos arrastrando los días de la semana cuando
/// se cruza la medianoche (23:59 + 2 min => 00:01 del día siguiente). Se usa para
/// mover la alarma de AlarmKit a `hora + margen` mientras la alarma inteligente está
/// armada.
enum ScheduleShift {
    static func shift(
        hour: Int,
        minute: Int,
        weekdays: Set<Weekday>,
        byMinutes delta: Int
    ) -> ShiftedSchedule {
        let minutesPerDay = 24 * 60
        let total = hour * 60 + minute + delta
        let dayShift = floorDiv(total, minutesPerDay)
        let minuteOfDay = total - dayShift * minutesPerDay

        let shiftedDays = Set(weekdays.map { day -> Weekday in
            let zeroBased = (day.rawValue - 1 + dayShift) % 7
            let wrapped = zeroBased < 0 ? zeroBased + 7 : zeroBased
            return Weekday(rawValue: wrapped + 1) ?? day
        })

        return ShiftedSchedule(
            hour: minuteOfDay / 60,
            minute: minuteOfDay % 60,
            weekdays: shiftedDays
        )
    }

    private static func floorDiv(_ value: Int, _ divisor: Int) -> Int {
        let quotient = value / divisor
        return (value % divisor != 0 && (value < 0) != (divisor < 0)) ? quotient - 1 : quotient
    }
}
