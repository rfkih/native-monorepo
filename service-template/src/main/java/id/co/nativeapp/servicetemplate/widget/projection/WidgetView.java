package id.co.nativeapp.servicetemplate.widget.projection;

/**
 * Read projection over the {@code widget} row — the template's worked example of the native-query +
 * projection convention (CLAUDE.md "Conventions"; docs/CODE-STRUCTURE.md §3.3).
 *
 * <p>A read path selects ONLY the columns it needs into this interface — snake_case native-query
 * aliases map to these camelCase getters — never {@code SELECT *} of the full {@code Auditable}
 * entity. It lives in its own {@code projection} package: a read model is neither the write-side
 * {@code domain} entity nor a request/response {@code dto}, and the ArchUnit {@code Projection}
 * layer keeps it reachable only from the service and repository.
 *
 * <p>When you clone this template, replace this with your aggregate's read shape (and delete the
 * package entirely if the feature has no read path).
 */
public interface WidgetView {

  Long getId();

  String getName();
}
