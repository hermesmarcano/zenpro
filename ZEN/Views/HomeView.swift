import SwiftData
import SwiftUI

/// Pantalla de inicio mínima y funcional: lista de alarmas, altas de anclas (QR / foto)
/// y armado de la alarma inteligente. El dashboard de diseño (MIT, estado de bloqueo)
/// llega en M5.
struct HomeView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var coordinator: AlarmCoordinator

    @Query(sort: [SortDescriptor(\AlarmConfig.hour), SortDescriptor(\AlarmConfig.minute)])
    private var alarms: [AlarmConfig]

    @State private var showingNewAlarm = false
    @State private var showingQRSetup = false
    @State private var showingPhotoSetup = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            List {
                alarmsSection
                anchorsSection
                if let errorMessage {
                    Section {
                        Text(errorMessage).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("ZEN")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showingNewAlarm = true
                    } label: {
                        Image(systemName: "plus")
                    }
                    .accessibilityLabel("Nueva alarma")
                }
            }
            .sheet(isPresented: $showingNewAlarm) {
                NavigationStack { AlarmEditView() }
            }
            .sheet(isPresented: $showingQRSetup) { QRAnchorSetupView() }
            .sheet(isPresented: $showingPhotoSetup) { PhotoAnchorSetupView() }
            // Binding constante: la pantalla de apagado no se puede descartar deslizando.
            .fullScreenCover(isPresented: .constant(coordinator.activeRingingAlarmID != nil)) {
                ringingScreen
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active {
                    coordinator.session.reconcile()
                }
            }
        }
    }

    // MARK: - Alarmas

    private var alarmsSection: some View {
        Section {
            if alarms.isEmpty {
                Text("Aún no tienes alarmas. Confirma un ancla y crea la primera con +.")
                    .foregroundStyle(.secondary)
            }
            ForEach(alarms) { alarm in
                row(for: alarm)
            }
            .onDelete { offsets in
                for index in offsets {
                    delete(alarms[index])
                }
            }
        } header: {
            Text("Alarmas")
        } footer: {
            Text("Armar mantiene ZEN activo esta noche para que el volumen se adapte a tu movimiento. No cierres la app deslizándola; si iOS la cierra, suena la alarma del sistema 2 minutos después.")
        }
    }

    private func row(for alarm: AlarmConfig) -> some View {
        let isArmed = coordinator.session.armedAlarmID == alarm.id
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(String(format: "%02d:%02d", alarm.hour, alarm.minute))
                    .font(.system(.title, design: .monospaced))
                Text(repeatSummary(for: alarm))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button(isArmed ? "Desarmar" : "Armar") {
                toggleArm(alarm)
            }
            .buttonStyle(.bordered)
            .tint(isArmed ? .orange : .accentColor)
        }
    }

    private func repeatSummary(for alarm: AlarmConfig) -> String {
        let days = Weekday.allCases.filter { alarm.repeatDays.contains($0) }
        guard !days.isEmpty else { return "Una vez" }
        let names: [Weekday: String] = [
            .monday: "L", .tuesday: "M", .wednesday: "X", .thursday: "J",
            .friday: "V", .saturday: "S", .sunday: "D",
        ]
        return days.compactMap { names[$0] }.joined(separator: " ")
    }

    private func toggleArm(_ alarm: AlarmConfig) {
        errorMessage = nil
        Task { @MainActor in
            do {
                if coordinator.session.armedAlarmID == alarm.id {
                    await coordinator.session.disarm()
                } else {
                    try await coordinator.session.arm(alarm)
                }
            } catch {
                errorMessage = "No se pudo armar la alarma: \(error.localizedDescription)"
            }
        }
    }

    private func delete(_ alarm: AlarmConfig) {
        Task { @MainActor in
            if coordinator.session.armedAlarmID == alarm.id {
                await coordinator.session.disarm()
            }
            try? await AlarmScheduler.cancel(alarm)
            modelContext.delete(alarm)
        }
    }

    // MARK: - Anclas

    private var anchorsSection: some View {
        Section {
            Button("Nueva ancla con código QR") { showingQRSetup = true }
            Button("Nueva ancla con foto de un lugar") { showingPhotoSetup = true }
        } header: {
            Text("Anclas")
        } footer: {
            Text("El ancla es el punto al que tienes que ir para apagar la alarma: un QR pegado en algún sitio, o un lugar que fotografías.")
        }
    }

    // MARK: - Alarma sonando

    @ViewBuilder
    private var ringingScreen: some View {
        if let id = coordinator.activeRingingAlarmID,
           let alarm = alarms.first(where: { $0.id == id }) {
            AlarmRingingView(
                alarm: alarm,
                anchor: alarm.anchorID.flatMap { AnchorStore(context: modelContext).anchorRef(id: $0) },
                onEngagementChange: { coordinator.session.setUserEngaged($0) },
                onDismissed: {
                    Task { @MainActor in await coordinator.dismissalCompleted(alarm: alarm) }
                }
            )
        } else {
            // La alarma ya no existe (se borró mientras sonaba): se libera la pantalla
            // en vez de dejar al usuario atrapado ante un fondo negro.
            Color.black
                .ignoresSafeArea()
                .onAppear { coordinator.systemAlarmIsRinging(id: nil) }
        }
    }
}
