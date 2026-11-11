package com.difft.android.base.utils.pane

import com.difft.android.test.guards.HostedSourceGuards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test






class HostedSourceGuardsTest {

    @Test
    fun `destinations name their fragment and shell`() {
        val source = """
            class FooDestination(private val id: String) : DetailDestination {
                override fun newFragment(): Fragment = FooFragment.newInstance(id)
                override fun shellIntent(context: Context): Intent = FooActivity.intent(context, id)
            }
            class BarDestination : DetailDestination {
                override fun newFragment() = BarFragment.newInstance()
                override fun shellIntent(context: Context): Intent = Intent(context, BarActivity::class.java)
            }
        """.trimIndent()
        assertEquals(listOf("FooFragment", "BarFragment"), HostedSourceGuards.destinationFragments(source))
        assertEquals(listOf("FooActivity", "BarActivity"), HostedSourceGuards.destinationShells(source))
        assertTrue(HostedSourceGuards.destinationFragments("class X { fun newFragment() = Y.newInstance() }").isEmpty())
    }

    @Test
    fun `other newFragment forms are read, an unreadable one fails`() {
        val forms = """
            class A : DetailDestination {
                override fun newFragment(): Fragment = AFragment()
            }
            class B : DetailDestination {
                override fun newFragment(): Fragment {
                    return BFragment.newInstance(id)
                }
            }
            class C : DetailDestination {
                override fun newFragment(): Fragment =
                    CFragment.newInstance(
                        id,
                    )
            }
        """.trimIndent()
        assertEquals(listOf("AFragment", "BFragment", "CFragment"), HostedSourceGuards.destinationFragments(forms))

        val unreadable = """
            class D : DetailDestination {
                override fun newFragment(): Fragment {
                    val page = DFragment()
                    return page
                }
            }
        """.trimIndent()
        assertThrows(IllegalStateException::class.java) { HostedSourceGuards.destinationFragments(unreadable) }
    }

    @Test
    fun `top-level declarations skip private ones`() {
        val source = """
            internal object FooDialogs
            fun Fragment.fooRows() = Unit
            private fun hidden() = Unit
            class FooBinder(
        """.trimIndent()
        assertEquals(setOf("FooDialogs", "fooRows", "FooBinder"), HostedSourceGuards.topLevelDeclarations(source))
    }

    @Test
    fun `Activity-owned APIs are rejected, comments ignored`() {
        listOf(
            "val id = requireActivity().intent.getStringExtra(\"id\")",
            "val id = activity?.intent?.getStringExtra(\"id\")",
            "Box(Modifier.systemBarsPadding())",
            "Box(Modifier.statusBarsPadding())",
            "startActivityForResult(intent, 1)",
            "requireActivity().setResult(RESULT_OK)",
            "pager.adapter = object : FragmentStateAdapter(requireActivity()) {}",
            "pager.adapter = object : FragmentStateAdapter(activity!!) {}",
        ).forEach { line -> assertEquals(line, 1, HostedSourceGuards.forbiddenCalls(line).size) }
        assertTrue(HostedSourceGuards.forbiddenCalls("// requireActivity().intent").isEmpty())
        assertTrue(HostedSourceGuards.forbiddenCalls("FragmentStateAdapter(this)").isEmpty())
    }

    @Test
    fun `starting a destination's shell directly is rejected`() {
        val shells = setOf("FooActivity")
        assertEquals(1, HostedSourceGuards.shellStarts("FooActivity.startActivity(requireActivity(), id)", shells).size)
        assertEquals(1, HostedSourceGuards.shellStarts("FooActivity.start(context, id)", shells).size)
        assertTrue(HostedSourceGuards.shellStarts("detailNavigation().push(this, FooDestination(id))", shells).isEmpty())
    }

    @Test
    fun `a push must pass the asking page`() {
        assertEquals(1, HostedSourceGuards.pushesWithoutFrom("detailNavigation().push(FooDestination(id))").size)
        assertEquals(1, HostedSourceGuards.pushesWithoutFrom("card.detailNavigationUnderDialog()?.push(dest)").size)
        assertTrue(HostedSourceGuards.pushesWithoutFrom("detailNavigation().push(this, FooDestination(id))").isEmpty())
        assertTrue(HostedSourceGuards.pushesWithoutFrom("it.detailNavigation().push(it, FooDestination(a(b), c))").isEmpty())
    }

