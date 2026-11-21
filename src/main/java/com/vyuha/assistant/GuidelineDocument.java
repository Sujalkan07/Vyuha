package com.vyuha.assistant;

import java.util.List;

/**
 * Immutable domain models and search DTOs for the built-in offline safety guideline corpus.
 */
public final class GuidelineDocument {

    private GuidelineDocument() {
    }

    public record DocumentSpec(
            String documentId,
            String documentTitle,
            String authority,
            int publicationYear,
            String category,
            String categoryDisplayName,
            List<GuidelineSection> sections
    ) {
    }

    public record GuidelineSection(
            String sectionId,
            String sectionNumber,
            String sectionTitle,
            List<String> topics,
            List<String> applicableCodes,
            String summary,
            String verbatimQuote
    ) {
    }

    public record GuidelineHit(
            String documentId,
            String documentTitle,
            String authority,
            int publicationYear,
            String category,
            String categoryDisplayName,
            String sectionId,
            String sectionNumber,
            String sectionTitle,
            List<String> topics,
            String summary,
            String verbatimQuote,
            double score,
            List<String> matchedTerms
    ) {
    }

    public record GuidelineCategorySummary(
            String category,
            String displayName,
            int sectionCount
    ) {
    }

    public record GuidelineDocumentSummary(
            String documentId,
            String documentTitle,
            String authority,
            int publicationYear,
            String category,
            String categoryDisplayName,
            int sectionCount
    ) {
    }

    public record GuidelineCorpusOverview(
            int documentCount,
            int totalSections,
            List<GuidelineCategorySummary> categories,
            List<GuidelineDocumentSummary> documents
    ) {
    }

    public record GuidelineSearchResult(
            String attributionTag,
            String disclaimer,
            String query,
            String category,
            int totalMatches,
            int totalCorpusSections,
            List<GuidelineHit> hits,
            List<GuidelineCategorySummary> categories
    ) {
    }
}
