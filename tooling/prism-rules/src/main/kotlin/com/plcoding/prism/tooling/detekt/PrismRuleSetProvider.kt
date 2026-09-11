package com.plcoding.prism.tooling.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

/**
 * Supplies the project-specific rule set.
 *
 * detekt finds this class through
 * `META-INF/services/dev.detekt.api.RuleSetProvider`. Without that file detekt
 * loads the jar and silently finds nothing, so a rule added here must also be
 * listed in `detekt.yml` under the `prism` id — with `config.validation` on, a
 * name that does not match fails the build.
 */
class PrismRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("prism")

    override fun instance() =
        RuleSet(
            ruleSetId,
            listOf(
                ::NonAtomicStateFlowAssignment,
                ::SingleLetterIdentifier,
                ::NoConsoleLogging,
                ::ThemeColorDirectUse,
                ::UnwiredThemeColor,
                ::IconContentDescription,
                ::IconButtonHardcodedSize,
                ::NoOpenClassInMain,
                ::TextFieldStateInViewModel,
                ::ObserveAsEventsRequired,
                ::NoPublicMutableFlow,
                ::NoRunBlockingInMain,
                ::NoNotNullAssertion,
                ::HardcodedUserFacingString,
                ::RawColorLiteral,
                ::NoBareIconClickable,
                ::NoComposableModifierExtension,
                ::CallbackFlowRequiresAwaitClose,
                ::KtorCallMustUseSafeCall,
                ::SuspendGenericCatchSwallowsCancellation,
                ::SealedHierarchyOfOnlyObjectsShouldBeEnum,
                ::NoImplSuffix,
                ::NoResultListOfErrors,
                ::EmptyResultOverResultUnit,
                ::StateInViewModelRequiresWhileSubscribed,
                ::SharedFlowRequiresExplicitBuffer,
                ::OneShotFlowBuilder,
                ::CustomScopeRequiresSupervisorJob,
                ::CollectAsStateWithoutLifecycle,
                ::WorkerResultTypealiasRequired,
                ::UniqueWorkNameIsSnakeCase,
                ::StartForegroundViaServiceCompat,
                ::DispatchersSetMainWithoutReset,
                ::InMemoryRoomNotClosed,
                ::RouteMustBeSerializable,
                ::RouteMustBeDataObjectOrDataClass,
                ::NavGraphBuilderExtensionNaming,
                ::NoCrossFeatureRouteImport,
                ::AdaptiveLayoutOwnsNoState,
                ::KoinModuleNaming,
                ::PreferConstructorReferenceKoinDefinition,
                ::StartKoinOnlyInAppModule,
                ::HttpClientMustAcceptEngine,
                ::HttpClientConstructionOutsideFactory,
                ::NoSharedPreferencesForTokens,
                ::JUnit4InJvmUnitTest,
                ::JUnit5InInstrumentedTest,
                ::NonAssertKAssertion,
                ::MockingLibraryInTest,
                ::RobolectricInTest,
                ::JvmComposeTestRule,
                ::ThreadSleepOrEspressoIdleInTest,
                ::ReflectionInTest,
                ::RoomInJvmUnitTest,
                ::ModifierParameterDefaultIsModifier,
                ::ModifierParameterAfterRequiredParameters,
                ::ComposableSlotParameterAfterModifier,
                ::NoPreviewParameterAnnotation,
                ::PreviewMustBePrivate,
                ::PreviewFunctionNaming,
                ::PreviewMustWrapInTheme,
                ::NoCustomCompositionLocal,
                ::StableAnnotationOnUnstableState,
                ::ScreenStateOnlyInScreenComposable,
                ::RootAndScreenInSameFile,
                ::KoinViewModelOnlyInRoot,
                ::ObserveAsEventsOnlyInRoot,
                ::RootComposableMustDefaultViewModel,
                ::EventChannelExposedAsReceiveAsFlow,
                ::ScreenComposableParameterOrder,
                ::CrammedBlockBody,
                ::ClassBodyMissingLeadingBlankLine,
            ),
        )
}
