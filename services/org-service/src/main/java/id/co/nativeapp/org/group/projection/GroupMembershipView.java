package id.co.nativeapp.org.group.projection;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection over the {@code group_membership} row — only the columns the list-group-members
 * read path needs, never the {@code Auditable} bookkeeping.
 *
 * <p>Backs the native read query on {@link
 * id.co.nativeapp.org.group.repository.GroupMembershipRepository} ({@code findMemberViews}).
 * Snake_case native-query aliases map to these accessors via Spring Data's projection-interface
 * convention (CLAUDE.md "native-query aliases snake_case; map via projection interfaces"), so a
 * read path fetches a narrow column set instead of {@code SELECT *} of the full entity. Lives in
 * its own {@code projection} package — a read model is neither the write-side {@code domain} entity
 * nor a request/response {@code dto}.
 */
public interface GroupMembershipView {

  UUID getMemberCompanyId();

  LocalDate getEffectiveFrom();

  LocalDate getEffectiveTo();
}
