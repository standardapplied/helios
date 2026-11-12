/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.origin;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.targetOwner;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.AndFullName.Predicates.fullNameMatching;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameStartingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMembers;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.persistence.TraceRollup;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaStaticInitializer;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.RecordComponent;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The one allowed way to do each thing, as build-failing rules over the main classes of every
 * library module. A spec that establishes or consolidates a pattern adds its rule here; each rule's
 * {@code because} clause states the allowed way.
 */
class ArchitectureRulesTest {

  private static final String HELIOS = "com.standardapplied.helios";
  private static final String CORE = HELIOS + ".core..";
  private static final String HTTP_CLIENT_FACTORY = HELIOS + ".core.common.HttpClientFactory";

  private static final String[] PROVIDERS = {
    HELIOS + ".anthropic..", HELIOS + ".openai..", HELIOS + ".gemini.."
  };

  private static final String JSON_MAPPER = "tools.jackson.databind.json.JsonMapper";
  private static final String OBJECT_MAPPER = "tools.jackson.databind.ObjectMapper";

  /**
   * Burn-down, v3-injected-time: the one class that reads the wall clock statically. Thirteen
   * classes stamp records through {@code Ids.now()}, and {@code Ids.newId()} embeds the millisecond
   * in a UUID v7.
   */
  private static final String STATIC_WALL_CLOCK_BURN_DOWN = HELIOS + ".core.common.Ids";

  /** The one visitor that walks the workspace for the session file tools. */
  private static final String WORKSPACE_WALK = HELIOS + ".session.files.WorkspaceWalk";

  /** The one class in the session module that builds a Jackson mapper. */
  private static final String SESSION_JSON = HELIOS + ".session.loop.SessionJson";

  /** The one launcher outside core.process: the REPL sandbox starts its own JVM. */
  private static final String SANDBOX_LAUNCHER = HELIOS + ".repl.sandbox.SandboxLauncher";

  /** The one place the runtime decides a request's identity. */
  private static final String AUTHENTICATION_GATE = HELIOS + ".runtime.AuthenticationGate";

  /** The one class that swaps the JVM-global standard streams: the sandbox's snippet evaluator. */
  private static final String STREAM_SWAPPER = HELIOS + ".repl.sandbox.SnippetEvaluator";

  private static final String PERMISSION = HELIOS + ".session.permissions.Permission";

  private static final String REASONING_SUPPORT = HELIOS + ".core.provider.ReasoningSupport";

  /**
   * Each provider's model catalogue, the core type it declares reasoning in, and its one reader.
   */
  private static final Set<String> REASONING_DECLARATIONS_AND_READERS =
      Set.of(
          HELIOS + ".anthropic.AnthropicModelId",
          HELIOS + ".anthropic.AnthropicRequestBuilder",
          HELIOS + ".openai.OpenAIModelId",
          HELIOS + ".openai.OpenAIRequestBuilder",
          HELIOS + ".gemini.GeminiModelId",
          HELIOS + ".gemini.GeminiRequestBuilder",
          REASONING_SUPPORT);

  private static final DescribedPredicate<JavaClass> A_REASONING_DECLARATION_OR_ITS_READER =
      describe(
          "a model catalogue, ReasoningSupport or a request builder",
          type -> REASONING_DECLARATIONS_AND_READERS.contains(type.getName()));

  private static final String TIME_SEAM =
      "a class that needs the time takes a java.time.InstantSource through withClock(...) and"
          + " defaults it to Clock.systemUTC()";

  private static final DescribedPredicate<JavaCodeUnit> A_CONSTRUCTOR_OR_STATIC_INITIALISER =
      describe(
          "a constructor or static initialiser",
          unit -> unit.isConstructor() || unit instanceof JavaStaticInitializer);

  private static final Set<Class<?>> STREAM_TYPES =
      Set.of(InputStream.class, OutputStream.class, Reader.class, Writer.class);

  private static final DescribedPredicate<JavaCodeUnit> START_A_VIRTUAL_THREAD =
      describe(
          "start a virtual thread",
          unit ->
              unit.getMethodCallsFromSelf().stream()
                  .anyMatch(ArchitectureRulesTest::startsAVirtualThread));

