package com.ir.cases.arch;

import com.ir.common.aspect.Audited;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * Spring AOP only intercepts external calls on public methods of beans. An {@code @Audited}
 * private/helper method would silently never be audited (self-invocation), so forbid it.
 */
@AnalyzeClasses(packages = "com.ir.cases", importOptions = ImportOption.DoNotIncludeTests.class)
class AuditArchitectureTest {

    @ArchTest
    static final ArchRule auditedOnlyOnPublicServiceMethods = methods()
            .that().areAnnotatedWith(Audited.class)
            .should().bePublic()
            .andShould().beDeclaredInClassesThat().areAnnotatedWith(Service.class);

    @ArchTest
    static final ArchRule auditedMethodsAreTransactional = methods()
            .that().areAnnotatedWith(Audited.class)
            .should().beAnnotatedWith(Transactional.class);
}
