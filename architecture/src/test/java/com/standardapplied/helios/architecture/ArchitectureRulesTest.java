/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.targetOwner;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.module.ModuleFinder;
import java.net.http.HttpClient;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

  /** Burn-down, core-and-tools: the one class that still builds its own client. */
  private static final String OWN_HTTP_CLIENT_BURN_DOWN = HELIOS + ".onnx.OnnxModelDownloader";

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
        .and()
        .doNotHaveFullyQualifiedName(OWN_HTTP_CLIENT_BURN_DOWN)
        .should()
        .accessTargetWhere(
            targetOwner(type(HttpClient.class))
                .and(target(name("newBuilder").or(name("newHttpClient")))))
        .because("every java.net.http.HttpClient comes from core.common.HttpClientFactory")
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
}
