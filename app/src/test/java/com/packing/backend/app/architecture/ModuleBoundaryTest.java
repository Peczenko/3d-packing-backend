package com.packing.backend.app.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the module boundaries declared in {@code CLAUDE.md}.
 *
 * <p>This lives in {@code :app} because it is the only module whose test classpath
 * contains all five. The other modules arrive as jars, which ArchUnit imports by default.
 */
@AnalyzeClasses(
        packages = "com.packing.backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    private static final String DOMAIN = "com.packing.backend.domain..";
    private static final String CORE = "com.packing.backend.core..";
    private static final String API = "com.packing.backend.api..";
    private static final String INFRA = "com.packing.backend.infra..";
    private static final String APP = "com.packing.backend.app..";

    /**
     * The central rule of the whole architecture: the domain is plain Java. No Spring, no
     * jOOQ, no JDBC, no persistence annotations, no cloud SDKs, no web.
     */
    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "org.jooq..",
                    "jakarta.persistence..",
                    "jakarta.servlet..",
                    "javax.persistence..",
                    "java.sql..",
                    "javax.sql..",
                    "com.azure..",
                    "com.google..")
            .because("the domain model must not depend on any framework or infrastructure");

    /**
     * {@code :core} is framework-free apart from one documented exception:
     * {@code @Transactional}, which is inert metadata used to place transaction boundaries
     * at the application layer. Everything else Spring is still forbidden.
     */
    @ArchTest
    static final ArchRule coreOnlyUsesSpringForTransactionBoundaries = noClasses()
            .that().resideInAPackage(CORE)
            .should().dependOnClassesThat(
                    com.tngtech.archunit.base.DescribedPredicate.describe(
                            "reside in org.springframework.. but not in "
                                    + "org.springframework.transaction.annotation..",
                            javaClass -> javaClass.getPackageName().startsWith("org.springframework")
                                    && !javaClass.getPackageName()
                                    .startsWith("org.springframework.transaction.annotation")))
            .because("core may use @Transactional but nothing else from Spring "
                    + "(documented exception in CLAUDE.md)");

    @ArchTest
    static final ArchRule coreIsPersistenceFree = noClasses()
            .that().resideInAPackage(CORE)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.jooq..", "jakarta.persistence..", "java.sql..", "com.azure..", "com.google..")
            .because("persistence and cloud details belong in infra");

    @ArchTest
    static final ArchRule azureSdkStaysInInfra = noClasses()
            .that().resideOutsideOfPackage(INFRA)
            .should().dependOnClassesThat().resideInAnyPackage("com.azure..")
            .because("cloud SDK types must not leak past the driven adapter");

    /** Generated jOOQ classes are an implementation detail of the persistence adapter. */
    @ArchTest
    static final ArchRule generatedJooqCodeStaysInInfra = noClasses()
            .that().resideOutsideOfPackage(INFRA)
            .should().dependOnClassesThat()
            .resideInAPackage("com.packing.backend.infra.persistence.jooq..")
            .because("generated jOOQ records must never leak out of infra");

    @ArchTest
    static final ArchRule apiDoesNotDependOnInfra = noClasses()
            .that().resideInAPackage(API)
            .should().dependOnClassesThat().resideInAPackage(INFRA)
            .because("the driving adapter must not know about driven adapters");

    @ArchTest
    static final ArchRule infraDoesNotDependOnApi = noClasses()
            .that().resideInAPackage(INFRA)
            .should().dependOnClassesThat().resideInAPackage(API)
            .because("driven adapters must not know about the web layer");

    /**
     * {@code consideringOnlyDependenciesInLayers} keeps JDK and third-party dependencies
     * out of scope — those are covered by the rules above.
     */
    @ArchTest
    static final ArchRule layersRespectTheDependencyDirection = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy(DOMAIN)
            .layer("Core").definedBy(CORE)
            .layer("Api").definedBy(API)
            .layer("Infra").definedBy(INFRA)
            .layer("App").definedBy(APP)
            .whereLayer("App").mayNotBeAccessedByAnyLayer()
            .whereLayer("Api").mayOnlyBeAccessedByLayers("App")
            .whereLayer("Infra").mayOnlyBeAccessedByLayers("App")
            .whereLayer("Core").mayOnlyBeAccessedByLayers("Api", "Infra", "App")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Core", "Api", "Infra", "App");
}
