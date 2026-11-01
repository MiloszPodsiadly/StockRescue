package com.stockrescue.platform;

import com.stockrescue.StockRescueApplication;
import com.stockrescue.identity.http.AuthController;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureTest {

    private static final ArchRule PLATFORM_IS_INDEPENDENT = noClasses()
            .that().resideInAPackage("com.stockrescue.platform..")
            .should().dependOnClassesThat().haveFullyQualifiedName(StockRescueApplication.class.getName())
            .because("technical adapters must not depend on the application bootstrap");

    private static final ArchRule PLATFORM_HAS_NO_BUSINESS_DEPENDENCIES = noClasses()
            .that().resideInAPackage("com.stockrescue.platform..")
            .should().dependOnClassesThat().resideInAnyPackage("com.stockrescue.identity..",
                    "com.stockrescue.organizations..", "com.stockrescue.access..", "com.stockrescue.stores..")
            .because("features implement platform ports, while technical adapters do not own business rules");

    @Test
    void featureHttpAndApplicationCodeRespectThePersistenceBoundary() {
        var production = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.stockrescue");
        PLATFORM_HAS_NO_BUSINESS_DEPENDENCIES.check(production);
        noClasses().that().resideInAnyPackage("com.stockrescue.identity.http..", "com.stockrescue.organizations.http..",
                        "com.stockrescue.access.http..", "com.stockrescue.stores.http..")
                .should().dependOnClassesThat().resideInAnyPackage("com.stockrescue.identity.persistence..",
                        "com.stockrescue.organizations.persistence..", "com.stockrescue.access.persistence..", "com.stockrescue.stores.persistence..")
                .check(production);
        noClasses().that().resideInAnyPackage("com.stockrescue.identity.application..", "com.stockrescue.organizations.application..",
                        "com.stockrescue.access.application..", "com.stockrescue.stores.application..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")
                .because("application services delegate SQL to their feature repositories")
                .check(production);
        noClasses().that().resideInAnyPackage("com.stockrescue.identity.application..", "com.stockrescue.organizations.application..",
                        "com.stockrescue.access.application..", "com.stockrescue.stores.application..")
                .should().dependOnClassesThat().resideInAnyPackage("jakarta.servlet..", "com.stockrescue.identity.http..",
                        "com.stockrescue.organizations.http..", "com.stockrescue.access.http..", "com.stockrescue.stores.http..")
                .check(production);
    }

    @Test
    void forbiddenBusinessDependencyActuallyFailsTheRule() {
        var fixture = new ClassFileImporter().importClasses(ForbiddenBusinessCoupling.class, AuthController.class);
        assertThatThrownBy(() -> PLATFORM_HAS_NO_BUSINESS_DEPENDENCIES.check(fixture))
                .isInstanceOf(AssertionError.class).hasMessageContaining("AuthController");
    }

    @Test
    void platformDoesNotDependOnApplicationBootstrap() {
        var production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.stockrescue");
        PLATFORM_IS_INDEPENDENT.check(production);
    }

    @Test
    void forbiddenDependencyActuallyFailsTheRule() {
        var fixture = new ClassFileImporter().importClasses(ForbiddenCoupling.class, StockRescueApplication.class);
        assertThatThrownBy(() -> PLATFORM_IS_INDEPENDENT.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("StockRescueApplication");
    }

    private static class ForbiddenCoupling {
        private final Class<?> bootstrap = StockRescueApplication.class;
    }

    private static class ForbiddenBusinessCoupling {
        private final Class<?> controller = AuthController.class;
    }
}
