package com.hermesmarcano.zen.core

/** Compara la foto candidata con las referencias de un ancla. Puro: el decodificado de JPEG vive en la capa Android. */
object PhotoVerifier {
    sealed interface Outcome {
        /** La foto candidata (o todas las referencias) no tiene contenido útil: muy oscura, tapada o lisa. */
        data object Unreadable : Outcome

        data class Compared(val bestDistance: Float, val isMatch: Boolean) : Outcome
    }

    fun verify(
        candidate: GrayImage,
        references: List<GrayImage>,
        threshold: Float = PhotoMatchPolicy.DEFAULT_THRESHOLD,
    ): Outcome {
        val candidateDescriptor = PhotoDescriptor.from(candidate) ?: return Outcome.Unreadable
        val distances = references.mapNotNull { PhotoDescriptor.from(it) }.map { candidateDescriptor.distanceTo(it) }
        // Distancias de orientación incomparable cuentan como "no coincide", pero hay que distinguirlas
        // de "no hay referencias legibles", que no debe poder aprobar nada.
        val best = PhotoMatchPolicy.bestDistance(distances) ?: return Outcome.Unreadable
        return Outcome.Compared(best, PhotoMatchPolicy.isMatch(distances, threshold))
    }
}
