package com.difft.android.lint

import com.android.resources.ResourceFolderType
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.XmlContext
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.w3c.dom.Element
import java.util.EnumSet

/**
 * Forbids the raw button / switch / checkbox widgets outside `:base`.
 *
 * Each of those controls has exactly one visual source of truth — the `DifftButton` /
 * `DifftSwitch` / `DifftCheckBox` composable — plus an XML shell (`DifftButtonView` /
 * `DifftSwitchView` / `DifftCheckBoxView`) for View layouts. A raw platform, AppCompat, Material
 * or Material3 widget forks that look and drifts from the design, which is what the unification
 * series (#1203 / #1206 / #1208) removed. `:base` itself is exempt via `lint.xml`: it hosts the
 * composables and shells.
 *
 * Three surfaces, one issue id:
 *  - layout XML tags (`<Button>`, `<androidx.appcompat.widget.SwitchCompat>`, ...)
 *  - Material / Material3 composable calls (`Button(...)`, `Switch(...)`, `Checkbox(...)`)
 *  - widget constructors in code (`AppCompatButton(context)`)
 *
 * `TextButton` is deliberately not covered: there is no Difft text button yet.
 */
class DifftWidgetDetector : Detector(), Detector.XmlScanner, Detector.UastScanner {

    // ---------- Layout XML ----------

    override fun appliesTo(folderType: ResourceFolderType): Boolean = folderType == ResourceFolderType.LAYOUT

    override fun getApplicableElements(): Collection<String> = XML_TAGS.keys

    override fun visitElement(context: XmlContext, element: Element) {
        val replacement = XML_TAGS[element.tagName] ?: return
        context.report(
            issue = ISSUE,
            scope = element,
            location = context.getNameLocation(element),
            message = "Use `<$replacement>` instead of `<${element.tagName}>`: " +
                "the Difft shell is the single source of the control's look",
        )
    }

    // ---------- Compose calls ----------

    override fun getApplicableMethodNames(): List<String> = COMPOSABLES.keys.toList()

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val replacement = COMPOSABLES[method.name] ?: return
        val facade = method.containingClass?.qualifiedName ?: return
        if (facade !in COMPOSE_FACADES) return
        context.report(
            issue = ISSUE,
            scope = node,
            location = context.getLocation(node),
            message = "Use `$replacement` instead of `${method.name}` from `$facade`: " +
                "the Difft composable is the single source of the control's look",
        )
    }

    // ---------- Widget constructors ----------

    override fun getApplicableConstructorTypes(): List<String> = VIEW_CLASSES.keys.toList()

    override fun visitConstructor(context: JavaContext, node: UCallExpression, constructor: PsiMethod) {
        val owner = constructor.containingClass?.qualifiedName ?: return
        val replacement = VIEW_CLASSES[owner] ?: return
        context.report(
            issue = ISSUE,
            scope = node,
            location = context.getLocation(node),
            message = "Use `$replacement` instead of constructing `$owner`: " +
                "the Difft shell is the single source of the control's look",
        )
    }

    companion object {
        private const val BUTTON_VIEW = "com.difft.android.base.widget.DifftButtonView"
        private const val SWITCH_VIEW = "com.difft.android.base.widget.DifftSwitchView"
        private const val CHECKBOX_VIEW = "com.difft.android.base.widget.DifftCheckBoxView"

        /** Fully qualified widget classes, for constructor calls in code. */
        private val VIEW_CLASSES: Map<String, String> = mapOf(
            "android.widget.Button" to BUTTON_VIEW,
            "androidx.appcompat.widget.AppCompatButton" to BUTTON_VIEW,
            "com.google.android.material.button.MaterialButton" to BUTTON_VIEW,
            "android.widget.Switch" to SWITCH_VIEW,
            "androidx.appcompat.widget.SwitchCompat" to SWITCH_VIEW,
            "com.google.android.material.materialswitch.MaterialSwitch" to SWITCH_VIEW,
            "com.google.android.material.switchmaterial.SwitchMaterial" to SWITCH_VIEW,
            "android.widget.CheckBox" to CHECKBOX_VIEW,
            "androidx.appcompat.widget.AppCompatCheckBox" to CHECKBOX_VIEW,
            "com.google.android.material.checkbox.MaterialCheckBox" to CHECKBOX_VIEW,
        )

        /** Layout tags: the fully qualified classes plus the short `android.widget` forms. */
        private val XML_TAGS: Map<String, String> = VIEW_CLASSES + mapOf(
            "Button" to BUTTON_VIEW,
            "Switch" to SWITCH_VIEW,
            "CheckBox" to CHECKBOX_VIEW,
        )

        /** Composable names to the Difft composable that replaces them. */
        private val COMPOSABLES: Map<String, String> = mapOf(
            "Button" to "DifftButton",
            "OutlinedButton" to "DifftButton",
            "FilledTonalButton" to "DifftButton",
            "ElevatedButton" to "DifftButton",
            "Switch" to "DifftSwitch",
            "Checkbox" to "DifftCheckBox",
            "TriStateCheckbox" to "DifftCheckBox",
        )

        /** Facade classes Kotlin compiles the Material / Material3 top-level composables into. */
        private val COMPOSE_FACADES: Set<String> = setOf(
            "androidx.compose.material3.ButtonKt",
            "androidx.compose.material3.SwitchKt",
            "androidx.compose.material3.CheckboxKt",
            "androidx.compose.material.ButtonKt",
            "androidx.compose.material.SwitchKt",
            "androidx.compose.material.CheckboxKt",
        )

        val ISSUE: Issue = Issue.create(
            id = "DifftWidgetRequired",
            briefDescription = "Raw button/switch/checkbox widget instead of the Difft shell",
            explanation = """
                Buttons, switches and checkboxes each have one visual source of truth: the \
                `DifftButton` / `DifftSwitch` / `DifftCheckBox` composables in `:base`, with \
                `DifftButtonView` / `DifftSwitchView` / `DifftCheckBoxView` as their XML shells. \
                A raw platform, AppCompat, Material or Material3 widget forks that look and \
                drifts from the design. Only `:base` may use the raw widgets, to build the shells.
            """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(
                DifftWidgetDetector::class.java,
                EnumSet.of(Scope.JAVA_FILE, Scope.RESOURCE_FILE),
                Scope.JAVA_FILE_SCOPE,
                Scope.RESOURCE_FILE_SCOPE,
            ),
        )
    }
}
