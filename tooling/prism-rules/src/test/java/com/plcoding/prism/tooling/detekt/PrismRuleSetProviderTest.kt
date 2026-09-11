package com.plcoding.prism.tooling.detekt

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.junit.jupiter.api.Test
import java.util.ServiceLoader

class PrismRuleSetProviderTest {
    @Test
    fun `is discoverable through the service loader`() {
        // detekt finds rule sets only through META-INF/services. Without that file it
        // loads the jar, finds nothing, and reports a clean tree — the exact silent
        // pass this whole gate exists to prevent, so the registration is worth a test.
        val provider =
            ServiceLoader
                .load(RuleSetProvider::class.java, javaClass.classLoader)
                .firstOrNull { it.ruleSetId == RuleSetId("prism") }

        assertThat(provider).isNotNull()
    }

    @Test
    fun `supplies every rule the root config activates`() {
        val ruleSet = PrismRuleSetProvider().instance()

        assertThat(ruleSet.id).isEqualTo(RuleSetId("prism"))
        assertThat(ruleSet.rules.keys.map { it.value }).containsExactlyInAnyOrder(
            "NonAtomicStateFlowAssignment",
            "SingleLetterIdentifier",
            "NoConsoleLogging",
            "ThemeColorDirectUse",
            "UnwiredThemeColor",
            "IconContentDescription",
            "IconButtonHardcodedSize",
            "NoOpenClassInMain",
            "TextFieldStateInViewModel",
            "ObserveAsEventsRequired",
            "NoPublicMutableFlow",
            "NoRunBlockingInMain",
            "NoNotNullAssertion",
            "HardcodedUserFacingString",
            "RawColorLiteral",
            "NoBareIconClickable",
            "NoComposableModifierExtension",
            "CallbackFlowRequiresAwaitClose",
            "KtorCallMustUseSafeCall",
            "SuspendGenericCatchSwallowsCancellation",
            "SealedHierarchyOfOnlyObjectsShouldBeEnum",
            "NoImplSuffix",
            "NoResultListOfErrors",
            "EmptyResultOverResultUnit",
            "StateInViewModelRequiresWhileSubscribed",
            "SharedFlowRequiresExplicitBuffer",
            "OneShotFlowBuilder",
            "CustomScopeRequiresSupervisorJob",
            "CollectAsStateWithoutLifecycle",
            "WorkerResultTypealiasRequired",
            "UniqueWorkNameIsSnakeCase",
            "StartForegroundViaServiceCompat",
            "DispatchersSetMainWithoutReset",
            "InMemoryRoomNotClosed",
            "RouteMustBeSerializable",
            "RouteMustBeDataObjectOrDataClass",
            "NavGraphBuilderExtensionNaming",
            "NoCrossFeatureRouteImport",
            "AdaptiveLayoutOwnsNoState",
            "KoinModuleNaming",
            "PreferConstructorReferenceKoinDefinition",
            "StartKoinOnlyInAppModule",
            "HttpClientMustAcceptEngine",
            "HttpClientConstructionOutsideFactory",
            "NoSharedPreferencesForTokens",
            "JUnit4InJvmUnitTest",
            "JUnit5InInstrumentedTest",
            "NonAssertKAssertion",
            "MockingLibraryInTest",
            "RobolectricInTest",
            "JvmComposeTestRule",
            "ThreadSleepOrEspressoIdleInTest",
            "ReflectionInTest",
            "RoomInJvmUnitTest",
            "ModifierParameterDefaultIsModifier",
            "ModifierParameterAfterRequiredParameters",
            "ComposableSlotParameterAfterModifier",
            "NoPreviewParameterAnnotation",
            "PreviewMustBePrivate",
            "PreviewFunctionNaming",
            "PreviewMustWrapInTheme",
            "NoCustomCompositionLocal",
            "StableAnnotationOnUnstableState",
            "ScreenStateOnlyInScreenComposable",
            "RootAndScreenInSameFile",
            "KoinViewModelOnlyInRoot",
            "ObserveAsEventsOnlyInRoot",
            "RootComposableMustDefaultViewModel",
            "EventChannelExposedAsReceiveAsFlow",
            "ScreenComposableParameterOrder",
            "CrammedBlockBody",
            "ClassBodyMissingLeadingBlankLine",
        )
    }
}