  private static final DescribedPredicate<JavaCodeUnit> READ_OR_WRITE_A_STREAM =
      describe(
          "read or write a byte or character stream, in their own body or in a method of their"
              + " class they call or reference",
          unit ->
              readsOrWritesAStream(unit)
                  || Stream.<JavaCodeUnitAccess<?>>concat(
                          unit.getCallsFromSelf().stream(),
                          unit.getCodeUnitReferencesFromSelf().stream())
                      .filter(access -> access.getTargetOwner().equals(unit.getOwner()))
                      .flatMap(access -> access.getTarget().resolveMember().stream())
                      .anyMatch(ArchitectureRulesTest::readsOrWritesAStream));

  private static final Set<String> JDK_PACKAGES =
      ModuleFinder.ofSystem().findAll().stream()
          .flatMap(module -> module.descriptor().packages().stream())
          .collect(Collectors.toUnmodifiableSet());

  private static final DescribedPredicate<JavaClass> BELONG_TO_THE_JDK =
      describe("belong to the JDK", type -> JDK_PACKAGES.contains(type.getPackageName()));

  private static final JavaClasses LIBRARY =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(HELIOS);

  private static final int SHORTEST_FLAGGED_RUN = 4;

  private static final String SHAPES = HELIOS + ".architecture.shapes";

  private static final Map<Path, Set<String>> EXPORTS_BY_CODE_SOURCE = new ConcurrentHashMap<>();

  private static final DescribedPredicate<JavaClass> IN_AN_EXPORTED_PACKAGE =
      describe(
          "reside in a package their module exports to every module",
          type -> exportedPackages(type).contains(type.getPackageName()));

  @Test
  void coreDependsOnNothingOutsideTheJdk() {
    classes()
        .that()
        .resideInAPackage(CORE)
        .should()
        .onlyDependOnClassesThat(resideInAPackage(CORE).or(BELONG_TO_THE_JDK))
        .because("helios-core has zero dependencies: use the JDK or a type in core itself")
        .check(LIBRARY);
  }

  @ParameterizedTest
  @ValueSource(strings = {"anthropic", "openai", "gemini"})
  void providerDependsOnCoreOnly(String provider) {
    var own = HELIOS + "." + provider + "..";
    noClasses()
        .that()
        .resideInAPackage(own)
        .should()
        .dependOnClassesThat(
            resideInAPackage(HELIOS + "..").and(not(resideInAnyPackage(own, CORE))))
        .because(
            "a provider module builds on helios-core only: shared code moves to core, never"
                + " into another provider, session, runtime, persistence or repl")
        .check(LIBRARY);
  }

  @Test
  void providersReadResponseStreamsOnlyThroughTheSseReader() {
    noClasses()
        .that()
        .resideInAnyPackage(PROVIDERS)
        .should()
        .dependOnClassesThat(type(BufferedReader.class).or(type(InputStreamReader.class)))
        .because(
            "a provider reads a response stream through core.provider.SseReader, which owns the"
                + " body, the per-line idle timeout, the read failures and the close of the"
                + " stream and its reader thread")
        .check(LIBRARY);
  }

  @ParameterizedTest
  @CsvSource({"anthropic, AnthropicJson", "openai, OpenAIJson", "gemini, GeminiJson"})
  void providerBuildsJsonMappersOnlyInItsHolder(String provider, String holder) {
    var module = HELIOS + "." + provider;
    var holderName = module + ".api." + holder;
    noClasses()
        .that()
        .resideInAPackage(module + "..")
        .and(not(name(holderName).or(nameStartingWith(holderName + "$"))))
        .should()
        .accessTargetWhere(
            targetOwner(name(JSON_MAPPER))
                .and(target(name("builder").or(name("shared"))))
                .or(
                    targetOwner(name(JSON_MAPPER).or(name(OBJECT_MAPPER)))
                        .and(target(name(JavaConstructor.CONSTRUCTOR_NAME)))))
        .because(
            "a provider module builds each Jackson mapper configuration once, in its api."
                + holder
                + " holder, and every other class takes the mapper from there")
        .check(LIBRARY);
  }

