package vega.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Enforces Constitution Principle III mechanically.
 *
 * <p>An architecture rule that no build can fail on decays into folder naming within a month. These
 * tests are the reason the hexagonal claim is checkable rather than decorative, and they are also
 * what makes Principle II structural: if the core cannot import lsp4j, headless usability is a
 * property of the build rather than something to remember.
 */
class ArchitectureRulesTest {

    private static JavaClasses coreClasses;

    @BeforeAll
    static void importCore() {
        coreClasses =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("vega.core");
    }

    @Test
    void coreDoesNotDependOnAnyAdapterTechnology() {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("vega.core..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage(
                                "io.vertx..",
                                "org.eclipse.lsp4j..",
                                "org.jooq..",
                                "org.sqlite..",
                                "io.github.treesitter..")
                        .because(
                                "the analysis core must stay runnable and testable without any adapter "
                                    + "(Constitution Principles II and III)");

        rule.check(coreClasses);
    }

    @Test
    void coreDoesNotDependOnAdapterOrApplicationPackages() {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("vega.core..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage("vega.lsp..", "vega.syntax..", "vega.fs..", "vega.app..")
                        .because("dependencies must point inward, never from the core out to an adapter");

        rule.check(coreClasses);
    }

    @Test
    void coreDoesNotPerformItsOwnFileOrNetworkAccess() {
        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAPackage("vega.core..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAnyPackage("java.nio.file..", "java.net..", "java.io..")
                        .because(
                                "all file access belongs behind FileGatewayPort so it can follow a remote "
                                    + "backend (FR-002)");

        rule.check(coreClasses);
    }
}
