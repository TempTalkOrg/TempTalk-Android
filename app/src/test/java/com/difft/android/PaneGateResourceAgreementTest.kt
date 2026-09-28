package com.difft.android

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins the dual-pane gate mirror: `R.bool.dual_pane_layout_active` answers exactly what
 * `activity_index.xml` inflates.
 *
 * The dual-pane gate is ENFORCED by the resource qualifier on
 * `app/src/main/res/layout-w673dp-h480dp/activity_index.xml`; runtime code derives
 * `isDualPaneMode` from the inflated tree (`findViewById(R.id.detail_pane) != null`) and any
 * code needing the EXPECTATION before/without a tree reads the bool from
 * `app/src/main/res/values-w673dp-h480dp/bools.xml`. That mirror is only sound while the two
 * resources are selected by the SAME qualifier set. Rename or delete one without the other
 * and the two answers diverge permanently — dual-pane branches running against a view tree
 * that has no detail pane, or vice versa. Nothing else in the repo pins the two directories
 * together, which is why this test exists.
 *
 * **What each case asserts.** Only the AGREEMENT: the gate must equal the inflated tree in
 * that configuration, in either direction.
 *
 * **Non-vacuity.** An agreement assertion passes trivially if both sides collapse to one
 * value — e.g. `values-w673dp-h480dp/` deleted, making the gate `false` everywhere, in a grid
 * that happened to inflate the phone layout everywhere too. The last case therefore asserts,
 * from one configuration-scoped `Resources` pair, that the bool actually resolves BOTH ways.
 *
 * **Why a vanilla [Activity].** `IndexActivity` is `@AndroidEntryPoint` and the app's
 * Robolectric tests deliberately never boot it; `setContentView(R.layout.activity_index)` on
 * a plain activity exercises the identical resolver path.
 */
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

    /**
     * Inflates `activity_index.xml` in the configuration the case declares and asserts the
     * resource gate equals what was actually inflated. Both sides are read from the SAME
     * [Activity], so they are answered by one `Resources` instance from one `Configuration` —
     * the very property the mirror relies on.
     */
    private fun assertGateAgreesWithInflation() {
        val activity = Robolectric.buildActivity(Activity::class.java)
            .create().start().resume().get()
        activity.setContentView(R.layout.activity_index)

        val gate = activity.resources.getBoolean(R.bool.dual_pane_layout_active)
        val treeHasDetailPane = activity.findViewById<View>(R.id.detail_pane) != null
        val configuration = activity.resources.configuration

        assertEquals(
            "R.bool.dual_pane_layout_active ($gate) MUST equal " +
                "findViewById(detail_pane) != null ($treeHasDetailPane) at " +
                "${configuration.screenWidthDp}dp x ${configuration.screenHeightDp}dp. " +
                "app/src/main/res/values-w673dp-h480dp/bools.xml and " +
                "app/src/main/res/layout-w673dp-h480dp/activity_index.xml carry the same " +
                "qualifier set on purpose and MUST be renamed together. If a THIRD structural " +
                "activity_index.xml variant was added, a two-valued bool can no longer mirror " +
                "the layout selection and the gate needs generalising, not re-qualifying.",
            gate,
            treeHasDetailPane,
        )
    }

    /**
     * Reads the gate from a configuration-scoped `Resources` — the one place this file needs a
     * hand-built [Configuration], because a single test method cannot carry two `@Config`
     * qualifier sets. Production never does this: runtime code reads the bool from its own
     * `resources`, which is what every case above exercises.
     */
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
