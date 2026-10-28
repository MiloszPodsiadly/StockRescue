package com.stockrescue.platform;

import com.stockrescue.StockRescueApplication;
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
}
