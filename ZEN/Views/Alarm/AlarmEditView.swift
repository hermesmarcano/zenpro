import SwiftUI
import SwiftData

struct AlarmEditView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    @State private var time = AlarmEditView.defaultTime()
    @State private var repeatDays: Set<Weekday> = []
    @State private var selectedAnchorID: UUID?
    @State private var confirmedAnchors: [AnchorRef] = []
    @State private var isSaving = false
    @State private var errorMessage: String?

    /// 07:00 de hoy. Con `Date()` la alarma nacería a la hora actual: AlarmKit la
    /// programaría para dentro de 24 h (o sonaría al instante), nunca lo esperado.
    private static func defaultTime() -> Date {
        Calendar.current.date(bySettingHour: 7, minute: 0, second: 0, of: Date()) ?? Date()
    }

    var body: some View {
        Form {
            Section("Hora") {
                DatePicker("Hora", selection: $time, displayedComponents: .hourAndMinute)
                    .datePickerStyle(.wheel)
                    .labelsHidden()
            }

            Section("Repetir") {
                HStack(spacing: 0) {
                    ForEach(Weekday.mondayFirst) { day in
                        dayToggle(day)
                    }
                }
            }

            // Sin ancla confirmada no se puede guardar: una alarma sin QR ni foto
            // asignados no cumple el propósito del producto (ver ARCHITECTURE.md).
            Section("Ancla") {
                if confirmedAnchors.isEmpty {
                    Text("Necesitas confirmar un ancla (QR o foto) antes de poder crear una alarma.")
                        .foregroundStyle(.secondary)
                } else {
                    Picker("Ancla", selection: $selectedAnchorID) {
                        ForEach(confirmedAnchors) { anchor in
                            Text("\(anchor.label) · \(anchor.kindName)").tag(Optional(anchor.id))
                        }
                    }
                }
            }

            if let errorMessage {
                Section {
                    Text(errorMessage).foregroundStyle(.red)
                }
            }
        }
        .navigationTitle("Nueva alarma")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancelar") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Guardar") { save() }
                    .disabled(isSaving || selectedAnchorID == nil)
            }
        }
        .task {
            confirmedAnchors = AnchorStore(context: modelContext).confirmedAnchorRefs()
            selectedAnchorID = confirmedAnchors.first?.id
        }
    }

    /// Botón de día con área táctil de 44 pt (mínimo de las HIG) y etiqueta completa para
    /// VoiceOver: la inicial suelta ("X") no dice nada a quien no ve la pantalla.
    private func dayToggle(_ day: Weekday) -> some View {
        let isOn = repeatDays.contains(day)
        return Button {
            if isOn {
                repeatDays.remove(day)
            } else {
                repeatDays.insert(day)
            }
        } label: {
            Text(day.shortLabel)
                .font(.subheadline.weight(.semibold))
                .frame(width: 36, height: 36)
                .background(isOn ? Color.accentColor : Color.secondary.opacity(0.15), in: Circle())
                .foregroundStyle(isOn ? Color.white : Color.primary)
                .frame(maxWidth: .infinity, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(day.fullName)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }

    private func save() {
        guard let selectedAnchorID else { return }
        let components = Calendar.current.dateComponents([.hour, .minute], from: time)
        guard let hour = components.hour, let minute = components.minute else { return }

        isSaving = true
        errorMessage = nil
        let store = AlarmStore(context: modelContext)
        let alarm = store.create(hour: hour, minute: minute, repeatDays: repeatDays, anchorID: selectedAnchorID)

        Task { @MainActor in
            do {
                try await AlarmScheduler.schedule(alarm)
                dismiss()
            } catch {
                // Si AlarmKit rechaza el scheduling, no dejamos una AlarmConfig
                // huérfana sin alarma real programada detrás.
                errorMessage = "No se pudo programar la alarma. Comprueba que ZEN tenga permiso para alarmas en Ajustes. (\(error.localizedDescription))"
                store.delete(alarm)
            }
            isSaving = false
        }
    }
}

#Preview {
    NavigationStack {
        AlarmEditView()
            .modelContainer(for: [AlarmConfig.self, QRAnchor.self, PhotoAnchor.self], inMemory: true)
    }
}
