import Foundation
import HealthKit

struct Stage: Codable {
    let stage: Int
    let start: String
    let end: String
}

struct Extra: Codable {
    var exerciseType: Int?
    var title: String?
    var distance_m: Double?
    var energy_kcal: Double?
    var stages: [Stage]?
}

struct Rec: Codable {
    let seq: Int
    let id: String
    let type: String
    let start: String
    let end: String
    let value: Double?
    let unit: String?
    let extra: Extra?
}

struct Page: Codable {
    let items: [Rec]
    let next: Int
}

enum SyncError: LocalizedError {
    case msg(String)
    var errorDescription: String? {
        switch self {
        case .msg(let m): return m
        }
    }
}

enum Fmt {
    static let plain: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()
    static let frac: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    static func parse(_ s: String) -> Date? {
        return plain.date(from: s) ?? frac.date(from: s)
    }
}

@MainActor
final class SyncVM: ObservableObject {
    @Published var url: String { didSet { UserDefaults.standard.set(url, forKey: "url") } }
    @Published var token: String { didSet { UserDefaults.standard.set(token, forKey: "token") } }
    @Published var status: String = "Listo"
    @Published var busy: Bool = false

    private let store = HKHealthStore()

    init() {
        url = UserDefaults.standard.string(forKey: "url") ?? "http://192.168.1.5:8765"
        token = UserDefaults.standard.string(forKey: "token") ?? ""
    }

    // MARK: Permisos

    private var shareTypes: Set<HKSampleType> {
        var s: Set<HKSampleType> = [HKObjectType.workoutType()]
        let ids: [HKQuantityTypeIdentifier] = [
            .stepCount, .heartRate, .bodyMass,
            .distanceWalkingRunning, .distanceCycling, .distanceSwimming,
            .activeEnergyBurned,
        ]
        for id in ids {
            if let t = HKObjectType.quantityType(forIdentifier: id) { s.insert(t) }
        }
        if let t = HKObjectType.categoryType(forIdentifier: .sleepAnalysis) { s.insert(t) }
        return s
    }

    func requestAuth() async {
        guard HKHealthStore.isHealthDataAvailable() else {
            status = "Salud no está disponible en este dispositivo"
            return
        }
        do {
            try await store.requestAuthorization(toShare: shareTypes, read: [])
            status = "Permisos solicitados. Ya puedes sincronizar."
        } catch {
            status = "Error: \(error.localizedDescription)"
        }
    }

    // MARK: Red

