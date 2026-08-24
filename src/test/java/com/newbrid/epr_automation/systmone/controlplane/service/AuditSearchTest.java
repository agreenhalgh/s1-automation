package com.newbrid.epr_automation.systmone.controlplane.service;

import com.newbrid.epr_automation.systmone.controlplane.domain.AuditEntity;
import com.newbrid.epr_automation.systmone.controlplane.repo.AuditRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The viewer's filters, against a real schema. These are the assertions that matter for an
 * audit trail: a filter that returns too much is untidy, but one that silently returns too
 * little is a false statement about what happened.
 *
 * <p>{@code @Transactional} rolls each method back, so no test sees another's entries — which
 * matters more here than usual, since several of them assert on exact result counts.
 */
@SpringBootTest
@Transactional
class AuditSearchTest {

    @Autowired
    private AuditService audit;

    @Autowired
    private AuditRepository repo;

    private static final int PAGE = 50;

    @Test
    void unfilteredReturnsEverythingNewestFirst() {
        audit.record("operator", "DISPATCH", "bot-a", "run-1", null);
        audit.record("worker-1", "RUN_SUCCEEDED", "bot-a", "run-1", null);
        audit.record("developer", "REGISTER_BOT", "bot-b", null, "version=2");

        Page<AuditEntity> found = audit.search(AuditQuery.all(), 0, PAGE);

        assertThat(found.getTotalElements()).isEqualTo(3);
        assertThat(found.getContent()).extracting(AuditEntity::getAction)
                .as("newest first, so the last entry written comes back first")
                .containsExactly("REGISTER_BOT", "RUN_SUCCEEDED", "DISPATCH");
    }

    @Test
    void principalIsMatchedAsACaseInsensitiveSubstring() {
        audit.record("Operator.Smith", "DISPATCH", "bot-a", "run-1", null);
        audit.record("worker-ANS-01", "RUN_FAILED", "bot-a", "run-1", null);

        assertThat(audit.search(query().principal("operator").build(), 0, PAGE).getContent())
                .extracting(AuditEntity::getPrincipal)
                .containsExactly("Operator.Smith");

        assertThat(audit.search(query().principal("ANS").build(), 0, PAGE).getContent())
                .extracting(AuditEntity::getPrincipal)
                .containsExactly("worker-ANS-01");
    }

    @Test
    void actionIsMatchedExactlyRatherThanAsAPrefix() {
        audit.record("worker-1", "RUN_FAILED", "bot-a", "run-1", null);
        audit.record("worker-1", "RUN_FAILED_PERMANENTLY", "bot-a", "run-2", null);

        Page<AuditEntity> found = audit.search(query().action("RUN_FAILED").build(), 0, PAGE);

        assertThat(found.getContent()).extracting(AuditEntity::getAction)
                .as("a substring match here would overstate the failure count")
                .containsExactly("RUN_FAILED");
    }

    @Test
    void runIdMatchesAnAbbreviatedId() {
        audit.record("operator", "DISPATCH", "bot-a", "3f2a91cc-0000-4444-8888-abcdefabcdef", null);
        audit.record("operator", "DISPATCH", "bot-a", "99999999-0000-4444-8888-abcdefabcdef", null);

        assertThat(audit.search(query().runId("3f2a91cc").build(), 0, PAGE).getContent())
                .hasSize(1);
    }

    @Test
    void filtersCombineAsAnAnd() {
        audit.record("operator", "DISPATCH", "bot-a", "run-1", null);
        audit.record("operator", "DISPATCH", "bot-b", "run-2", null);
        audit.record("developer", "DISPATCH", "bot-a", "run-3", null);

        Page<AuditEntity> found =
                audit.search(query().principal("operator").botId("bot-a").build(), 0, PAGE);

        assertThat(found.getContent()).extracting(AuditEntity::getRunId).containsExactly("run-1");
    }

