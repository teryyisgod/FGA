package io.github.fate_grand_automata.scripts.models

/**
 * A snapshot of everything the battle engine actually knows at one
 * decision point, captured for a future external AI battle advisor.
 *
 * Honesty rule: a field is only filled when the engine genuinely has
 * the value. Anything FGA does not read from the screen (ally/enemy HP,
 * NP gauge percentages, skill cooldowns, servant names, enemy count)
 * is emitted as JSON `null` (unknown), never guessed.
 *
 * The JSON is written by hand ([toJson]) instead of pulling a
 * serialization runtime into the log path: the shape is fixed, every
 * value is an enum name / number / boolean / null (no free text, so no
 * escaping hazards), and the capture must never be able to break the
 * battle it observes.
 */
data class BattleObservation(
    /** [EVENT_BATTLE_START] or [EVENT_TURN_DECISION]. */
    val event: String,
    /** 1-based wave/stage number (FGA's run-scoped stage counter). */
    val stage: Int,
    /** 1-based turn number within the current stage. */
    val turn: Int,
    /** The command cards dealt this turn; empty at battle start. */
    val cards: List<CardEntry>,
    /**
     * Field slots (A/B/C) whose NP the engine will fire together with
     * this turn's cards, per the AutoSkill script / spam settings.
     * This is the engine's plan, not a gauge reading.
     */
    val npQueued: List<String>,
    /** Cards to click before the first queued NP, when known. */
    val cardsBeforeNp: Int?,
    /** The priority target the engine locked on to (A/B/C), if any. */
    val chosenTarget: String?,
    /** Servants currently on the field. */
    val servants: List<ServantEntry>
) {
    data class CardEntry(
        /** Hand position 1-5. */
        val index: Int,
        /** Buster / Arts / Quick / Unknown. */
        val type: String,
        /** Weak / Normal / Resist. */
        val affinity: String,
        /** Owning servant's team slot (A-F) or "unknown". */
        val servant: String,
        /** Field slot (A/B/C) the owner stands on, if deployed. */
        val fieldSlot: String?,
        val stunned: Boolean
    )

    data class ServantEntry(
        /** Field slot (A/B/C). */
        val fieldSlot: String,
        /** Team slot (A-F) or "unknown". */
        val teamSlot: String,
        val isSupport: Boolean
        // HP and NP gauge are not read by FGA: always null in JSON.
    )

    fun toJson(): String = buildString {
        append("{\"version\":0")
        append(",\"event\":\"").append(event).append('"')
        append(",\"stage\":").append(stage)
        append(",\"turn\":").append(turn)
        append(",\"cards\":[")
        cards.forEachIndexed { i, card ->
            if (i > 0) append(',')
            append("{\"index\":").append(card.index)
            append(",\"type\":\"").append(card.type).append('"')
            append(",\"affinity\":\"").append(card.affinity).append('"')
            append(",\"servant\":\"").append(card.servant).append('"')
            append(",\"field_slot\":")
            append(card.fieldSlot?.let { "\"$it\"" } ?: "null")
            append(",\"stunned\":").append(card.stunned)
            append('}')
        }
        append(']')
        append(",\"np_queued\":[")
        npQueued.forEachIndexed { i, slot ->
            if (i > 0) append(',')
            append('"').append(slot).append('"')
        }
        append(']')
        append(",\"cards_before_np\":").append(cardsBeforeNp?.toString() ?: "null")
        append(",\"chosen_target\":")
        append(chosenTarget?.let { "\"$it\"" } ?: "null")
        append(",\"servants\":[")
        servants.forEachIndexed { i, servant ->
            if (i > 0) append(',')
            append("{\"field_slot\":\"").append(servant.fieldSlot).append('"')
            append(",\"team_slot\":\"").append(servant.teamSlot).append('"')
            append(",\"is_support\":").append(servant.isSupport)
            append(",\"hp\":null,\"np_percent\":null}")
        }
        append(']')
        // Not obtainable from the screen by any current FGA mechanism.
        append(",\"enemies\":{\"count\":null,\"hp\":null}")
        append(",\"skills\":null")
        append('}')
    }

    companion object {
        const val EVENT_BATTLE_START = "battle_start"
        const val EVENT_TURN_DECISION = "turn_decision"
    }
}

/** Stable slot labels (reflection-free: release builds obfuscate class names). */
fun TeamSlot.label(): String = when (this) {
    TeamSlot.A -> "A"
    TeamSlot.B -> "B"
    TeamSlot.C -> "C"
    TeamSlot.D -> "D"
    TeamSlot.E -> "E"
    TeamSlot.F -> "F"
    TeamSlot.Unknown -> "unknown"
}

/** Stable slot labels (reflection-free: release builds obfuscate class names). */
fun FieldSlot.label(): String = when (this) {
    FieldSlot.A -> "A"
    FieldSlot.B -> "B"
    FieldSlot.C -> "C"
}

/** Stable slot labels (reflection-free: release builds obfuscate class names). */
fun EnemyTarget.label(): String = when (this) {
    EnemyTarget.A -> "A"
    EnemyTarget.B -> "B"
    EnemyTarget.C -> "C"
}
