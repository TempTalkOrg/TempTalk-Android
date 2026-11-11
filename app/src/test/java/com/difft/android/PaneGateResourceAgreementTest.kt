package com.difft.android

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.dualpane.StaleLayoutDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config








@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PaneGateResourceAgreementTest {

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `phone window — gate agrees with the inflated tree`() = assertGateAgreesWithInflation()

    @Test
    @Config(qualifiers = "w672dp-h480dp")
    fun `one dp below the width floor — gate agrees with the inflated tree`() =
        assertGateAgreesWithInflation()

    @Test
    @Config(qualifiers = "w673dp-h479dp")
    fun `one dp below the height floor — gate agrees with the inflated tree`() =
        assertGateAgreesWithInflation()

    @Test
    @Config(qualifiers = "w673dp-h480dp")
    fun `at the width and height floor — gate agrees with the inflated tree`() =
        assertGateAgreesWithInflation()

    @Test
    @Config(qualifiers = "w691dp-h716dp")
    fun `unfolded book foldable (Find N6 class) — gate agrees with the inflated tree`() =
        assertGateAgreesWithInflation()

    @Test
    @Config(qualifiers = "w840dp-h479dp")
    fun `wide but short (Z TriFold folded) — gate agrees with the inflated tree`() =
        assertGateAgreesWithInflation()

    @Test
    @Config(qualifiers = "w840dp-h1112dp")
    fun `Fold 8 unfolded portrait — gate agrees with the inflated tree`() =
        assertGateAgreesWithInflation()

    @Test
    @Config(qualifiers = "w899dp-h480dp")
    fun `top of the compact dual-pane band — gate agrees with the inflated tree`() =
        assertGateAgreesWithInflation()

    @Test
    @Config(qualifiers = "w900dp-h481dp")
    fun `tablet band — gate agrees with the inflated tree`() = assertGateAgreesWithInflation()

    @Test
    fun `the gate resolves both ways, so the agreement grid cannot pass vacuously`() {
        assertFalse(
            "R.bool.dual_pane_layout_active must resolve FALSE in a phone-sized configuration " +
                "(400dp x 800dp), from app/src/main/res/values/bools.xml. It did not, so every " +
                "agreement case above may be comparing two constants.",
            gateIn(screenWidthDp = 400, screenHeightDp = 800),
        )
        assertTrue(
            "R.bool.dual_pane_layout_active must resolve TRUE in a dual-pane-sized configuration " +
                "(840dp x 480dp), from app/src/main/res/values-w673dp-h480dp/bools.xml. It did " +
                "not — that directory was renamed or deleted, which makes the gate a constant " +
                "false while the dual-pane layout still inflates.",
            gateIn(screenWidthDp = 840, screenHeightDp = 480),
        )
    }

    @Test
    fun `the stale-layout detector's dual-pane floor is the qualifier's`() {
        val width = StaleLayoutDecision.DUAL_PANE_MIN_WIDTH_DP
        val height = StaleLayoutDecision.DUAL_PANE_MIN_HEIGHT_DP
        val message = "StaleLayoutDecision's (b) bound must match layout-w673dp-h480dp; update both together"
        assertTrue(message, gateIn(screenWidthDp = width, screenHeightDp = height))
        assertFalse(message, gateIn(screenWidthDp = width - 1, screenHeightDp = height))
        assertFalse(message, gateIn(screenWidthDp = width, screenHeightDp = height - 1))
    }

    





    private fun assertGateAgreesWithInflation() {
        val activity = Robolectric.buildActivity(Activity::class.java)
            .create().start().resume().get()
        activity.setContentView(R.layout.activity_index)

        val gate = activity.resources.getBoolean(R.bool.dual_pane_layout_active)
        val treeIsTwoPane = activity.findViewById<View>(R.id.list_pane) != null
        val configuration = activity.resources.configuration

        assertNotNull(
            "fragment_container_detail must exist in every activity_index.xml variant: the per-tab " +
                "detail stacks restore into it after a fold/unfold recreation " +
                "(${configuration.screenWidthDp}dp x ${configuration.screenHeightDp}dp).",
            activity.findViewById<View>(R.id.fragment_container_detail),
        )
        assertEquals(
            "R.bool.dual_pane_layout_active ($gate) MUST equal " +
                "findViewById(list_pane) != null ($treeIsTwoPane) at " +
                "${configuration.screenWidthDp}dp x ${configuration.screenHeightDp}dp. " +
                "app/src/main/res/values-w673dp-h480dp/bools.xml and " +
                "app/src/main/res/layout-w673dp-h480dp/activity_index.xml carry the same " +
                "qualifier set on purpose and MUST be renamed together. If a THIRD structural " +
                "activity_index.xml variant was added, a two-valued bool can no longer mirror " +
                "the layout selection and the gate needs generalising, not re-qualifying.",
            gate,
            treeIsTwoPane,
        )
    }

    





    private fun gateIn(screenWidthDp: Int, screenHeightDp: Int): Boolean {
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration).apply {
            this.screenWidthDp = screenWidthDp
            this.screenHeightDp = screenHeightDp
        }
        return application.createConfigurationContext(configuration)
            .resources
            .getBoolean(R.bool.dual_pane_layout_active)
    }
}
