import Foundation

/// Decide si una foto candidata coincide con las referencias de un ancla, a partir de
/// las distancias de feature print (menor = más parecida). Es lógica pura: calcular
/// las distancias con Vision vive en `PhotoMatcher`.
enum PhotoMatchPolicy {
    /// Distancia máxima (exclusiva) para aceptar una foto como "el mismo lugar".
    /// [CALIBRAR EN DISPOSITIVO]: no se pudo medir con fotos reales (luz del día
    /// vs. madrugada). Si se queda corto el usuario cae al código de respaldo; si es
    /// laxo, se aceptan lugares parecidos. Es la única constante que ajustar.
    static let defaultThreshold: Float = 0.7

    /// Fallos consecutivos tras los cuales la pantalla sugiere encender la luz o usar
    /// el código de respaldo.
    static let failuresBeforeHint = 3

    /// Mejor (menor) distancia válida. Ignora NaN, infinitos y negativos para que una
    /// referencia corrupta no pueda ganar la comparación ni forzar un falso positivo.
    static func bestDistance(_ distances: [Float]) -> Float? {
        distances.filter { $0.isFinite && $0 >= 0 }.min()
    }

    /// Coincide si la mejor distancia contra cualquier referencia es estrictamente
    /// menor al umbral. Sin distancias válidas nunca coincide.
    static func isMatch(distances: [Float], threshold: Float = defaultThreshold) -> Bool {
        guard let best = bestDistance(distances) else { return false }
        return best < threshold
    }

    static func shouldShowHint(consecutiveFailures: Int) -> Bool {
        consecutiveFailures >= failuresBeforeHint
    }
}
