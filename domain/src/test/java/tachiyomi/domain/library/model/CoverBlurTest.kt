package tachiyomi.domain.library.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class CoverBlurTest {

    private val on = CoverBlur.Rules(enabled = true)

    @Test
    fun `adult genres are recognised regardless of case and spacing`() {
        CoverBlur.isAdult(listOf("Action", " Hentai ")) shouldBe true
        CoverBlur.isAdult(listOf("Romance", "SMUT")) shouldBe true
        CoverBlur.isAdult(listOf("Comedy", "Ecchi")) shouldBe false
        CoverBlur.isAdult(null) shouldBe false
    }

    @Test
    fun `nothing is blurred when the toggle is off`() {
        val rules = CoverBlur.Rules(
            enabled = false,
            forced = setOf("1"),
            sourcesForced = setOf("9"),
            nsfwSourceIds = setOf(9),
        )
        rules.shouldBlur(1, 9, listOf("Adult")) shouldBe false
        rules.sourceBlurred(9) shouldBe null
    }

    @Test
    fun `an nsfw source blurs a series with no adult tags`() {
        val rules = on.copy(nsfwSourceIds = setOf(9))
        rules.shouldBlur(1, 9, listOf("Romance")) shouldBe true
        rules.shouldBlur(1, 9, null) shouldBe true
        rules.shouldBlur(1, 8, listOf("Romance")) shouldBe false
        rules.shouldBlur(1, 8, listOf("Adult")) shouldBe true
    }

    @Test
    fun `a source set by hand beats the automatic rule`() {
        on.copy(nsfwSourceIds = setOf(9), sourcesExempt = setOf("9")).shouldBlur(1, 9, listOf("Adult")) shouldBe false
        on.copy(sourcesForced = setOf("8")).shouldBlur(1, 8, listOf("Comedy")) shouldBe true
        on.copy(sourcesForced = setOf("8")).sourceBlurred(8) shouldBe true
        on.copy(nsfwSourceIds = setOf(9), sourcesExempt = setOf("9")).sourceBlurred(9) shouldBe false
    }

    @Test
    fun `a series set by hand beats its source`() {
        on.copy(sourcesExempt = setOf("9"), forced = setOf("1")).shouldBlur(1, 9, null) shouldBe true
        on.copy(sourcesForced = setOf("9"), exempt = setOf("1")).shouldBlur(1, 9, null) shouldBe false
    }

    @Test
    fun `choosing a state moves the ids between the two sets`() {
        CoverBlur.override(listOf(5, 6), true, emptySet(), setOf("5")) shouldBe (setOf("5", "6") to emptySet())
        CoverBlur.override(listOf(5), false, setOf("5", "6"), emptySet()) shouldBe (setOf("6") to setOf("5"))
    }
}
