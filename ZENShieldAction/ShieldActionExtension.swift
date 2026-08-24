import ManagedSettings

/// Maneja los taps en los botones del Shield. El botón primario debe abrir ZEN
/// (deep link registrado en la app principal); el secundario simplemente cierra
/// el shield sin abrir la app bloqueada.
///
/// Casos de `ShieldActionResponse` confirmados por CI (build real en macOS, Xcode 26.0.1).
/// Pendiente de verificar en dispositivo real (no solo compilación): que `.defer`
/// efectivamente abre la app contenedora — se valida en M3 con hardware.
final class ShieldActionExtension: ShieldActionDelegate {
    override func handle(
        action: ShieldAction,
        for application: ApplicationToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        switch action {
        case .primaryButtonPressed:
            completionHandler(.defer)
        case .secondaryButtonPressed:
            completionHandler(.close)
        @unknown default:
            completionHandler(.close)
        }
    }

    override func handle(
        action: ShieldAction,
        for webDomain: WebDomainToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        completionHandler(.close)
    }

    override func handle(
        action: ShieldAction,
        for category: ActivityCategoryToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        completionHandler(.close)
    }
}
