# What PRISM checks

> Generated from the sources by `tools/make-rules-doc.py`. `verify-payload.sh` regenerates and diffs it, so it cannot fall behind the rules it describes. Edit the rule, not this file.

**72 detekt rules and 23 konsist tests.** Every detekt rule is active in every install except the five noted below, which need a name your repository may not have.

## How to read this

If you have not met PRISM before, three words will make the rest of this document make sense. The glossary in the documentation folder defines the rest.

| Word | Plainly |
|---|---|
| **rule** | one thing that is checked. Each row below is one rule. |
| **finding** | one place in your code where a rule matched. One rule can produce many findings. |
| **blocking** | whether a finding actually stops your build. |

**A rule reporting something is not the same as it blocking.** Every rule in this document runs in your repository. Whether its findings fail the build is a separate setting you control per module and per engine, in `.prism/scope.json`. [Installing into a codebase that already exists](INSTALL-BROWNFIELD.md) covers what that means when this repository has history.

These are the **detekt** rules and the **konsist** architecture tests. Two more engines check things no rule here covers: **ktlint** owns formatting and is configured entirely in `.editorconfig`, and **coverage** owns how much of your code the tests execute. The engines chapter in the documentation folder walks through all four.

## Contents

- [Rules that hold everywhere](#rules-that-hold-everywhere) — 40 rules
- [PALETTE RULES](#palette-rules) — 2 rules · switch `PALETTE_RULES_ACTIVE`
- [THEME RULES](#theme-rules) — 3 rules · switch `THEME_RULES_ACTIVE`
- [MVI PRESENTATION RULES](#mvi-presentation-rules) — 11 rules
- [MODULE-PATH RULES](#module-path-rules) — 6 rules
- [NAVIGATION RULES](#navigation-rules) — 3 rules
- [TEST-STACK RULES](#test-stack-rules) — 7 rules
- [Architecture tests (konsist)](#architecture-tests-konsist)

## Rules that hold everywhere

| Rule | What it catches |
|---|---|
| `NonAtomicStateFlowAssignment` | Assignment to a backing MutableStateFlow's .value is not atomic - use update { }. |
| `SingleLetterIdentifier` | Single-letter identifiers do not say what they hold. |
| `NoConsoleLogging` | Console logging must not appear in shipped code. |
| `IconContentDescription` | Every Icon must state a content description, explicitly null if it is decorative. |
| `IconButtonHardcodedSize` | An IconButton sized by its modifier loses the 48dp minimum touch target. |
| `NoOpenClassInMain` | Production types are not opened for subclassing. Extract an interface instead. |
| `NoPublicMutableFlow` | A mutable flow must not be visible outside the class that owns it. |
| `NoRunBlockingInMain` | runBlocking does not belong in shipped code outside an entry point. |
| `NoNotNullAssertion` | The not-null assertion operator turns a nullable value into a crash. |
| `HardcodedUserFacingString` | User-facing strings come from string resources. |
| `NoBareIconClickable` | A clickable Icon misses the minimum touch target and the ripple. Wrap it in an IconButton. |
| `NoComposableModifierExtension` | A Modifier extension must not be @Composable. |
| `CallbackFlowRequiresAwaitClose` | A callbackFlow must end in awaitClose so its callback is unregistered. |
| `SuspendGenericCatchSwallowsCancellation` | A generic catch in a suspending context must re-raise cancellation  |
| `SealedHierarchyOfOnlyObjectsShouldBeEnum` | A sealed hierarchy of member-less objects is an enum class written the long way. |
| `NoImplSuffix` | Implementations are named for what makes them unique, never with an Impl suffix. |
| `NoResultListOfErrors` | A Result carries exactly one error, never a collection of them. |
| `SharedFlowRequiresExplicitBuffer` | A MutableSharedFlow must state its replay and extraBufferCapacity explicitly. |
| `OneShotFlowBuilder` | A flow builder that only emits a single value should be a suspend function. |
| `CustomScopeRequiresSupervisorJob` | A hand-built CoroutineScope must include a SupervisorJob in its context. |
| `CollectAsStateWithoutLifecycle` | Flows are collected in composables with collectAsStateWithLifecycle, never collectAsState. |
| `UniqueWorkNameIsSnakeCase` | Unique-work name literals must be snake_case. |
| `StartForegroundViaServiceCompat` | The raw two-argument startForeground overload must be replaced with ServiceCompat.startForeground. |
| `DispatchersSetMainWithoutReset` | A test class that calls Dispatchers.setMain must reset it in an @AfterEach/@After teardown. |
| `InMemoryRoomNotClosed` | A test class that builds an in-memory Room database must close() it in an @After/@AfterEach teardown. |
| `AdaptiveLayoutOwnsNoState` | An *Adaptive*Layout composable is a pure structural container and must not own state. |
| `KoinModuleNaming` | Top-level Koin module properties are named <feature><Layer>Module. |
| `PreferConstructorReferenceKoinDefinition` | A Koin definition that only calls a constructor with get() uses the constructor-reference overload. |
| `HttpClientMustAcceptEngine` | HttpClient is constructed with an explicit engine or engine factory as its first argument. |
| `NoSharedPreferencesForTokens` | SharedPreferences APIs are forbidden; persistence goes through DataStore. |
| `ThreadSleepOrEspressoIdleInTest` | Tests never wait on the wall clock via Thread.sleep or Espresso.onIdle. |
| `ReflectionInTest` | Tests never reflect into private members; test through the public API instead. |
| `ModifierParameterDefaultIsModifier` | A composable's modifier parameter defaults to Modifier. |
| `ModifierParameterAfterRequiredParameters` | Only required parameters come before a composable's modifier parameter. |
| `ComposableSlotParameterAfterModifier` | Composable slot parameters come after the modifier parameter. |
| `NoPreviewParameterAnnotation` | Previews are written one @Preview function per state, never via @PreviewParameter. |
| `PreviewMustBePrivate` | Preview functions are private. |
| `PreviewFunctionNaming` | Preview functions are named *Preview. |
| `CrammedBlockBody` | Long unbroken statement runs hide the logical steps of a body. |
| `ClassBodyMissingLeadingBlankLine` | A class body should open with a blank line after the header. |

## PALETTE RULES

**This group may be switched off in your repository.** It is one of the two that can be; every other rule in this document is always active.

The group is anchored on a name PRISM has to be told: `PALETTE_OBJECT`, `EXTENDED_TOKEN_HOLDER`. Some of these rules read that name directly and the rest belong to the same design-system shape, so they travel together. If your repository has no such declaration there is no name to give, and `PALETTE_RULES_ACTIVE` turns the whole group off — rather than leaving a rule pointed at a name that matches nothing, which would report success over code it never examined.

Which way it went is decided by `.prism/verify/lib/probe.py`, which looks rather than asks, so two installs of the same repository reach the same answer. Re-run it with `./prism probe`; the whole chain is the design-system chapter in the documentation folder.

> **If `PALETTE_RULES_ACTIVE` is `false`, nothing below is checking your code.** That is a gap you chose, and it belongs in `.prism/INSTALL-RECORD.md`.

| Rule | What it catches |
|---|---|
| `ThemeColorDirectUse` | Colours must be consumed through MaterialTheme, not read off the palette object. |
| `UnwiredThemeColor` | A palette entry that no colour scheme or extended token references is unreachable through MaterialTheme. |

## THEME RULES

**This group may be switched off in your repository.** It is one of the two that can be; every other rule in this document is always active.

The group is anchored on a name PRISM has to be told: `THEME_OBJECT`. Some of these rules read that name directly and the rest belong to the same design-system shape, so they travel together. If your repository has no such declaration there is no name to give, and `THEME_RULES_ACTIVE` turns the whole group off — rather than leaving a rule pointed at a name that matches nothing, which would report success over code it never examined.

Which way it went is decided by `.prism/verify/lib/probe.py`, which looks rather than asks, so two installs of the same repository reach the same answer. Re-run it with `./prism probe`; the whole chain is the design-system chapter in the documentation folder.

> **If `THEME_RULES_ACTIVE` is `false`, nothing below is checking your code.** That is a gap you chose, and it belongs in `.prism/INSTALL-RECORD.md`.

| Rule | What it catches |
|---|---|
| `PreviewMustWrapInTheme` | Every preview wraps its content in the app theme composable. |
| `RawColorLiteral` | Colour literals belong in the design-system theme, not at a call site. |
| `NoCustomCompositionLocal` | Custom CompositionLocals are not created outside the design-system theme. |

## MVI PRESENTATION RULES

| Rule | What it catches |
|---|---|
| `TextFieldStateInViewModel` | Screen-level text-field state belongs to the ViewModel, not to a composable. |
| `ObserveAsEventsRequired` | One-time events are observed through ObserveAsEvents, not a LaunchedEffect that collects. |
| `StateInViewModelRequiresWhileSubscribed` | stateIn/shareIn on viewModelScope must use SharingStarted.WhileSubscribed(5_000L). |
| `StableAnnotationOnUnstableState` | A State class holding standard collections carries @Stable or @Immutable. |
| `ScreenStateOnlyInScreenComposable` | Screen-level state may only be a parameter of Screen or Root composables. |
| `RootAndScreenInSameFile` | A Root composable and its Screen composable live in the same file. |
| `KoinViewModelOnlyInRoot` | koinViewModel() and viewModel() are called only inside Root composables. |
| `ObserveAsEventsOnlyInRoot` | ObserveAsEvents is called only inside Root composables. |
| `RootComposableMustDefaultViewModel` | A Root composable's ViewModel parameter defaults to koinViewModel(). |
| `EventChannelExposedAsReceiveAsFlow` | A ViewModel keeps its event Channel private and exposes it via receiveAsFlow(). |
| `ScreenComposableParameterOrder` | A Screen composable takes state as its first parameter and onAction as its second. |

## MODULE-PATH RULES

| Rule | What it catches |
|---|---|
| `KtorCallMustUseSafeCall` | HTTP calls go through the project's safeGet / safePost / safeDelete wrappers. |
| `EmptyResultOverResultUnit` | A Result with no success value is written as the EmptyResult typealias. |
| `WorkerResultTypealiasRequired` | ListenableWorker.Result must be referenced through the WorkerResult typealias. |
| `NoCrossFeatureRouteImport` | A feature module must not import another feature's route; cross-feature navigation is a callback. |
| `StartKoinOnlyInAppModule` | startKoin is called only in the Application class. |
| `HttpClientConstructionOutsideFactory` | HttpClient is constructed only in HttpClientFactory. |

## NAVIGATION RULES

| Rule | What it catches |
|---|---|
| `RouteMustBeSerializable` | Every route declaration must carry @Serializable for type-safe navigation. |
| `RouteMustBeDataObjectOrDataClass` | Routes must be declared as data object (no args) or data class (args). |
| `NavGraphBuilderExtensionNaming` | NavGraphBuilder extension functions must be named <feature>Graph. |

## TEST-STACK RULES

| Rule | What it catches |
|---|---|
| `JUnit4InJvmUnitTest` | JVM unit tests use JUnit5; legacy org.junit imports do not belong under src/test. |
| `JUnit5InInstrumentedTest` | Instrumented tests use JUnit4; a jupiter test silently never runs under AndroidJUnitRunner. |
| `NonAssertKAssertion` | Test assertions go through AssertK, not kotlin.test or JUnit assertion APIs. |
| `MockingLibraryInTest` | Tests use hand-written fakes over mocking libraries. |
| `RobolectricInTest` | Robolectric is forbidden; Android-dependent tests run instrumented on a device. |
| `JvmComposeTestRule` | Compose tests use createAndroidComposeRule<ComponentActivity>(), never the plain createComposeRule variants. |
| `RoomInJvmUnitTest` | Room tests are instrumented; database builders do not belong under src/test. |

## Architecture tests (konsist)

Whole-project rules, run as a JUnit suite in `tooling/konsist`. They use `assertAllWhereApplicable`, so a construct your project does not have is reported as **skipped with a reason** — never as a silent pass.

**`DataLayerRulesTest.kt`**

- classes in a dto package are serializable Dto data classes
- every Dto resides in a dto package
- every Room entity is named Entity and lives in an entity package
- every repository function returns a typed result

**`LayerPurityRulesTest.kt`**

- domain packages depend on no framework
- the named domain package is free of the wire format
- presentation packages never reach the network or the database

**`MviContractRulesTest.kt`**

- every feature ViewModel exposes a public state property
- the state property is a StateFlow
- every feature ViewModel declares onAction with exactly one parameter
- every feature ViewModel has a sibling State and Action
- Action and Event hierarchies in presentation are sealed
- State declarations in presentation are immutable data classes
- Screen composables take no ViewModel

**`ScopeIntegrityTest.kt`**

- the scope covers every module declared in settings-gradle-kts
- the scope covers every source set the project uses
- the scope excludes generated sources
- the scope excludes every framework-owned and harness-owned directory

**`StructureRulesTest.kt`**

- every file's package matches its directory
- every module's sources reside under that module's package root
- every declared module has a package root mapping

**`TestConventionRulesTest.kt`**

- a class declaring test functions is named Test
- a test sits in the same package as the code it exercises
