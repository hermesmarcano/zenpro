import SwiftUI
import SwiftData

struct AlarmEditView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss

    @State private var time = Date()
    @State private var repeatDays: Set<Weekday> = []
    @State private var escalationEnabled = true
    @State private var selectedAnchorID: UUID?
    @State private var confirmedAnchors: [AnchorRef] = []
    @State private var isSaving = false
    @State private var errorMessage: String?

    var body: some View {
        Form {
            Section("Hora") {
                DatePicker("Hora", selection: $time, displayedComponents: .hourAndMinute)
                    .datePickerStyle(.wheel)
                    .labelsHidden()
            }

            Section("Repetir") {
                HStack {
                    ForEach(Weekday.allCases) { day in
                        dayToggle(day)
                    }
                }
            }

            Section("Escalada de volumen") {
                Toggle("Sube cada minuto hasta escanear el código", isOn: $escalationEnabled)
            }

            // Sin ancla confirmada no se puede guardar: una alarma sin QR asignado
            // no cumple el propósito del producto (ver ARCHITECTURE.md).
            Section("Ancla QR") {
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
        .toolbar {
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

    private func dayToggle(_ day: Weekday) -> some View {
        let isOn = repeatDays.contains(day)
        return Button {
            if isOn {
                repeatDays.remove(day)
            } else {
                repeatDays.insert(day)
            }
        } label: {
            Text(shortLabel(for: day))
                .frame(width: 32, height: 32)
                .background(isOn ? Color.accentColor : Color.secondary.opacity(0.15))
                .foregroundStyle(isOn ? Color.white : Color.primary)
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
    }

    private func shortLabel(for day: Weekday) -> String {
        switch day {
        case .sunday: "D"
        case .monday: "L"
        case .tuesday: "M"
        case .wednesday: "X"
        case .thursday: "J"
        case .friday: "V"
        case .saturday: "S"
        }
    }

    private func save() {
        guard let selectedAnchorID else { return }
        let components = Calendar.current.dateComponents([.hour, .minute], from: time)
        guard let hour = components.hour, let minute = components.minute else { return }

        isSaving = true
        let store = AlarmStore(context: modelContext)
        let alarm = store.create(hour: hour, minute: minute, repeatDays: repeatDays, anchorID: selectedAnchorID)
        alarm.escalationEnabled = escalationEnabled

        Task { @MainActor in
            do {
                try await AlarmScheduler.schedule(alarm)
                dismiss()
            } catch {
                // Si AlarmKit rechaza el scheduling, no dejamos una AlarmConfig
                // huérfana sin alarma real programada detrás.
                errorMessage = "No se pudo programar la alarma: \(error.localizedDescription)"
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