    private func fetch(after: Int) async throws -> Page {
        let base = url.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let u = URL(string: "\(base)/records?after=\(after)&limit=1000") else {
            throw SyncError.msg("URL no válida")
        }
        var req = URLRequest(url: u)
        req.timeoutInterval = 30
        let tk = token.trimmingCharacters(in: .whitespacesAndNewlines)
        req.setValue("Bearer \(tk)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else { throw SyncError.msg("Servidor: HTTP \(code)") }
        return try JSONDecoder().decode(Page.self, from: data)
    }

    // MARK: Conversión a HealthKit

    private func meta(_ r: Rec, _ suffix: String = "") -> [String: Any] {
        return [
            HKMetadataKeySyncIdentifier: "hb-\(r.id)\(suffix)",
            HKMetadataKeySyncVersion: r.seq,
        ]
    }

    private func sleepValue(_ stage: Int) -> HKCategoryValueSleepAnalysis {
        switch stage {
        case 1, 3, 7: return .awake
        case 4: return .asleepCore
        case 5: return .asleepDeep
        case 6: return .asleepREM
        default: return .asleepUnspecified
        }
    }

    private func activity(_ t: Int) -> (HKWorkoutActivityType, HKQuantityTypeIdentifier) {
        switch t {
        case 8, 9: return (.cycling, .distanceCycling)
        case 37: return (.hiking, .distanceWalkingRunning)
        case 56, 57: return (.running, .distanceWalkingRunning)
        case 73, 74: return (.swimming, .distanceSwimming)
        case 79: return (.walking, .distanceWalkingRunning)
        case 70, 81: return (.traditionalStrengthTraining, .distanceWalkingRunning)
        case 83: return (.yoga, .distanceWalkingRunning)
        default: return (.other, .distanceWalkingRunning)
        }
    }

    private func objects(for r: Rec) -> [HKObject] {
        guard let s = Fmt.parse(r.start), let e = Fmt.parse(r.end) else { return [] }
        switch r.type {
        case "steps":
            guard let v = r.value, let t = HKQuantityType.quantityType(forIdentifier: .stepCount) else { return [] }
            return [HKQuantitySample(type: t, quantity: HKQuantity(unit: .count(), doubleValue: v), start: s, end: e, metadata: meta(r))]
        case "heart_rate":
            guard let v = r.value, let t = HKQuantityType.quantityType(forIdentifier: .heartRate) else { return [] }
            let unit = HKUnit.count().unitDivided(by: HKUnit.minute())
            return [HKQuantitySample(type: t, quantity: HKQuantity(unit: unit, doubleValue: v), start: s, end: e, metadata: meta(r))]
        case "weight":
            guard let v = r.value, let t = HKQuantityType.quantityType(forIdentifier: .bodyMass) else { return [] }
            return [HKQuantitySample(type: t, quantity: HKQuantity(unit: HKUnit.gramUnit(with: .kilo), doubleValue: v), start: s, end: e, metadata: meta(r))]
        case "sleep":
            guard let t = HKObjectType.categoryType(forIdentifier: .sleepAnalysis) else { return [] }
            if let stages = r.extra?.stages, !stages.isEmpty {
                var out: [HKObject] = []
                for (i, st) in stages.enumerated() {
                    guard let ss = Fmt.parse(st.start), let se = Fmt.parse(st.end) else { continue }
                    out.append(HKCategorySample(type: t, value: sleepValue(st.stage).rawValue, start: ss, end: se, metadata: meta(r, "-\(i)")))
                }
                return out
            }
            return [HKCategorySample(type: t, value: HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue, start: s, end: e, metadata: meta(r))]
        default:
            return []
        }
    }

    private func saveWorkout(_ r: Rec) async throws {
        guard let s = Fmt.parse(r.start), let e = Fmt.parse(r.end) else { return }
        let (act, distId) = activity(r.extra?.exerciseType ?? 0)
        var dist: HKQuantity? = nil
        var energy: HKQuantity? = nil
        var assoc: [HKSample] = []
        if let d = r.extra?.distance_m, d > 0, let t = HKQuantityType.quantityType(forIdentifier: distId) {
            let q = HKQuantity(unit: .meter(), doubleValue: d)
            dist = q
            assoc.append(HKQuantitySample(type: t, quantity: q, start: s, end: e))
        }
        if let k = r.extra?.energy_kcal, k > 0, let t = HKQuantityType.quantityType(forIdentifier: .activeEnergyBurned) {
            let q = HKQuantity(unit: .kilocalorie(), doubleValue: k)
            energy = q
            assoc.append(HKQuantitySample(type: t, quantity: q, start: s, end: e))
        }
        let w = HKWorkout(
            activityType: act,
            start: s,
            end: e,
            duration: e.timeIntervalSince(s),
            totalEnergyBurned: energy,
            totalDistance: dist,
            metadata: meta(r)
        )
        try await store.save(w)
        if !assoc.isEmpty {
            try await store.addSamples(assoc, to: w)
        }
    }

    // MARK: Sincronización

    func sync() async {
        if busy { return }
        busy = true
        defer { busy = false }
        guard HKHealthStore.isHealthDataAvailable() else {
            status = "Salud no está disponible en este dispositivo"
            return
        }
        var last = UserDefaults.standard.integer(forKey: "lastSeq")
        var total = 0
        do {
            while true {
                let page = try await fetch(after: last)
                if page.items.isEmpty { break }
                var batch: [HKObject] = []
                for r in page.items {
                    if r.type == "workout" {
                        if !batch.isEmpty {
                            try await store.save(batch)
                            batch.removeAll()
                        }
                        try await saveWorkout(r)
                    } else {
                        batch.append(contentsOf: objects(for: r))
                    }
                }
                if !batch.isEmpty { try await store.save(batch) }
                last = page.next
                UserDefaults.standard.set(last, forKey: "lastSeq")
                total += page.items.count
                status = "Sincronizando… \(total) registros"
            }
            status = total == 0 ? "Todo al día" : "Listo: \(total) registros escritos en Salud"
        } catch {
            status = "Error: \(error.localizedDescription)"
        }
    }
}