  @Test
  void sessionBuildsAJsonMapperOnlyInSessionJson() {
    noClasses()
        .that()
        .resideInAPackage(HELIOS + ".session..")
        .and(not(name(SESSION_JSON).or(nameStartingWith(SESSION_JSON + "$"))))
        .should()
        .accessTargetWhere(
            targetOwner(name(JSON_MAPPER))
                .and(target(name("builder").or(name("shared"))))
                .or(
                    targetOwner(name(JSON_MAPPER).or(name(OBJECT_MAPPER)))
                        .and(target(name(JavaConstructor.CONSTRUCTOR_NAME)))))
        .because(
            "the session module builds its one Jackson mapper in session.loop.SessionJson, and every"
                + " other class reads JSON through the binding it holds")
        .check(LIBRARY);
  }

  @Test
  void openTelemetryStaysOutOfCoreAndItsSdkInPersistence() {
    CompositeArchRule.of(
            noClasses()
                .that()
                .resideInAPackage(CORE)
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io.opentelemetry.."))
        .and(
            noClasses()
                .that()
                .resideOutsideOfPackage(HELIOS + ".persistence..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io.opentelemetry.sdk.."))
        .because(
            "the session emits spans through the OpenTelemetry API; only the Postgres exporter"
                + " needs its SDK")
        .check(LIBRARY);
  }

  @Test
  void streamsArePulledNeverPublishedThroughFlow() {
    noClasses()
        .should()
        .dependOnClassesThat(
            nameStartingWith(Flow.class.getName()).or(type(SubmissionPublisher.class)))
        .because(
            "a stream is pulled by its reader: a model turn's through core.model.CloseableIterator,"
                + " a session's events through session.EventStream; nothing is published through"
                + " java.util.concurrent.Flow or SubmissionPublisher")
        .check(LIBRARY);
  }

  @Test
  void sessionClassesCreateNoExecutors() {
    noClasses()
        .that()
        .resideInAPackage(HELIOS + ".session..")
        .and()
        .resideOutsideOfPackages(
            HELIOS + ".session.files..",
            HELIOS + ".session.memory..",
            HELIOS + ".session.execution..")
        .should()
        .accessTargetWhere(
            targetOwner(type(Executors.class))
                .and(target(nameStartingWith("new")))
                .or(
                    targetOwner(assignableTo(ExecutorService.class))
                        .and(target(name(JavaConstructor.CONSTRUCTOR_NAME)))))
        .because(
            "a session owns no pool: its loop, its wall clock and every bounded wait run on"
                + " virtual threads of their own, which end with the work they run")
        .check(LIBRARY);
  }

  @Test
  void providersServeTheirModelsThroughStreamingModel() {
    noClasses()
        .that()
        .resideInAnyPackage(PROVIDERS)
        .should()
        .beAssignableTo(Model.class)
        .because(
            "a streaming provider's Model is a core.provider.StreamingModel assembled from the"
                + " provider's request factory, exchange and JSON binding, so chat, structured"
                + " chat, streaming and close are written once for every provider")
        .check(LIBRARY);
  }

  @Test
  void coreProviderDependsOnNoProviderModule() {
    noClasses()
        .that()
        .resideInAPackage(HELIOS + ".core.provider..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(PROVIDERS)
        .because(
            "core.provider holds only what every provider does identically; what one provider"
                + " does differently stays in that provider's module")
        .check(LIBRARY);
  }

  @Test
  void sessionDoesNotDependOnProvidersRuntimeOrPersistence() {
    noClasses()
        .that()
        .resideInAPackage(HELIOS + ".session..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            HELIOS + ".anthropic..",
            HELIOS + ".openai..",
            HELIOS + ".gemini..",
            HELIOS + ".runtime..",
            HELIOS + ".persistence..")
        .because(
            "helios-session is provider-neutral and transport-neutral: it talks to the core"
                + " Model and store interfaces, and the deployer wires the implementations")
        .check(LIBRARY);
  }

  @Test
  void httpClientIsBuiltOnlyByHttpClientFactory() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(HTTP_CLIENT_FACTORY)
        .should()
        .accessTargetWhere(
            targetOwner(type(HttpClient.class))
                .and(target(name("newBuilder").or(name("newHttpClient")))))
        .because("every java.net.http.HttpClient comes from core.common.HttpClientFactory")
        .check(LIBRARY);
  }

  @Test
  void theRuntimeAuthenticatesRequestsOnlyThroughTheAuthenticationGate() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(AUTHENTICATION_GATE)
        .should()
        .accessTargetWhere(
            targetOwner(assignableTo(HELIOS + ".runtime.Authenticator"))
                .and(target(name("authenticate"))))
        .because(
            "every runtime route is admitted by runtime.AuthenticationGate, which runs the"
                + " deployer's Authenticator before any session lookup, body read or work; a route"
                + " never checks identity a second way")
        .check(LIBRARY);
  }

