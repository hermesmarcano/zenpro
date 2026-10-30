import UserNotifications

/// Aviso en la pantalla de bloqueo a la hora de la alarma. Con la alarma inteligente
/// armada el tono suena desde ZEN, pero la app no se abre sola: este aviso (sin sonido,
/// para no duplicar el tono) es lo que el usuario toca para llegar a la pantalla donde
/// se apaga con el QR o la foto.
enum WakeNotification {
    private static let identifier = "zen.wake-banner"

    static func requestAuthorization() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert])) ?? false
    }

    static func schedule(at fireDate: Date, label: String) async {
        let content = UNMutableNotificationContent()
        content.title = "ZEN"
        content.body = "Es hora. Toca para apagar la alarma (\(label))."
        content.sound = nil
        content.interruptionLevel = .active

        let components = Calendar.current.dateComponents(
            [.year, .month, .day, .hour, .minute, .second],
            from: fireDate
        )
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: trigger)
        try? await UNUserNotificationCenter.current().add(request)
    }

    static func cancel() {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
    }
}