    /** Lower bound inclusive, upper bound exclusive — so two adjacent ranges neither overlap nor drop a row. */
    @Test
    void theTimeWindowIsInclusiveAtTheStartAndExclusiveAtTheEnd() {
        audit.record("operator", "DISPATCH", "bot-a", "run-1", null);
        Instant stamp = repo.findAll().getFirst().getTs();

        assertThat(audit.search(new AuditQuery(null, null, null, null, stamp, null), 0, PAGE)
                .getTotalElements())
                .as("from is >= its own timestamp, so the entry is in the window")
                .isEqualTo(1);

        assertThat(audit.search(new AuditQuery(null, null, null, null, null, stamp), 0, PAGE)
                .getTotalElements())
                .as("to is <, so the entry falls outside a window that ends at its own timestamp")
                .isZero();
    }

    @Test
    void aTypedWildcardIsALiteralNotAPattern() {
        audit.record("operator", "DISPATCH", "bot-a", "run-1", null);
        audit.record("100%-synthetic", "DISPATCH", "bot-a", "run-2", null);

        assertThat(audit.search(query().principal("%").build(), 0, PAGE).getContent())
                .as("a bare % must find the principal containing one, not every row")
                .extracting(AuditEntity::getPrincipal)
                .containsExactly("100%-synthetic");
    }

    @Test
    void blankFormFieldsAreNotFilters() {
        audit.record("operator", "DISPATCH", "bot-a", "run-1", null);

        AuditQuery submitted = new AuditQuery("", "", "  ", "", null, null);

        assertThat(submitted.filtered())
                .as("an untouched form must not read as a filter")
                .isFalse();
        assertThat(audit.search(submitted, 0, PAGE).getTotalElements()).isEqualTo(1);
    }

    @Test
    void actionsListsWhatIsPresentWithoutDuplicates() {
        audit.record("operator", "DISPATCH", "bot-a", "run-1", null);
        audit.record("operator", "DISPATCH", "bot-b", "run-2", null);
        audit.record("worker-1", "RUN_SUCCEEDED", "bot-a", "run-1", null);

        assertThat(audit.actions()).containsExactly("DISPATCH", "RUN_SUCCEEDED");
    }

    @Test
    void pagingWalksTheWholeTrailWithoutRepeatingOrSkipping() {
        for (int i = 0; i < 7; i++) {
            audit.record("operator", "DISPATCH", "bot-a", "run-" + i, null);
        }

        List<String> firstPage = audit.search(AuditQuery.all(), 0, 3).getContent()
                .stream().map(AuditEntity::getRunId).toList();
        List<String> secondPage = audit.search(AuditQuery.all(), 1, 3).getContent()
                .stream().map(AuditEntity::getRunId).toList();
        List<String> thirdPage = audit.search(AuditQuery.all(), 2, 3).getContent()
                .stream().map(AuditEntity::getRunId).toList();

        assertThat(firstPage).hasSize(3);
        assertThat(secondPage).hasSize(3);
        assertThat(thirdPage).hasSize(1);
        assertThat(firstPage).doesNotContainAnyElementsOf(secondPage);
        assertThat(firstPage).doesNotContainAnyElementsOf(thirdPage);
        // Entries written in one transaction can share a timestamp, so the ordering has to fall
        // back to the identity column — without that tie-break these pages could overlap.
        assertThat(audit.search(AuditQuery.all(), 0, PAGE).getContent())
                .extracting(AuditEntity::getRunId)
                .startsWith("run-6", "run-5", "run-4");
    }

    private static Builder query() {
        return new Builder();
    }

    /** Keeps the tests readable when only one or two of the six filters are being set. */
    private static final class Builder {
        private String principal;
        private String action;
        private String botId;
        private String runId;

        Builder principal(String v) { this.principal = v; return this; }
        Builder action(String v) { this.action = v; return this; }
        Builder botId(String v) { this.botId = v; return this; }
        Builder runId(String v) { this.runId = v; return this; }

        AuditQuery build() {
            return new AuditQuery(principal, action, botId, runId, null, null);
        }
    }
}
