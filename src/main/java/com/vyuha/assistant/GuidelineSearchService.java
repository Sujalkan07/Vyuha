package com.vyuha.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * Pure-Java Okapi BM25 retrieval engine over the built-in offline crowd-safety guideline corpus.
 * Returns ranked excerpts with exact document title, section number, authority, and verbatim quotes.
 */
@Service
public class GuidelineSearchService {

    private static final double BM25_K1 = 1.5;
    private static final double BM25_B = 0.75;
    private static final double TITLE_TOPIC_WEIGHT = 2.2;
    private static final double EXACT_PHRASE_BONUS = 2.5;
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 30;

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "for", "from",
            "has", "have", "how", "in", "into", "is", "it", "its", "must", "not", "of",
            "on", "or", "should", "such", "than", "that", "the", "their", "this", "to",
            "under", "using", "what", "when", "where", "which", "while", "with"
    );

    private final List<GuidelineDocument.DocumentSpec> documents;
    private final List<IndexedSection> indexedSections;
    private final Map<String, Double> bodyIdf;
    private final Map<String, Double> titleIdf;
    private final double avgBodyLen;
    private final double avgTitleLen;

    @Autowired
    public GuidelineSearchService(ObjectMapper objectMapper) {
        this(loadCorpusFromClasspath(objectMapper));
    }

    /**
     * Pure-Java constructor enabling deterministic unit tests without Spring context.
     */
    public GuidelineSearchService(List<GuidelineDocument.DocumentSpec> documents) {
        List<GuidelineDocument.DocumentSpec> safeDocs = documents != null ? documents : List.of();
        this.documents = List.copyOf(safeDocs);
        this.indexedSections = buildIndexedSections(this.documents);
        this.avgBodyLen = computeAverageLength(indexedSections, true);
        this.avgTitleLen = computeAverageLength(indexedSections, false);
        this.bodyIdf = computeIdf(indexedSections, true);
        this.titleIdf = computeIdf(indexedSections, false);
    }

    public GuidelineDocument.GuidelineSearchResult search(String query, String category, Integer requestedLimit) {
        int limit = clampLimit(requestedLimit);
        String cleanQuery = query == null ? "" : query.trim();
        String cleanCategory = normalizeCategory(category);

        List<GuidelineDocument.GuidelineCategorySummary> categories = buildCategorySummaries();

        if (cleanQuery.isEmpty()) {
            List<GuidelineDocument.GuidelineHit> browseHits = indexedSections.stream()
                    .filter(s -> cleanCategory == null || cleanCategory.equalsIgnoreCase(s.doc().category()))
                    .limit(limit)
                    .map(s -> toHit(s, 0.0, List.of()))
                    .toList();
            long totalMatching = indexedSections.stream()
                    .filter(s -> cleanCategory == null || cleanCategory.equalsIgnoreCase(s.doc().category()))
                    .count();
            return new GuidelineDocument.GuidelineSearchResult(
                    AssistantConstants.ATTRIBUTION_TAG,
                    AssistantConstants.GUIDELINE_DISCLAIMER,
                    cleanQuery,
                    cleanCategory,
                    (int) totalMatching,
                    indexedSections.size(),
                    browseHits,
                    categories
            );
        }

        List<String> queryTokens = tokenize(cleanQuery);
        String lowerQuery = cleanQuery.toLowerCase(Locale.ROOT);
        List<ScoredSection> scored = new ArrayList<>();

        for (IndexedSection entry : indexedSections) {
            if (cleanCategory != null && !cleanCategory.equalsIgnoreCase(entry.doc().category())) {
                continue;
            }
            if (queryTokens.isEmpty()) {
                continue;
            }
            double bodyScore = scoreBm25(queryTokens, entry.bodyTermFreq(), entry.bodyLen(), avgBodyLen, bodyIdf);
            double titleScore = scoreBm25(queryTokens, entry.titleTermFreq(), entry.titleLen(), avgTitleLen, titleIdf);
            double totalScore = bodyScore + (TITLE_TOPIC_WEIGHT * titleScore);

            if (totalScore <= 0.0) {
                continue;
            }
            if (queryTokens.size() >= 2 && entry.searchableLower().contains(lowerQuery)) {
                totalScore += EXACT_PHRASE_BONUS;
            }

            Set<String> matched = new LinkedHashSet<>();
            for (String token : queryTokens) {
                if (entry.bodyTermFreq().containsKey(token) || entry.titleTermFreq().containsKey(token)) {
                    matched.add(token);
                }
            }

            scored.add(new ScoredSection(entry, round3(totalScore), List.copyOf(matched)));
        }

        scored.sort(Comparator.comparingDouble(ScoredSection::score).reversed()
                .thenComparing(s -> s.section().doc().documentId())
                .thenComparing(s -> s.section().section().sectionNumber()));

        List<GuidelineDocument.GuidelineHit> hits = scored.stream()
                .limit(limit)
                .map(s -> toHit(s.section(), s.score(), s.matchedTerms()))
                .toList();

        return new GuidelineDocument.GuidelineSearchResult(
                AssistantConstants.ATTRIBUTION_TAG,
                AssistantConstants.GUIDELINE_DISCLAIMER,
                cleanQuery,
                cleanCategory,
                scored.size(),
                indexedSections.size(),
                hits,
                categories
        );
    }

    public List<GuidelineDocument.GuidelineHit> findCitationsForCode(String code, String fallbackQuery, int maxHits) {
        int limit = Math.max(1, Math.min(maxHits, 10));
        Map<String, GuidelineDocument.GuidelineHit> collected = new LinkedHashMap<>();

        if (code != null && !code.isBlank()) {
            String target = code.trim().toUpperCase(Locale.ROOT);
            for (IndexedSection entry : indexedSections) {
                List<String> codes = entry.section().applicableCodes();
                if (codes != null && codes.stream().anyMatch(c -> target.equalsIgnoreCase(c))) {
                    collected.putIfAbsent(entry.section().sectionId(),
                            toHit(entry, 10.0, List.of(target.toLowerCase(Locale.ROOT))));
                }
            }
        }

        if (collected.size() < limit && fallbackQuery != null && !fallbackQuery.isBlank()) {
            GuidelineDocument.GuidelineSearchResult result = search(fallbackQuery, null, limit);
            for (GuidelineDocument.GuidelineHit hit : result.hits()) {
                collected.putIfAbsent(hit.sectionId(), hit);
                if (collected.size() >= limit) {
                    break;
                }
            }
        }

        return collected.values().stream().limit(limit).toList();
    }

    public GuidelineDocument.GuidelineCorpusOverview getCorpusOverview() {
        List<GuidelineDocument.GuidelineDocumentSummary> docSummaries = documents.stream()
                .map(d -> new GuidelineDocument.GuidelineDocumentSummary(
                        d.documentId(),
                        d.documentTitle(),
                        d.authority(),
                        d.publicationYear(),
                        d.category(),
                        d.categoryDisplayName(),
                        d.sections() != null ? d.sections().size() : 0
                ))
                .toList();
        return new GuidelineDocument.GuidelineCorpusOverview(
                documents.size(),
                indexedSections.size(),
                buildCategorySummaries(),
                docSummaries
        );
    }

    private List<GuidelineDocument.GuidelineCategorySummary> buildCategorySummaries() {
        Map<String, GuidelineDocument.GuidelineCategorySummary> map = new LinkedHashMap<>();
        for (GuidelineDocument.DocumentSpec doc : documents) {
            int count = doc.sections() != null ? doc.sections().size() : 0;
            GuidelineDocument.GuidelineCategorySummary existing = map.get(doc.category());
            if (existing == null) {
                map.put(doc.category(), new GuidelineDocument.GuidelineCategorySummary(
                        doc.category(), doc.categoryDisplayName(), count));
            } else {
                map.put(doc.category(), new GuidelineDocument.GuidelineCategorySummary(
                        doc.category(), existing.displayName(), existing.sectionCount() + count));
            }
        }
        return List.copyOf(map.values());
    }

    private static List<GuidelineDocument.DocumentSpec> loadCorpusFromClasspath(ObjectMapper objectMapper) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath*:guidelines/*.json");
            Arrays.sort(resources, Comparator.comparing(r -> r.getFilename() != null ? r.getFilename() : ""));
            List<GuidelineDocument.DocumentSpec> loaded = new ArrayList<>();
            for (Resource res : resources) {
                try (InputStream in = res.getInputStream()) {
                    GuidelineDocument.DocumentSpec doc = objectMapper.readValue(in, GuidelineDocument.DocumentSpec.class);
                    if (doc != null && doc.sections() != null && !doc.sections().isEmpty()) {
                        loaded.add(doc);
                    }
                }
            }
            return List.copyOf(loaded);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load built-in safety guideline corpus.", ex);
        }
    }

    private static List<IndexedSection> buildIndexedSections(List<GuidelineDocument.DocumentSpec> docs) {
        List<IndexedSection> list = new ArrayList<>();
        for (GuidelineDocument.DocumentSpec doc : docs) {
            if (doc.sections() == null) {
                continue;
            }
            for (GuidelineDocument.GuidelineSection sec : doc.sections()) {
                String topicsJoined = sec.topics() != null ? String.join(" ", sec.topics()) : "";
                String titleText = String.join(" ",
                        safeStr(doc.documentTitle()),
                        safeStr(sec.sectionNumber()),
                        safeStr(sec.sectionTitle()),
                        topicsJoined);
                String bodyText = String.join(" ",
                        safeStr(sec.summary()),
                        safeStr(sec.verbatimQuote()),
                        topicsJoined);

                List<String> titleTokens = tokenize(titleText);
                List<String> bodyTokens = tokenize(bodyText);

                Map<String, Integer> titleFreq = termFrequencies(titleTokens);
                Map<String, Integer> bodyFreq = termFrequencies(bodyTokens);
                String searchableLower = (titleText + " " + bodyText).toLowerCase(Locale.ROOT);

                list.add(new IndexedSection(
                        doc,
                        sec,
                        titleFreq,
                        bodyFreq,
                        Math.max(1, titleTokens.size()),
                        Math.max(1, bodyTokens.size()),
                        searchableLower
                ));
            }
        }
        return List.copyOf(list);
    }

    private static double computeAverageLength(List<IndexedSection> sections, boolean body) {
        if (sections.isEmpty()) {
            return 1.0;
        }
        double sum = 0.0;
        for (IndexedSection s : sections) {
            sum += body ? s.bodyLen() : s.titleLen();
        }
        return Math.max(1.0, sum / sections.size());
    }

    private static Map<String, Double> computeIdf(List<IndexedSection> sections, boolean body) {
        Map<String, Integer> docFreq = new HashMap<>();
        for (IndexedSection s : sections) {
            Set<String> terms = body ? s.bodyTermFreq().keySet() : s.titleTermFreq().keySet();
            for (String term : terms) {
                docFreq.merge(term, 1, Integer::sum);
            }
        }
        int n = Math.max(1, sections.size());
        Map<String, Double> idf = new HashMap<>();
        for (Map.Entry<String, Integer> entry : docFreq.entrySet()) {
            int df = entry.getValue();
            double value = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
            idf.put(entry.getKey(), Math.max(0.01, value));
        }
        return Map.copyOf(idf);
    }

    private static double scoreBm25(List<String> queryTokens,
                                    Map<String, Integer> termFreq,
                                    int docLen,
                                    double avgDocLen,
                                    Map<String, Double> idfMap) {
        double score = 0.0;
        Set<String> seen = new LinkedHashSet<>(queryTokens);
        for (String term : seen) {
            int tf = termFreq.getOrDefault(term, 0);
            if (tf <= 0) {
                continue;
            }
            double idf = idfMap.getOrDefault(term, 0.0);
            double denom = tf + BM25_K1 * (1.0 - BM25_B + BM25_B * (docLen / avgDocLen));
            score += idf * ((tf * (BM25_K1 + 1.0)) / denom);
        }
        return score;
    }

    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.]+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        String[] rawParts = normalized.split("\\s+");
        List<String> tokens = new ArrayList<>(rawParts.length);
        for (String part : rawParts) {
            String cleaned = part.replaceAll("^\\.+|\\.+$", "");
            if (cleaned.isEmpty() || STOPWORDS.contains(cleaned)) {
                continue;
            }
            tokens.add(stem(cleaned));
        }
        return tokens;
    }

    private static String stem(String token) {
        if (token.length() <= 3 || token.matches(".*\\d.*")) {
            return token;
        }
        if (token.endsWith("ies") && token.length() > 5) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.endsWith("ing") && token.length() > 5) {
            return token.substring(0, token.length() - 3);
        }
        if (token.endsWith("ed") && token.length() > 4) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("es") && token.length() > 4 && !token.endsWith("ses")) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("s") && token.length() > 3 && !token.endsWith("ss")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    private static Map<String, Integer> termFrequencies(List<String> tokens) {
        Map<String, Integer> freq = new HashMap<>();
        for (String token : tokens) {
            freq.merge(token, 1, Integer::sum);
        }
        return Map.copyOf(freq);
    }

    private static GuidelineDocument.GuidelineHit toHit(IndexedSection entry, double score, List<String> matchedTerms) {
        GuidelineDocument.DocumentSpec doc = entry.doc();
        GuidelineDocument.GuidelineSection sec = entry.section();
        return new GuidelineDocument.GuidelineHit(
                doc.documentId(),
                doc.documentTitle(),
                doc.authority(),
                doc.publicationYear(),
                doc.category(),
                doc.categoryDisplayName(),
                sec.sectionId(),
                sec.sectionNumber(),
                sec.sectionTitle(),
                sec.topics() != null ? List.copyOf(sec.topics()) : List.of(),
                sec.summary(),
                sec.verbatimQuote(),
                score,
                matchedTerms != null ? matchedTerms : List.of()
        );
    }

    private static int clampLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank() || "ALL".equalsIgnoreCase(category.trim())) {
            return null;
        }
        return category.trim().toUpperCase(Locale.ROOT);
    }

    private static String safeStr(String s) {
        return s != null ? s : "";
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private record IndexedSection(
            GuidelineDocument.DocumentSpec doc,
            GuidelineDocument.GuidelineSection section,
            Map<String, Integer> titleTermFreq,
            Map<String, Integer> bodyTermFreq,
            int titleLen,
            int bodyLen,
            String searchableLower
    ) {
    }

    private record ScoredSection(
            IndexedSection section,
            double score,
            List<String> matchedTerms
    ) {
    }
}