  @Test
  void theRuntimeSendsA401OnlyThroughTheAuthenticationGate() {
    noClasses()
        .that()
        .resideInAPackage(HELIOS + ".runtime..")
        .and()
        .doNotHaveFullyQualifiedName(AUTHENTICATION_GATE)
        .should()
        .accessTargetWhere(
            targetOwner(name("io.helidon.http.Status"))
                .and(target(name("UNAUTHORIZED_401").or(name("create"))))
                .or(
                    targetOwner(assignableTo("io.helidon.webserver.http.ServerResponse"))
                        .and(target(fullNameMatching(".*\\.status\\(int\\)"))))
                .or(targetOwner(assignableTo("io.helidon.http.UnauthorizedException"))))
        .because(
            "every runtime 401 is sent by runtime.AuthenticationGate.unauthenticated, which sets"
                + " WWW-Authenticate to the authenticator's challenge; a route that ends a request"
                + " for an ended identity calls it, never builds the 401 itself, names a status"
                + " by its number (Status.create, ServerResponse.status(int)) or throws Helidon's"
                + " UnauthorizedException, whose 401 carries no challenge")
        .check(LIBRARY);
  }

  @Test
  void onlyTheOnnxModuleBuildsARedirectFollowingClient() {
    noClasses()
        .that()
        .resideOutsideOfPackage(HELIOS + ".onnx..")
        .should()
        .accessTargetWhere(
            targetOwner(name(HTTP_CLIENT_FACTORY)).and(target(name("createForDownloads"))))
        .because(
            "a redirect target receives whatever the request carries: a client that may carry a"
                + " credential comes from HttpClientFactory.create(...), which never follows a"
                + " redirect, and only the onnx module's unauthenticated model downloads use"
                + " HttpClientFactory.createForDownloads()")
        .check(LIBRARY);
  }

  @Test
  void onlyRequestBuildersReadAModelsReasoningDeclaration() {
    var declaration =
        targetOwner(simpleNameEndingWith("ModelId"))
            .and(target(name("reasoning")))
            .or(
                targetOwner(simpleName("ReasoningRules"))
                    .and(target(name("off").or(name("support")).or(name("sampling")))))
            .or(
                targetOwner(name(REASONING_SUPPORT))
                    .and(
                        target(
                            name("require")
                                .or(name("off"))
                                .or(name("levels"))
                                .or(name("displays")))));
    noClasses()
        .that(not(belongTo(A_REASONING_DECLARATION_OR_ITS_READER)))
        .should()
        .accessTargetWhere(declaration)
        .because(
            "a model's catalogue declares the reasoning and sampling it accepts and its request"
                + " builder is the one reader: the builder checks the configuration against the"
                + " declaration and looks up the wire fields, so effort and display have one"
                + " interpretation per provider")
        .check(LIBRARY);
  }

