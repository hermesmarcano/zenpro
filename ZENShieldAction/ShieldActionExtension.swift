import ManagedSettings

/// Maneja los taps en los botones del Shield. El botón primario debe abrir ZEN
/// (deep link registrado en la app principal); el secundario simplemente cierra
/// el shield sin abrir la app bloqueada.
///
/// [VERIFICAR] Los casos exactos de `ShieldActionResponse` (`.close`, `.defer`, …)
/// se escribieron de memoria — confirmar contra ManagedSettings cuando haya CI real.
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
