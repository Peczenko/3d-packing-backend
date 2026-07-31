package com.packing.backend.app.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.packing.backend",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    private static final String DOMAIN = "com.packing.backend.domain..";
    private static final String CORE = "com.packing.backend.core..";
    private static final String API = "com.packing.backend.api..";
    private static final String INFRA = "com.packing.backend.infra..";
    private static final String APP = "com.packing.backend.app..";

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

    @ArchTest
    static final ArchRule coreOnlyUsesSpringForTransactionBoundariesAndStereotypes = noClasses()
            .that().resideInAPackage(CORE)
            .should().dependOnClassesThat(
                    com.tngtech.archunit.base.DescribedPredicate.describe(
                            "reside in org.springframework.. but not in "
                                    + "org.springframework.transaction.annotation.. or "
                                    + "org.springframework.stereotype..",
                            javaClass -> javaClass.getPackageName().startsWith("org.springframework")
                                    && !javaClass.getPackageName()
                                    .startsWith("org.springframework.transaction.annotation")
                                    && !javaClass.getPackageName()
                                    .startsWith("org.springframework.stereotype")))
            .because("core may use @Transactional and @Service but nothing else from Spring "
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

    @ArchTest
    static final ArchRule generatedJooqCodeStaysInInfra = noClasses()
            .that().resideOutsideOfPackage(INFRA)
            .should().dependOnClassesThat()
            .resideInAPackage("com.packing.backend.infra.persistence.jooq..")
            .because("generated jOOQ records must never leak out of infra");

    @ArchTest
    static final ArchRule postgresDriverStaysInTheConstraintTranslator = noClasses()
            .that().resideOutsideOfPackage("com.packing.backend.infra.persistence.shared")
            .should().dependOnClassesThat().resideInAPackage("org.postgresql..")
            .because("only the constraint translator may know which driver is underneath");

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
