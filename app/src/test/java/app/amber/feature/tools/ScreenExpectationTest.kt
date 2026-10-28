package app.amber.feature.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四类动作后验收条件的纯判定。
 *
 * 这里锁的是"验收语义"本身：条件不满足时不得给出通过、目标不唯一时交回、
 * absent 不得对"从未出现过"的目标假通过、value_equals 不得命中密码框。
 * 不涉及手势注入与真实无障碍服务。
 */
class ScreenExpectationTest {

    private class FakeProbe(
        private val texts: Map<String, List<Boolean>>,
        private val editable: List<String> = emptyList(),
    ) : ScreenExpectationProbe {
        override fun matchingTexts(query: String): List<ProbeTextMatch> =
            texts[query].orEmpty().map { ProbeTextMatch(enabled = it) }

        override fun editableValues(): List<String> = editable
    }

    private fun evaluate(
        probe: ScreenExpectationProbe,
        kind: String,
        target: String = "",
        anchor: String = "",
        value: String = "",
        targetWasVisibleBefore: Boolean = false,
    ) = evaluateScreenExpectation(probe, kind, target, anchor, value, targetWasVisibleBefore)

    @Test
    fun visiblePassesOnlyWhenTextIsPresent() {
        val present = evaluate(FakeProbe(mapOf("Saved" to listOf(true))), "visible", "Saved")
        assertTrue(present.met)
        assertEquals("visible", present.detail)

        val missing = evaluate(FakeProbe(emptyMap()), "visible", "Saved")
        assertFalse(missing.met)
        assertEquals("not_visible", missing.detail)
    }

    @Test
    fun enabledRequiresTheSingleVisibleTargetToBeEnabled() {
        val enabled = evaluate(FakeProbe(mapOf("Send" to listOf(true))), "enabled", "Send")
        assertTrue(enabled.met)

        val disabled = evaluate(FakeProbe(mapOf("Send" to listOf(false))), "enabled", "Send")
        assertFalse(disabled.met)
        assertEquals("disabled", disabled.detail)

        // 多命中不猜：交回而不是挑一个
        val ambiguous = evaluate(FakeProbe(mapOf("Send" to listOf(true, false))), "enabled", "Send")
        assertFalse(ambiguous.met)
        assertTrue(ambiguous.ambiguous)
        assertEquals("ambiguous_target", ambiguous.detail)
    }

    @Test
    fun absentRequiresPriorVisibilityOfTheTarget() {
        // 目标从未出现过：不能因为"现在没找到"就判通过
        val neverSeen = evaluate(
            FakeProbe(mapOf("Done" to listOf(true))),
            "absent",
            target = "Dialog",
            anchor = "Done",
            targetWasVisibleBefore = false,
        )
        assertFalse(neverSeen.met)
        assertEquals("target_not_visible_before_action", neverSeen.detail)

        val gone = evaluate(
            FakeProbe(mapOf("Done" to listOf(true))),
            "absent",
            target = "Dialog",
            anchor = "Done",
            targetWasVisibleBefore = true,
        )
        assertTrue(gone.met)
        assertEquals("absent", gone.detail)
    }

    @Test
    fun absentStillFailsWhenTargetRemainsOrAnchorDisappears() {
        val stillThere = evaluate(
            FakeProbe(mapOf("Dialog" to listOf(true), "Done" to listOf(true))),
            "absent",
            target = "Dialog",
            anchor = "Done",
            targetWasVisibleBefore = true,
        )
        assertFalse(stillThere.met)
        assertEquals("target_still_visible", stillThere.detail)

        val anchorGone = evaluate(
            FakeProbe(emptyMap()),
            "absent",
            target = "Dialog",
            anchor = "Done",
            targetWasVisibleBefore = true,
        )
        assertFalse(anchorGone.met)
        assertEquals("anchor_not_visible", anchorGone.detail)
    }

    @Test
    fun absentWithoutAnchorIsRejected() {
        val verdict = evaluate(
            FakeProbe(emptyMap()),
            "absent",
            target = "Dialog",
            anchor = "",
            targetWasVisibleBefore = true,
        )
        assertFalse(verdict.met)
        assertEquals("missing_anchor", verdict.detail)
    }

    @Test
    fun valueEqualsMatchesExactlyOneNonPasswordField() {
        val ok = evaluate(FakeProbe(emptyMap(), editable = listOf("hello")), "value_equals", value = "hello")
        assertTrue(ok.met)

        val mismatch = evaluate(FakeProbe(emptyMap(), editable = listOf("hello")), "value_equals", value = "hell")
        assertFalse(mismatch.met)
        assertEquals("value_mismatch", mismatch.detail)
    }

    /** 密码框的原始值不进入 probe；显示遮罩不是可验收的输入值。 */
    @Test
    fun valueEqualsNeverMatchesTheMaskedPasswordField() {
        val verdict = evaluate(
            FakeProbe(emptyMap()),
            "value_equals",
            value = MASKED_EDITABLE_LABEL,
        )
        assertFalse(verdict.met)
        assertEquals("value_mismatch", verdict.detail)
    }

    @Test
    fun valueEqualsRequiresAValue() {
        val verdict = evaluate(FakeProbe(emptyMap(), editable = listOf("hello")), "value_equals", value = "")
        assertFalse(verdict.met)
        assertEquals("missing_value", verdict.detail)
    }

    @Test
    fun unknownKindIsReportedNotGuessed() {
        val verdict = evaluate(FakeProbe(emptyMap()), "contains", "x")
        assertFalse(verdict.met)
        assertEquals("unsupported_kind", verdict.detail)
    }
}
