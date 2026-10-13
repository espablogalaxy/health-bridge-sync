import HealthKit

extension HKHealthStore {
    /// Versión async de add(_:to:completion:) para asociar muestras a un entrenamiento.
    func addSamples(_ samples: [HKSample], to workout: HKWorkout) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            self.add(samples, to: workout) { _, error in
                if let error = error {
                    cont.resume(throwing: error)
                } else {
                    cont.resume()
                }
            }
        }
    }
}
