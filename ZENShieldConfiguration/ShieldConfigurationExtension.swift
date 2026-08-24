import ManagedSettings
import ManagedSettingsUI
import UIKit

/// Pantalla nativa de bloqueo ("Shield") que ve el usuario al abrir una app bloqueada.
/// Copy y colores según el mockup "Bloqueo-Shield" del canvas de diseño.
///
/// [VERIFICAR] La firma exacta del inicializador de `ShieldConfiguration` se escribió
/// de memoria (no hay Xcode local para comprobarla contra ManagedSettingsUI). Si CI
/// falla aquí, es el primer punto a corregir con el error real del compilador.
final class ShieldConfigurationExtension: ShieldConfigurationDataSource {
    override func configuration(shielding application: Application) -> ShieldConfiguration {
        Self.zenConfiguration
    }

    override func configuration(
        shielding application: Application,
        in category: ActivityCategory
    ) -> ShieldConfiguration {
        Self.zenConfiguration
    }

    override func configuration(shielding webDomain: WebDomain) -> ShieldConfiguration {
        Self.zenConfiguration
    }

    override func configuration(
        shielding webDomain: WebDomain,
        in category: ActivityCategory
    ) -> ShieldConfiguration {
        Self.zenConfiguration
    }

    private static let zenConfiguration = ShieldConfiguration(
        backgroundBlurStyle: .systemMaterialDark,
        backgroundColor: UIColor(red: 0.043, green: 0.043, blue: 0.051, alpha: 1),
        title: ShieldConfiguration.Label(
            text: "Esto no estaba en el plan.",
            color: .white
        ),
        subtitle: ShieldConfiguration.Label(
            text: "¿Seguro que quieres abrir esto ahora?",
            color: UIColor(white: 1, alpha: 0.7)
        ),
        primaryButtonLabel: ShieldConfiguration.Label(
            text: "Volver a ZEN",
            color: .black
        ),
        primaryButtonBackgroundColor: .white,
        secondaryButtonLabel: ShieldConfiguration.Label(
            text: "Cerrar",
            color: UIColor(white: 1, alpha: 0.6)
        )
    )
}
