package com.vyuha.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GuidelineSearchServiceTest {

    private final GuidelineSearchService service = new GuidelineSearchService(new ObjectMapper());

    @Test
    void loadsBuiltInOfflineCorpusWithZeroExternalUrls() {
        GuidelineDocument.GuidelineCorpusOverview overview = service.getCorpusOverview();

        assertThat(overview.documentCount()).isGreaterThanOrEqualTo(4);
        assertThat(overview.totalSections()).isGreaterThanOrEqualTo(20);
        assertThat(overview.categories()).extracting(GuidelineDocument.GuidelineCategorySummary::category)
                .contains("CROWD_MANAGEMENT", "EGRESS_DESIGN", "QUEUE_MANAGEMENT", "EMERGENCY_OPS");

        GuidelineDocument.GuidelineSearchResult all = service.search("", "ALL", 50);
        assertThat(all.attributionTag()).isEqualTo(AssistantConstants.ATTRIBUTION_TAG);
        assertThat(all.hits()).allSatisfy(hit -> {
            assertThat(hit.documentTitle()).isNotBlank();
            assertThat(hit.sectionNumber()).isNotBlank();
            assertThat(hit.verbatimQuote()).isNotBlank();
            assertThat(hit.summary()).doesNotContain("http://").doesNotContain("https://");
            assertThat(hit.verbatimQuote()).doesNotContain("http://").doesNotContain("https://");
        });
    }

    @Test
    void bm25RanksExactRelevantSectionsForSafetyQueriesWithCitations() {
        GuidelineDocument.GuidelineSearchResult ambulanceResult =
                service.search("ambulance emergency lane minimum 3.5 m clear width", null, 5);
        assertThat(ambulanceResult.hits()).isNotEmpty();
        assertThat(ambulanceResult.hits().get(0).sectionId()).isEqualTo("EMERG-OPS-S1.1");
        assertThat(ambulanceResult.hits().get(0).verbatimQuote()).contains("3.5");
        assertThat(ambulanceResult.hits().get(0).score()).isGreaterThan(0.0);

        GuidelineDocument.GuidelineSearchResult contraFlowResult =
                service.search("separating entry and exit contra-flow one-way corridors", null, 5);
        assertThat(contraFlowResult.hits()).isNotEmpty();
        assertThat(contraFlowResult.hits().get(0).sectionId()).isEqualTo("NDMA-2014-S3.2");

        GuidelineDocument.GuidelineSearchResult queueResult =
                service.search("holding zone overflow spillback peak queue", null, 5);
        assertThat(queueResult.hits()).isNotEmpty();
        assertThat(queueResult.hits().get(0).sectionId()).isIn("QUEUE-OPS-S1.2", "NDMA-2014-S3.4");

        GuidelineDocument.GuidelineSearchResult deadEndResult =
                service.search("dead-end corridor exceeding 10 m trap", null, 5);
        assertThat(deadEndResult.hits()).isNotEmpty();
        assertThat(deadEndResult.hits().get(0).sectionId()).isEqualTo("EGRESS-REF-S1.3");
    }

    @Test
    void supportsCategoryFilteringAndEmptyGibberishQueriesGracefully() {
        GuidelineDocument.GuidelineSearchResult filtered =
                service.search("width", "EGRESS_DESIGN", 10);
        assertThat(filtered.hits()).isNotEmpty();
        assertThat(filtered.hits()).allSatisfy(h -> assertThat(h.category()).isEqualTo("EGRESS_DESIGN"));

        GuidelineDocument.GuidelineSearchResult noMatches =
                service.search("zxqvnonexistenttoken999", null, 10);
        assertThat(noMatches.totalMatches()).isZero();
        assertThat(noMatches.hits()).isEmpty();
        assertThat(noMatches.attributionTag()).isEqualTo(AssistantConstants.ATTRIBUTION_TAG);
    }
}
