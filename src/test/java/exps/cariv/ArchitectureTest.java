package exps.cariv;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "exps.cariv", importOptions = {ImportOption.DoNotIncludeTests.class})
class ArchitectureTest {

    @ArchTest
    static final ArchRule loginLayerDependencyRule = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("LoginController").definedBy("..domain.login.controller..")
            .layer("LoginService").definedBy("..domain.login.service..")
            .layer("LoginRepository").definedBy("..domain.login.repository..")
            .whereLayer("LoginController").mayNotBeAccessedByAnyLayer()
            .whereLayer("LoginService").mayOnlyBeAccessedByLayers("LoginController", "LoginService")
            .whereLayer("LoginRepository").mayOnlyBeAccessedByLayers("LoginService", "LoginRepository");

    @ArchTest
    static final ArchRule controllerMustNotDependOnRepository = noClasses()
            .that().resideInAPackage("..domain.login.controller..")
            .should().dependOnClassesThat().resideInAnyPackage("..domain.login.repository..");

    @ArchTest
    static final ArchRule requestDtoPackageAndNamingRule = classes()
            .that().resideInAPackage("..domain.login.dto.request..")
            .should().haveSimpleNameEndingWith("Request");

    @ArchTest
    static final ArchRule responseDtoPackageAndNamingRule = classes()
            .that().resideInAPackage("..domain.login.dto.response..")
            .should().haveSimpleNameEndingWith("Response")
            .orShould().haveSimpleNameEndingWith("Result");
}
