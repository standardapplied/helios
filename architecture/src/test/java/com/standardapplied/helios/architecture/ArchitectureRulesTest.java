/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.origin;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.targetOwner;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameStartingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMembers;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;

import com.standardapplied.helios.core.model.Model;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaStaticInitializer;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.module.ModuleFinder;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SubmissionPublisher;
import java.util.stream.Collectors;
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
  private static final String SESSION_JSON = HELIOS + ".session.SessionJson";

  /** The one owner of the session's event fan-out. */
  private static final String SESSION_EVENT_PUBLISHER = HELIOS + ".session.SessionEventPublisher";

  /** The one owner of the session's start-up and shutdown. */
  private static final String SESSION_LIFECYCLE = HELIOS + ".session.SessionLifecycle";

  /** The one launcher outside core.process: the REPL sandbox starts its own JVM. */
  private static final String SANDBOX_LAUNCHER = HELIOS + ".repl.sandbox.SandboxLauncher";

  /** The one class that swaps the JVM-global standard streams: the sandbox's snippet evaluator. */
  private static final String STREAM_SWAPPER = HELIOS + ".repl.sandbox.SnippetEvaluator";

  private static final String TIME_SEAM =
      "a class that needs the time takes a java.time.InstantSource through withClock(...) and"
          + " defaults it to Clock.systemUTC()";

  private static final DescribedPredicate<JavaCodeUnit> A_CONSTRUCTOR_OR_STATIC_INITIALISER =
      describe(
          "a constructor or static initialiser",
          unit -> unit.isConstructor() || unit instanceof JavaStaticInitializer);

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
            "the session module builds its one Jackson mapper in session.SessionJson, and every"
                + " other class reads JSON through the binding it holds")
        .check(LIBRARY);
  }

  @Test
  void submissionPublisherIsUsedOnlyBySessionEventPublisher() {
    noClasses()
        .that(
            not(name(SESSION_EVENT_PUBLISHER).or(nameStartingWith(SESSION_EVENT_PUBLISHER + "$"))))
        .should()
        .dependOnClassesThat(type(SubmissionPublisher.class))
        .because(
            "a session's events fan out through session.SessionEventPublisher, which owns the"
                + " SubmissionPublisher, its executor, the bounded wait on a slow subscriber and"
                + " the replay of the terminal event")
        .check(LIBRARY);
  }

  @Test
  void sessionExecutorsAreCreatedOnlyByTheEventPublisherAndTheLifecycle() {
    noClasses()
        .that()
        .resideInAPackage(HELIOS + ".session..")
        .and()
        .resideOutsideOfPackages(
            HELIOS + ".session.files..",
            HELIOS + ".session.memory..",
            HELIOS + ".session.execution..")
        .and(
            not(
                name(SESSION_EVENT_PUBLISHER)
                    .or(nameStartingWith(SESSION_EVENT_PUBLISHER + "$"))
                    .or(name(SESSION_LIFECYCLE))
                    .or(nameStartingWith(SESSION_LIFECYCLE + "$"))))
        .should()
        .accessTargetWhere(
            targetOwner(type(Executors.class))
                .and(target(nameStartingWith("new")))
                .or(
                    targetOwner(assignableTo(ExecutorService.class))
                        .and(target(name(JavaConstructor.CONSTRUCTOR_NAME)))))
        .because(
            "every pool a session owns has one owner that shuts it down: the event executor is"
                + " session.SessionEventPublisher's and the deadline scheduler is"
                + " session.SessionLifecycle's; every other class is handed the pool it uses")
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
}