  @Test
  void noTopLevelTypeIsNamedAsAGrabBag() {
    noClasses()
        .that()
        .areTopLevelClasses()
        .should()
        .haveSimpleNameEndingWith("Util")
        .orShould()
        .haveSimpleNameEndingWith("Utils")
        .orShould()
        .haveSimpleNameEndingWith("Helper")
        .orShould()
        .haveSimpleNameEndingWith("Helpers")
        .orShould()
        .haveSimpleNameEndingWith("Manager")
        .because(
            "a type is named for its single responsibility: put the behaviour on the type that"
                + " owns the data, or name the new type after what it does")
        .check(LIBRARY);
  }

  @Test
  void standardStreamsAreUsedOnlyByTheSandbox() {
    noClasses()
        .that()
        .resideOutsideOfPackage(HELIOS + ".repl.sandbox..")
        .should(ACCESS_STANDARD_STREAMS)
        .orShould()
        .accessTargetWhere(
            targetOwner(assignableTo(Throwable.class)).and(target(name("printStackTrace"))))
        .because(
            "library code reports through java.util.logging or the trace API; only repl.sandbox,"
                + " where capturing the standard streams is the mechanism, and the example"
                + " modules may use System.out, System.err or printStackTrace")
        .check(LIBRARY);
  }

  @Test
  void nothingInMainCodeIsDeprecated() {
    var oneWay =
        "there is one way to do each thing: a superseded type or member is deleted and its"
            + " replacement recorded under Breaking in CHANGELOG.md, never kept behind @Deprecated";
    noClasses().should().beAnnotatedWith(Deprecated.class).because(oneWay).check(LIBRARY);
    noMembers().should().beAnnotatedWith(Deprecated.class).because(oneWay).check(LIBRARY);
  }

