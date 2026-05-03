package com.gridgain.demo.datagen.generation

import com.gridgain.demo.datagen.config.WeightedChoice
import com.gridgain.demo.datagen.errors.MisconfigurationException
import java.util.Random

class WeightedChoiceValueSource(
    choices: List<WeightedChoice>,
    seed: Long,
) : ValueSource {

    init {
        if (choices.isEmpty()) {
            throw MisconfigurationException(
                "weighted-choice value source requires at least one choice; received zero. " +
                "Add at least one entry under 'choices'."
            )
        }
        choices.forEach {
            if (it.weight <= 0.0) {
                throw MisconfigurationException(
                    "weighted-choice value source has non-positive weight ${it.weight} for value '${it.value}'. " +
                    "Every choice must have a strictly positive weight."
                )
            }
        }
    }

    private val totalWeight: Double = choices.sumOf { it.weight }
    private val cumulative: List<Pair<Double, Any>> =
        choices.runningFold(0.0 to (Unit as Any)) { acc, c ->
            (acc.first + c.weight) to c.value
        }.drop(1)
    private val random: Random = Random(seed)

    override fun next(ctx: GenerationContext): Any {
        val r = random.nextDouble() * totalWeight
        return cumulative.first { r < it.first }.second
    }
}
