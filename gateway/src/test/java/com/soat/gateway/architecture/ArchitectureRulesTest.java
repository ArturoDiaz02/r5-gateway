package com.soat.gateway.architecture;

import com.soat.gateway.architecture.fixtures.application.ApplicationUsingSpring;
import com.soat.gateway.architecture.fixtures.domain.DomainUsingJdbc;
import com.soat.gateway.architecture.fixtures.domain.DomainUsingSpring;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureRulesTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.soat.gateway");

    @Test
    void domainDoesNotDependOnFrameworksOrInfrastructure() {
        ArchitectureRules.DOMAIN_WITHOUT_FRAMEWORKS.check(PRODUCTION);
    }

    @Test
    void applicationDoesNotDependOnFrameworksOrInfrastructure() {
        ArchitectureRules.APPLICATION_WITHOUT_FRAMEWORKS.check(PRODUCTION);
    }

    @Test
    void signatureVerifierComparesInConstantTime() {
        ArchitectureRules.VERIFIER_COMPARES_IN_CONSTANT_TIME.check(PRODUCTION);
        ArchitectureRules.VERIFIER_WITHOUT_VARIABLE_TIME_COMPARISONS.check(PRODUCTION);
    }

    @Test
    void importsProductionClasses() {
        assertThat(PRODUCTION.contain(com.soat.gateway.domain.security.SignatureVerifier.class)).isTrue();
    }

    @Test
    void ruleDetectsDomainUsingSpring() {
        JavaClasses violating = new ClassFileImporter().importClasses(DomainUsingSpring.class);

        assertThatThrownBy(() -> ArchitectureRules.DOMAIN_WITHOUT_FRAMEWORKS.check(violating))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("org.springframework");
    }

    @Test
    void ruleDetectsDomainUsingJdbc() {
        JavaClasses violating = new ClassFileImporter().importClasses(DomainUsingJdbc.class);

        assertThatThrownBy(() -> ArchitectureRules.DOMAIN_WITHOUT_FRAMEWORKS.check(violating))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("java.sql");
    }

    @Test
    void ruleDetectsApplicationUsingSpring() {
        JavaClasses violating = new ClassFileImporter().importClasses(ApplicationUsingSpring.class);

        assertThatThrownBy(() -> ArchitectureRules.APPLICATION_WITHOUT_FRAMEWORKS.check(violating))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("org.springframework");
    }

    @Test
    void ruleDetectsVerifierComparingWithEquals() {
        JavaClasses violating = new ClassFileImporter()
                .importClasses(com.soat.gateway.architecture.fixtures.security.SignatureVerifier.class);

        assertThatThrownBy(() -> ArchitectureRules.VERIFIER_COMPARES_IN_CONSTANT_TIME.check(violating))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("isEqual");
        assertThatThrownBy(() -> ArchitectureRules.VERIFIER_WITHOUT_VARIABLE_TIME_COMPARISONS.check(violating))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("String.equals");
    }
}
