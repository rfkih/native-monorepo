package id.co.nativeapp.restaurant.recipe;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Recipe-scoped regression guard for two ArchUnit layering rules the recipe main source originally
 * tripped, both since fixed in main source (this test package never touches main source — QA
 * scope):
 *
 * <ol>
 *   <li>{@code repositoriesAreAccessedOnlyByTheServiceLayer()} in the fleet-wide {@code
 *       config.LayeredArchitectureTest} — originally, {@code RecipeIngredientGuard} and {@code
 *       RecipeModifierCascade} held a {@code RecipeLineRepository} field directly. That rule allows
 *       a {@code JpaRepository} to be depended on only by a class whose SIMPLE NAME ends {@code
 *       Service}, {@code Writer}, or {@code Reader} — {@code *Guard}/{@code *Cascade} does not
 *       qualify. Fixed by routing both classes through {@code RecipeReader}/{@code RecipeWriter}
 *       instead of holding the repository directly.
 *   <li>{@code featureLayersRespectTheLayeredArchitecture()} in the same class — originally, {@code
 *       RecipeResponse.LineResponse.from(RecipeLineView)} (a {@code dto}) called getters directly
 *       on {@code RecipeLineView} (a {@code projection}), the one dto in the whole service with a
 *       {@code from(...View)} static factory. Fixed by moving the projection -> dto mapping into
 *       {@code RecipeReader} (mirroring every other feature).
 * </ol>
 *
 * <p>These two rules are RESTATED here, scoped to the {@code recipe} package only, so a regression
 * is caught fast and locally without waiting on the (also still-enforcing) fleet-wide {@code
 * LayeredArchitectureTest}.
 */
class RecipeArchitectureFitTest {

  private static final String RECIPE_PACKAGE = "id.co.nativeapp.restaurant.recipe";

  private static JavaClasses recipeClasses() {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(RECIPE_PACKAGE);
  }

  @Test
  void repositoryIsOnlyDependedOnByServiceWriterOrReaderNamedClasses() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(RECIPE_PACKAGE + "..")
            .and()
            .haveSimpleNameNotEndingWith("Service")
            .and()
            .haveSimpleNameNotEndingWith("Writer")
            .and()
            .haveSimpleNameNotEndingWith("Reader")
            .and()
            .haveSimpleNameNotEndingWith("Repository")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Repository")
            .as("only *Service/*Writer/*Reader classes may depend on a *Repository");
    rule.check(recipeClasses());
  }

  @Test
  void dtoLayerNeverDependsOnProjectionLayer() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(RECIPE_PACKAGE + ".dto..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(RECIPE_PACKAGE + ".projection..")
            .as("recipe dto classes never depend on recipe projection classes");
    rule.check(recipeClasses());
  }
}
