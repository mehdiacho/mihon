package tachiyomi.domain.library.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class CoverBlurTest {

    @Test
    fun `adult genres are recognised regardless of case and spacing`() {
        CoverBlur.isAdult(listOf("Action", " Hentai ")) shouldBe true
        CoverBlur.isAdult(listOf("Romance", "SMUT")) shouldBe true
        CoverBlur.isAdult(listOf("Comedy", "Ecchi")) shouldBe false
        CoverBlur.isAdult(null) shouldBe false
    }

    @Test
    fun `nothing is blurred when the toggle is off`() {
        CoverBlur.shouldBlur(false, 1, listOf("Adult"), setOf("1"), emptySet()) shouldBe false
    }

    @Test
    fun `overrides beat the genre rule`() {
        CoverBlur.shouldBlur(true, 1, listOf("Comedy"), setOf("1"), emptySet()) shouldBe true
        CoverBlur.shouldBlur(true, 2, listOf("Adult"), emptySet(), setOf("2")) shouldBe false
        CoverBlur.shouldBlur(true, 3, listOf("Adult"), emptySet(), emptySet()) shouldBe true
    }

    @Test
    fun `choosing a state moves the id between the two sets`() {
        CoverBlur.override(5, true, emptySet(), setOf("5")) shouldBe (setOf("5") to emptySet())
        CoverBlur.override(5, false, setOf("5"), emptySet()) shouldBe (emptySet<String>() to setOf("5"))
    }
}