  @Test
  void theWallClockIsNotReadStatically() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(STATIC_WALL_CLOCK_BURN_DOWN)
        .should()
        .accessTargetWhere(
            targetOwner(resideInAPackage("java.time.."))
                .and(target(name("now")))
                .or(targetOwner(type(System.class)).and(target(name("currentTimeMillis")))))
        .because(
            "a static now() or currentTimeMillis() read cannot be driven by a test: "
                + TIME_SEAM
                + ", then reads clock.instant()")
        .check(LIBRARY);
  }

  @Test
  void theSystemClockIsOnlyADefault() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(STATIC_WALL_CLOCK_BURN_DOWN)
        .should()
        .accessTargetWhere(
            targetOwner(type(Clock.class))
                .and(target(nameStartingWith("system").or(nameStartingWith("tick"))))
                .and(not(origin(A_CONSTRUCTOR_OR_STATIC_INITIALISER))))
        .because(
            "Clock.system* and Clock.tick* appear only as the initial value of a field, never"
                + " inline in a method: "
                + TIME_SEAM)
        .check(LIBRARY);
  }

  @Test
  void aTimeSeamIsAnInstantSource() {
    noFields()
        .should()
        .haveRawType(Clock.class)
        .because("java.time.Clock is not a field type: " + TIME_SEAM)
        .check(LIBRARY);
    noCodeUnits()
        .should()
        .haveRawParameterTypes(
            describe(
                "containing java.time.Clock", types -> types.stream().anyMatch(type(Clock.class))))
        .orShould()
        .haveRawReturnType(Clock.class)
        .because("java.time.Clock is not a parameter or return type: " + TIME_SEAM)
        .check(LIBRARY);
  }

  @Test
  void childProcessPipesAreReadAndWrittenOnPlatformThreads() {
    noCodeUnits()
        .that()
        .areDeclaredInClassesThat()
        .resideInAnyPackage(
            HELIOS + ".core.process..", HELIOS + ".session.execution..", HELIOS + ".repl.sandbox..")
        .and(START_A_VIRTUAL_THREAD)
        .should(ArchCondition.from(READ_OR_WRITE_A_STREAM))
        .because(
            "a read or write on a child-process pipe blocks its carrier, so on a virtual thread a"
                + " few dozen of them starve the virtual-thread scheduler: a thread that reads or"
                + " writes a child-process pipe is a platform daemon thread,"
                + " Thread.ofPlatform().daemon()")
        .check(LIBRARY);
  }

  @Test
  void processBuilderIsUsedOnlyByCoreProcessAndTheSandboxLauncher() {
    noClasses()
        .that()
        .resideOutsideOfPackage(HELIOS + ".core.process..")
        .and(not(name(SANDBOX_LAUNCHER).or(nameStartingWith(SANDBOX_LAUNCHER + "$"))))
        .should()
        .dependOnClassesThat(nameStartingWith(ProcessBuilder.class.getName()))
        .because(
            "a child process is started through core.process.BoundedProcess, which owns the"
                + " explicit argv and environment, bounded output, timeout kill and working"
                + " directory; only repl.sandbox.SandboxLauncher launches its own JVM")
        .check(LIBRARY);
  }

  @Test
  void jshellIsUsedOnlyOnTheBootstrapSideOfTheSandbox() {
    noClasses()
        .that()
        .resideOutsideOfPackage(HELIOS + ".repl.sandbox..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("jdk.jshell..")
        .because(
            "JShell runs inside the sandbox subprocess: only repl.sandbox and its sub-packages, the"
                + " bootstrap side of the process boundary, use jdk.jshell; the host reaches a"
                + " snippet through JvmSandbox's RPC")
        .check(LIBRARY);
  }

  @Test
  void theStandardStreamsAreSwappedOnlyBySnippetEvaluator() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(STREAM_SWAPPER)
        .should()
        .accessTargetWhere(
            targetOwner(type(System.class)).and(target(name("setOut").or(name("setErr")))))
        .because(
            "System.out and System.err are JVM-global: only repl.sandbox.SnippetEvaluator swaps"
                + " them, under the lock that admits one execute at a time")
        .check(LIBRARY);
  }

  @Test
  void sessionFilesWalksTheWorkspaceOnlyThroughWorkspaceWalk() {
    var oneWay =
        "a file tool walks the workspace through session.files.WorkspaceWalk, which drives the"
            + " one confined walk, WorkspaceRoot.walkFileTree, with hidden-directory pruning, the"
            + " regular-file and size filter, cancellation and the result cap";
    noClasses()
        .that()
        .resideInAPackage(HELIOS + ".session.files")
        .and(not(name(WORKSPACE_WALK).or(nameStartingWith(WORKSPACE_WALK + "$"))))
        .should()
        .accessTargetWhere(
            targetOwner(name(HELIOS + ".session.files.WorkspaceRoot"))
                .and(target(name("walkFileTree"))))
        .because(oneWay)
        .check(LIBRARY);
    noClasses()
        .that()
        .resideInAPackage(HELIOS + ".session.files")
        .should()
        .accessTargetWhere(
            targetOwner(type(Files.class)).and(target(name("walk").or(name("walkFileTree")))))
        .because(oneWay)
        .check(LIBRARY);
  }

  @Test
  void aPermissionIsBuiltByNamedRuleLists() {
    noClasses()
        .that(not(name(PERMISSION).or(nameStartingWith(PERMISSION + "$"))))
        .should()
        .accessTargetWhere(
            targetOwner(name(PERMISSION)).and(target(name(JavaConstructor.CONSTRUCTOR_NAME))))
        .because(
            "a permission is built with Permission.newBuilder(mode), whose withAllow, withAsk and"
                + " withDeny name the list each rule joins; the canonical constructor takes three"
                + " rule lists in a row, so a swap there compiles and turns a deny into an allow")
        .check(LIBRARY);
  }

  @Test
  void builderMethodsReturningTheBuilderStartWithWith() {
    methods()
        .that()
        .arePublic()
        .and()
        .areNotStatic()
        .and()
        .areDeclaredInClassesThat()
        .haveSimpleNameEndingWith("Builder")
        .and(
            describe(
                "return their declaring builder",
                (JavaMethod method) -> method.getRawReturnType().equals(method.getOwner())))
        .should()
        .haveNameStartingWith("with")
        .because(
            "a builder step is named withX(...): a fluent method on a *Builder that returns the"
                + " builder starts with \"with\"")
        .check(LIBRARY);
  }

  private static boolean startsAVirtualThread(JavaMethodCall call) {
    var owner = call.getTargetOwner();
    return switch (call.getName()) {
      case "startVirtualThread", "ofVirtual" -> owner.isEquivalentTo(Thread.class);
      case "newVirtualThreadPerTaskExecutor" -> owner.isEquivalentTo(Executors.class);
      default -> false;
    };
  }

  private static boolean readsOrWritesAStream(JavaCodeUnit unit) {
    return unit.getMethodCallsFromSelf().stream()
        .anyMatch(
            call ->
                STREAM_TYPES.stream().anyMatch(call.getTargetOwner()::isAssignableTo)
                    && (call.getName().startsWith("read")
                        || call.getName().startsWith("write")
                        || call.getName().equals("transferTo")));
  }

  @Test
  void noPublicApiTakesFourArgumentsOfOneTypeInARow() {
    noPublicApiTakesFourArgumentsOfOneTypeInARow(IN_AN_EXPORTED_PACKAGE).check(LIBRARY);
  }

  @Test
  void theFourInARowRuleFlagsExactlyThePositionalShapes() {
    var shape = SHAPES + ".PositionalShapes$";
    var missingBuilder = " declares no public static newBuilder()";
    var shapes = new ClassFileImporter().importPackages(SHAPES);

    var report =
        noPublicApiTakesFourArgumentsOfOneTypeInARow(resideInAPackage(SHAPES))
            .evaluate(shapes)
            .getFailureReport()
            .getDetails();

    assertEquals(
        Set.of(
            shape
                + "PositionalUsage.<init>(int, int, int, int, int, long) takes 5 arguments of type"
                + " int in a row, parameters 1 to 5; record "
                + shape
                + "PositionalUsage"
                + missingBuilder,
            shape
                + "HiddenBuilderUsage.<init>(int, int, int, int, int, long) takes 5 arguments of"
                + " type int in a row, parameters 1 to 5; record "
                + shape
                + "HiddenBuilderUsage"
                + missingBuilder,
            shape
                + "NamedUsage.of(int, int, int, int) takes 4 arguments of type int in a row,"
                + " parameters 1 to 4",
            shape
                + "SameRules.<init>(java.util.List<java.lang.String>, java.util.List<java.lang.String>,"
                + " java.util.List<java.lang.String>, java.util.List<java.lang.String>) takes 4"
                + " arguments of type java.util.List<java.lang.String> in a row, parameters 1 to 4"
                + "; record "
                + shape
                + "SameRules"
                + missingBuilder,
            shape
                + "Factories.sum(int, int, int, int) takes 4 arguments of type int in a row,"
                + " parameters 1 to 4",
            shape
                + "Factories.join(java.lang.String, java.lang.String, java.lang.String,"
                + " java.lang.String) takes 4 arguments of type java.lang.String in a row,"
                + " parameters 1 to 4",
            shape
                + "Factories.<init>(long, long, long, long) takes 4 arguments of type long in a"
                + " row, parameters 1 to 4"),
        Set.copyOf(report));
    assertEquals(7, report.size());
  }

  @Test
  void exportedPackagesAreReadFromTheModuleDescriptors() {
    assertTrue(IN_AN_EXPORTED_PACKAGE.test(LIBRARY.get(Response.Usage.class)));
    assertTrue(IN_AN_EXPORTED_PACKAGE.test(LIBRARY.get(TraceRollup.class)));
    assertFalse(IN_AN_EXPORTED_PACKAGE.test(LIBRARY.get(HELIOS + ".anthropic.api.ContentDelta")));
    assertFalse(IN_AN_EXPORTED_PACKAGE.test(LIBRARY.get(HELIOS + ".session.loop.AgentLoop")));
  }

  static ArchRule noPublicApiTakesFourArgumentsOfOneTypeInARow(
      DescribedPredicate<JavaClass> exported) {
    return codeUnits()
        .that(
            describe(
                "are public or protected, neither synthetic nor bridge, and declared in types that"
                    + " are public with every enclosing type and "
                    + exported.getDescription(),
                unit -> inThePublishedApi(unit, exported)))
        .should(
            new ArchCondition<JavaCodeUnit>("take at most three arguments of one type in a row") {
              @Override
              public void check(JavaCodeUnit unit, ConditionEvents events) {
                runsOfOneType(unit)
                    .forEach(run -> events.add(SimpleConditionEvent.violated(unit, run)));
              }
            })
        .because(
            "four or more arguments of one type in a row compile in any order, and every"
                + " positional caller breaks when one is added: take them through a builder (a"
                + " record declares a public static newBuilder() whose with... steps name each"
                + " component, as Response.newBuilder() does, which exempts its canonical"
                + " constructor) or through a record that names them; never reorder parameters to"
                + " break the run");
  }

  private static boolean inThePublishedApi(
      JavaCodeUnit unit, DescribedPredicate<JavaClass> exported) {
    var modifiers = unit.getModifiers();
    return (modifiers.contains(JavaModifier.PUBLIC) || modifiers.contains(JavaModifier.PROTECTED))
        && !modifiers.contains(JavaModifier.SYNTHETIC)
        && !modifiers.contains(JavaModifier.BRIDGE)
        && publicWithEveryEnclosingType(unit.getOwner())
        && exported.test(unit.getOwner());
  }

  private static boolean publicWithEveryEnclosingType(JavaClass type) {
    return type.getModifiers().contains(JavaModifier.PUBLIC)
        && type.getEnclosingClass()
            .map(ArchitectureRulesTest::publicWithEveryEnclosingType)
            .orElse(true);
  }

  private static List<String> runsOfOneType(JavaCodeUnit unit) {
    if (isCanonicalConstructor(unit) && declaresAPublicNewBuilder(unit.getOwner())) {
      return List.of();
    }
    var types = unit.getParameterTypes().stream().map(JavaType::getName).toList();
    var runs = new ArrayList<String>();
    var start = 0;
    for (var end = 1; end <= types.size(); end++) {
      if (end == types.size() || !types.get(end).equals(types.get(start))) {
        if (end - start >= SHORTEST_FLAGGED_RUN) {
          runs.add(describeRun(unit, types, start, end));
        }
        start = end;
      }
    }
    return runs;
  }

  private static String describeRun(JavaCodeUnit unit, List<String> types, int start, int end) {
    var owner = unit.getOwner().getName();
    var run =
        owner
            + "."
            + unit.getName()
            + "("
            + String.join(", ", types)
            + ") takes "
            + (end - start)
            + " arguments of type "
            + types.get(start)
            + " in a row, parameters "
            + (start + 1)
            + " to "
            + end;
    return isCanonicalConstructor(unit)
        ? run + "; record " + owner + " declares no public static newBuilder()"
        : run;
  }

  private static boolean isCanonicalConstructor(JavaCodeUnit unit) {
    var owner = unit.getOwner();
    return unit.isConstructor()
        && owner.isRecord()
        && unit.getRawParameterTypes().stream()
            .map(JavaClass::reflect)
            .toList()
            .equals(
                Arrays.stream(owner.reflect().getRecordComponents())
                    .map(RecordComponent::getType)
                    .toList());
  }

  private static boolean declaresAPublicNewBuilder(JavaClass type) {
    return type.tryGetMethod("newBuilder")
        .filter(
            method ->
                method.getModifiers().containsAll(Set.of(JavaModifier.PUBLIC, JavaModifier.STATIC)))
        .isPresent();
  }

  private static Set<String> exportedPackages(JavaClass type) {
    return EXPORTS_BY_CODE_SOURCE.computeIfAbsent(
        codeSource(type), ArchitectureRulesTest::unqualifiedExports);
  }

  private static Path codeSource(JavaClass type) {
    try {
      return Path.of(type.reflect().getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException("cannot locate the code source of " + type.getName(), e);
    }
  }

  private static Set<String> unqualifiedExports(Path codeSource) {
    return ModuleFinder.of(codeSource).findAll().stream()
        .map(ModuleReference::descriptor)
        .filter(descriptor -> !descriptor.isAutomatic())
        .flatMap(descriptor -> descriptor.exports().stream())
        .filter(export -> !export.isQualified())
        .map(ModuleDescriptor.Exports::source)
        .collect(Collectors.toUnmodifiableSet());
  }
}
