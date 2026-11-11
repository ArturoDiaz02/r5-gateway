package com.soat.gateway.architecture;

import com.tngtech.archunit.lang.ArchRule;

import java.security.MessageDigest;
import java.util.Arrays;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

final class ArchitectureRules {

    static final ArchRule DOMAIN_WITHOUT_FRAMEWORKS = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "com.rabbitmq..",
                    "com.fasterxml..",
                    "java.sql..",
                    "javax.sql..")
            .allowEmptyShould(true);

    static final ArchRule APPLICATION_WITHOUT_FRAMEWORKS = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "com.rabbitmq..",
                    "java.sql..",
                    "javax.sql..",
                    "..infrastructure..")
            .allowEmptyShould(true);

    static final ArchRule VERIFIER_COMPARES_IN_CONSTANT_TIME = classes()
            .that().haveSimpleName("SignatureVerifier")
            .should().callMethod(MessageDigest.class, "isEqual", byte[].class, byte[].class);

    static final ArchRule VERIFIER_WITHOUT_VARIABLE_TIME_COMPARISONS = noClasses()
            .that().haveSimpleName("SignatureVerifier")
            .should().callMethod(String.class, "equals", Object.class)
            .orShould().callMethod(Arrays.class, "equals", byte[].class, byte[].class);

    private ArchitectureRules() {
    }
}