    @Test
    fun `a Compose page must consume the container's insets`() {
        val compose = """
            override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
                ComposeView(requireContext()).apply { setContent { Content() } }

            override fun onViewCreated(view: View, s: Bundle?) = Unit
        """.trimIndent()
        assertTrue(HostedSourceGuards.composeWithoutContainerInsets(compose))
        assertFalse(HostedSourceGuards.composeWithoutContainerInsets(compose + "\nModifier.detailPageInsets(padsBottom)"))
        assertFalse(HostedSourceGuards.composeWithoutContainerInsets(compose + "\nModifier.consumeContainerInsets(padsBottom)"))
        val xml = """
            override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View = binding.root

            private fun header() = ComposeView(requireContext())
        """.trimIndent()
        assertFalse("an XML page with a Compose part is not a Compose page", HostedSourceGuards.composeWithoutContainerInsets(xml))
    }

    @Test
    fun `wait utilities release by claim and hosted calls pass their wait`() {
        assertEquals(1, HostedSourceGuards.noArgDismissWaits("ComposeDialogManager.dismissWait()").size)
        assertTrue(HostedSourceGuards.noArgDismissWaits("ComposeDialogManager.dismissWait(token)").isEmpty())
        val functions = listOf("saveToNotes", "saveWithUI")
        val missing = """
            selectChatsUtils.saveToNotes(
                requireActivity(),
                content,
                sourceConversation = chatViewModel.forWhat,
            )
        """.trimIndent()
        assertEquals(1, HostedSourceGuards.callsWithoutWait(missing, functions).size)
        assertTrue(HostedSourceGuards.callsWithoutWait("SaveAttachmentUtil.saveWithUI(ctx, a, wait = saveWait)", functions).isEmpty())
        assertTrue(HostedSourceGuards.callsWithoutWait("suspend fun saveWithUI(context: Context) {}", functions).isEmpty())
    }

    @Test
    fun `a hosted DifftTheme passes false or ownsWindow`() {
        assertEquals(1, HostedSourceGuards.windowBackgroundViolations("DifftTheme(applyWindowBackground = !isPopupMode) {").size)
        assertEquals(1, HostedSourceGuards.windowBackgroundViolations("DifftTheme { Content() }").size)
        assertEquals(1, HostedSourceGuards.windowBackgroundViolations("DifftTheme(useFlatBackground = true) {").size)
        assertTrue(HostedSourceGuards.windowBackgroundViolations("DifftTheme(applyWindowBackground = false) {").isEmpty())
        assertTrue(HostedSourceGuards.windowBackgroundViolations("DifftTheme(applyWindowBackground = !isPopupMode && ownsWindow()) {").isEmpty())
        assertTrue(
            HostedSourceGuards.windowBackgroundViolations(
                "DifftTheme(\n    useFlatBackground = true,\n    applyWindowBackground = ownsWindow(),\n) {"
            ).isEmpty()
        )
        assertTrue(HostedSourceGuards.windowBackgroundViolations("size = DifftTheme.spacing.avatarLarge").isEmpty())
        val preview = "@Preview(showBackground = true)\n@Composable\nprivate fun CardPreview() {\n    DifftTheme {\n        Card()\n    }\n}"
        assertTrue("previews never run hosted", HostedSourceGuards.windowBackgroundViolations(preview).isEmpty())
        assertEquals(1, HostedSourceGuards.windowBackgroundViolations("@Composable\nfun Card() {\n    DifftTheme {\n    }\n}").size)
        assertTrue(HostedSourceGuards.windowBackgroundViolations("DifftTheme(applyWindowBackground = ownsWindow()) {").isEmpty())
        assertEquals("inverse", 1, HostedSourceGuards.windowBackgroundViolations("DifftTheme(applyWindowBackground = !ownsWindow()) {").size)
        assertEquals(1, HostedSourceGuards.windowBackgroundViolations("DifftTheme(applyWindowBackground = a && !ownsWindow()) {").size)
        assertEquals(1, HostedSourceGuards.windowBackgroundViolations("DifftTheme(applyWindowBackground = a || ownsWindow()) {").size)
    }
}
