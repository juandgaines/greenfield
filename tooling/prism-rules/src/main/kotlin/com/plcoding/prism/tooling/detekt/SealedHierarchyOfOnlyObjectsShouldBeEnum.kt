package com.plcoding.prism.tooling.detekt

import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Reports a sealed hierarchy that is an enum class written the long way.
 *
 * When every subtype visible in the file is a member-less `object` and the
 * parent declares no members either, the hierarchy carries no per-case state or
 * behaviour — exactly what `enum class` expresses in one declaration. `*Action`
 * and `*Event` hierarchies stay sealed by the MVI contract, and a generic
 * hierarchy cannot become an enum, so both are exempt. A hierarchy whose
 * subtypes live in other files is invisible to this per-file check and never
 * fires.
 */
class SealedHierarchyOfOnlyObjectsShouldBeEnum(config: Config) :
    Rule(
        config,
        "A sealed hierarchy of member-less objects is an enum class written the long way.",
    ) {
    @Configuration("name suffixes of sealed hierarchies that must stay sealed")
    private val exemptSuffixes: List<String> by config(listOf("Action", "Event"))

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (!klass.hasModifier(KtTokens.SEALED_KEYWORD)) return
        val parentName = klass.name ?: return
        if (exemptSuffixes.any { suffix -> parentName.endsWith(suffix) }) return
        if (klass.typeParameters.isNotEmpty()) return
        if (declaresMembers(klass)) return

        val subtypes =
            klass.containingKtFile
                .collectDescendantsOfType<KtClassOrObject> { candidate ->
                    candidate !== klass && extendsParent(candidate, parentName)
                }
        if (subtypes.isEmpty()) return
        if (subtypes.any { subtype -> subtype !is KtObjectDeclaration || declaresMembers(subtype) }) return

        report(
            Finding(
                Entity.from(klass),
                "Every subtype of '$parentName' is a member-less object. " +
                    "Declare it as an enum class instead of a sealed hierarchy.",
            ),
        )
    }

    private fun declaresMembers(declaration: KtClassOrObject): Boolean =
        declaration.body
            ?.declarations
            .orEmpty()
            .any { member -> member !is KtClassOrObject }

    private fun extendsParent(candidate: KtClassOrObject, parentName: String): Boolean =
        candidate.superTypeListEntries.any { entry ->
            val userType = entry.typeReference?.typeElement as? KtUserType
            userType?.referencedName == parentName
        }
}
