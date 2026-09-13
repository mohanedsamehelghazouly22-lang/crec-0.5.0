package com.mohaned.clashoverlay.core.engine

import com.mohaned.clashoverlay.core.model.TrackedCard

/**
 * Models the four-card visible hand/cycle
 * without ever issuing game input.
 *
 * This class is observation-only.
 */
class CycleEngine {

    private val playedOrder = ArrayDeque<String>()

    private var confidence = 0f

    fun reset() {
        playedOrder.clear()
        confidence = 0f
    }

    /**
     * Records a card that was observed as played.
     */
    fun observePlayed(
        cardId: String,
        confidence: Float
    ) {
        if (cardId.isBlank()) return

        // Remove an older occurrence first.
        playedOrder.remove(cardId)

        // The newest observation goes to the back.
        playedOrder.add(cardId)

        // Keep a bounded history.
        while (playedOrder.size > 8) {
            playedOrder.removeFirst()
        }

        // Smooth confidence over time.
        this.confidence =
            (
                this.confidence * 0.75f +
                confidence.coerceIn(0f, 1f) * 0.25f
            ).coerceIn(0f, 1f)
    }

    /**
     * Updates cycle confidence from the currently
     * observed hand.
     */
    fun updateFromHand(
        hand: List<TrackedCard>
    ) {
        if (hand.isEmpty()) return

        val observed =
            hand.map { it.cardId }.toSet()

        val consistency =
            hand.map { it.confidence }
                .average()
                .toFloat()
                .coerceIn(0f, 1f)

        val duplicatePenalty =
            if (observed.size == hand.size) {
                1f
            } else {
                0.65f
            }

        confidence =
            (
                (confidence * 0.6f) +
                (consistency * 0.4f)
            ) * duplicatePenalty

        confidence =
            confidence.coerceIn(0f, 1f)
    }

    /**
     * Production prediction.
     *
     * knownDeck = cards known to belong to the opponent deck.
     * hand      = cards currently visible in the opponent hand.
     */
    fun predictedNext(
        knownDeck: List<TrackedCard>,
        hand: List<TrackedCard>
    ): String? {

        val inHand =
            hand.map { it.cardId }.toSet()

        return knownDeck
            .asSequence()
            .filter {
                it.cardId !in inHand
            }
            .sortedBy { card ->

                val index =
                    playedOrder.indexOf(card.cardId)

                if (index < 0) {
                    Int.MAX_VALUE
                } else {
                    index
                }
            }
            .firstOrNull()
            ?.cardId
    }

    /**
     * Compatibility/helper overload.
     *
     * Used when the caller only has cycle observations
     * and does not yet have a complete known deck/hand.
     *
     * Example:
     *
     * a -> b -> a
     *
     * The current cycle order becomes:
     *
     * b -> a
     *
     * Therefore the next candidate is b.
     */
    fun predictedNext(): String? {
        return playedOrder.firstOrNull()
    }

    /**
     * Current position inside the four-card cycle.
     */
    fun index(): Int {
        return playedOrder.size % 4
    }

    /**
     * Current smoothed confidence.
     */
    fun confidence(): Float {
        return confidence.coerceIn(0f, 1f)
    }
}
