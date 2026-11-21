package com.vyuha.assistant;

import com.vyuha.network.PathwayNodeType;
import com.vyuha.simulation.SimulationConfig;
import com.vyuha.simulation.SimulationDtos;
import com.vyuha.simulation.SimulationScenarioType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Pure-Java natural-language parser translating plain-English "What-If" crowd scenarios
 * into structured SimulationConfig parameters for explicit human confirmation before execution.
 */
@Service
public class ScenarioParserService {

    private static final Pattern AGENT_PATTERN = Pattern.compile(
            "(\\d{2,4})\\s*(?:agents?|visitors?|attendees?|people|crowd)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DURATION_MIN_PATTERN = Pattern.compile(
            "(\\d{1,3})\\s*(?:min|mins|minute|minutes)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DURATION_SEC_PATTERN = Pattern.compile(
            "(?:duration|for)\\s+(\\d{2,4})\\s*(?:s|sec|secs|second|seconds)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DISRUPTION_TIME_SEC_PATTERN = Pattern.compile(
            "(?:at|after|from)\\s*(?:t\\s*=\\s*)?(\\d{1,4})\\s*(?:s|sec|secs|second|seconds)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DISRUPTION_TIME_MIN_PATTERN = Pattern.compile(
            "(?:at|after|from)\\s*(\\d{1,2})\\s*(?:min|mins|minute|minutes)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SEED_PATTERN = Pattern.compile(
            "(?:seed)\\s*[#:=]?\\s*(\\d{1,9})\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Set<String> STOP_TOKENS = Set.of(
            "the", "a", "an", "if", "is", "are", "was", "during", "under", "with", "for",
            "when", "what", "happens", "happen", "test", "simulate", "run", "scenario",
            "sudden", "normal", "operations", "closes", "closed", "close", "shut", "shuts",
            "block", "blocked", "blocking", "disable", "disabled", "unavailable", "outage"
    );

    private static final Set<String> GENERIC_TARGET_TOKENS = Set.of(
            "exit", "gate", "corridor", "walkway", "pathway", "route", "edge", "node",
            "lane", "passage", "way", "door", "portal", "entry", "normal", "emergency"
    );

    public ParsedScenarioResult parse(String prompt,
                                      SimulationDtos.SerializedLayout layout,
                                      List<SimulationDtos.LayoutSnapshotSummaryDto> snapshots,
                                      List<SimulationDtos.SimulationRunSummaryDto> runs,
                                      AssistantDtos.WhatIfPreviewRequest overrides) {
        String cleanPrompt = prompt != null ? prompt.trim() : "";
        String lower = cleanPrompt.toLowerCase(Locale.ROOT);

        List<AssistantDtos.TargetOptionDto> availableExits = extractAvailableExits(layout);
        List<AssistantDtos.TargetOptionDto> availableCorridors = extractAvailableCorridors(layout);
        List<String> parsingNotes = new ArrayList<>();

        SimulationScenarioType scenarioType = detectScenarioType(lower, availableExits, availableCorridors);

        boolean hasRushWords = containsAny(lower, "rush", "surge", "spike", "influx", "2.5x", "peak arrival");
        boolean hasStageWords = containsAny(lower, "stage", "main stage", "concert", "front of house", "crowding");
        if (scenarioType == SimulationScenarioType.CLOSED_EXIT && hasRushWords) {
            parsingNotes.add("Detected both exit closure and arrival rush wording; prioritizing Closed Exit disruption. "
                    + "Adjust agent count below or run a companion Arrival Rush comparison if needed.");
        } else if (scenarioType == SimulationScenarioType.BLOCKED_CORRIDOR && hasRushWords) {
            parsingNotes.add("Detected both corridor blockage and arrival rush wording; prioritizing Blocked Corridor disruption. "
                    + "You may adjust agent count below before confirming.");
        } else if (scenarioType == SimulationScenarioType.CLOSED_EXIT && hasStageWords) {
            parsingNotes.add("Detected exit closure near stage context; prioritizing Closed Exit disruption.");
        }

        int durationSec = extractDurationSeconds(cleanPrompt, overrides);
        int agentCount = extractAgentCount(cleanPrompt, layout, overrides);
        long seed = extractSeed(cleanPrompt, overrides);
        int disruptionStartSec = extractDisruptionStartSec(cleanPrompt, durationSec);

        // Match target exit or corridor
        String matchedTargetType = "NONE";
        String matchedTargetLabel = null;
        boolean targetAutoSelected = false;
        String targetExitNodeUid = null;
        Long targetExitInfraId = null;
        String targetEdgeUid = null;
        Long targetEdgeId = null;
        int matchQuality = 2; // 2 = strong, 1 = partial, 0 = fallback

        if (scenarioType == SimulationScenarioType.CLOSED_EXIT) {
            matchedTargetType = "EXIT";
            if (availableExits.isEmpty()) {
                matchQuality = 0;
                parsingNotes.add("No Exit Gate or Emergency Exit pathway nodes exist on the current layout yet.");
            } else {
                TargetMatch match = findBestTarget(cleanPrompt, availableExits);
                if (match != null && match.score() > 0) {
                    targetExitNodeUid = match.option().uid();
                    targetExitInfraId = match.option().entityId();
                    matchedTargetLabel = match.option().label();
                    matchQuality = match.score() >= 100 ? 2 : 1;
                    parsingNotes.add("Matched exit target: " + matchedTargetLabel + ".");
                } else {
                    AssistantDtos.TargetOptionDto fallback = availableExits.get(0);
                    targetExitNodeUid = fallback.uid();
                    targetExitInfraId = fallback.entityId();
                    matchedTargetLabel = fallback.label();
                    targetAutoSelected = true;
                    matchQuality = 0;
                    parsingNotes.add("No specific exit label matched your prompt; pre-selected '"
                            + fallback.label() + "'. Verify or choose another exit before confirming.");
                }
            }
        } else if (scenarioType == SimulationScenarioType.BLOCKED_CORRIDOR) {
            matchedTargetType = "CORRIDOR";
            if (availableCorridors.isEmpty()) {
                matchQuality = 0;
                parsingNotes.add("No active pathway corridors exist on the current layout yet.");
            } else {
                TargetMatch match = findBestTarget(cleanPrompt, availableCorridors);
                if (match != null && match.score() > 0) {
                    targetEdgeUid = match.option().uid();
                    targetEdgeId = match.option().entityId();
                    matchedTargetLabel = match.option().label();
                    matchQuality = match.score() >= 100 ? 2 : 1;
                    parsingNotes.add("Matched corridor target: " + matchedTargetLabel + ".");
                } else {
                    AssistantDtos.TargetOptionDto fallback = availableCorridors.get(0);
                    targetEdgeUid = fallback.uid();
                    targetEdgeId = fallback.entityId();
                    matchedTargetLabel = fallback.label();
                    targetAutoSelected = true;
                    matchQuality = 0;
                    parsingNotes.add("No specific corridor label matched your prompt; pre-selected '"
                            + fallback.label() + "'. Verify or choose another corridor before confirming.");
                }
            }
        } else {
            parsingNotes.add("Parsed scenario: " + scenarioType.getDisplayName() + " (no single exit/corridor lock required).");
        }

        if (scenarioType.requiresTargetEdge() || scenarioType.requiresTargetExit()) {
            parsingNotes.add("Disruption starts at t = " + disruptionStartSec + " s (editable before confirmation).");
        }

        SimulationConfig proposedConfig = SimulationConfig.normalized(
                scenarioType,
                seed,
                agentCount,
                durationSec,
                null,
                null,
                null,
                targetEdgeUid,
                targetEdgeId,
                targetExitNodeUid,
                targetExitInfraId,
                disruptionStartSec
        );

        // Snapshot & Baseline matching
        Long targetSnapshotId = null;
        String targetSnapshotLabel = "Live Current Layout (Auto-Snapshot on Confirm)";
        if (overrides != null && overrides.snapshotId() != null && snapshots != null) {
            for (SimulationDtos.LayoutSnapshotSummaryDto s : snapshots) {
                if (overrides.snapshotId().equals(s.id())) {
                    targetSnapshotId = s.id();
                    targetSnapshotLabel = "V" + s.versionNumber() + " · " + s.label();
                    break;
                }
            }
        } else if (snapshots != null && !snapshots.isEmpty()) {
            SimulationDtos.LayoutSnapshotSummaryDto latest = snapshots.get(0);
            targetSnapshotId = latest.id();
            targetSnapshotLabel = "V" + latest.versionNumber() + " · " + latest.label();
        }

        SimulationDtos.SimulationRunSummaryDto matchedBaseline = resolveBaselineRun(
                runs, overrides != null ? overrides.baselineRunId() : null,
                targetSnapshotId, proposedConfig);

        Long baselineRunId = matchedBaseline != null ? matchedBaseline.id() : null;
        String baselineRunLabel = matchedBaseline != null
                ? matchedBaseline.label()
                : "Auto-run Normal Operations baseline (seed " + proposedConfig.randomSeed() + ")";
        boolean willCreateBaselineRun = matchedBaseline == null;

        String confidence = resolveConfidence(scenarioType, matchQuality, targetAutoSelected);
        boolean layoutReady = layout != null
                && !extractAvailableEntries(layout).isEmpty()
                && !availableExits.isEmpty()
                && !availableCorridors.isEmpty();

        String readinessMsg = layoutReady
                ? "Layout pathways ready for deterministic simulation."
                : "Add at least 1 Entry Gate node, 1 Exit/Emergency Exit node, and 1 active Pathway Edge in Venue Plan -> Pathways before running.";

        String confirmationSummary = buildConfirmationSummary(
                scenarioType, matchedTargetLabel, proposedConfig, targetSnapshotLabel, baselineRunLabel, willCreateBaselineRun);

        return new ParsedScenarioResult(
                layoutReady,
                readinessMsg,
                scenarioType,
                proposedConfig,
                matchedTargetType,
                matchedTargetLabel,
                targetAutoSelected,
                confidence,
                targetSnapshotId,
                targetSnapshotLabel,
                baselineRunId,
                baselineRunLabel,
                willCreateBaselineRun,
                availableExits,
                availableCorridors,
                List.copyOf(parsingNotes),
                confirmationSummary
        );
    }

    private SimulationScenarioType detectScenarioType(String lower,
                                                      List<AssistantDtos.TargetOptionDto> exits,
                                                      List<AssistantDtos.TargetOptionDto> corridors) {
        boolean mentionsClosure = containsAny(lower,
                "close", "closes", "closed", "closing", "shut", "shuts", "shutting",
                "disable", "disabled", "unavailable", "outage", "fail", "fails", "failed", "offline", "lock");
        boolean mentionsBlockage = containsAny(lower,
                "block", "blocks", "blocked", "blocking", "obstruct", "obstructed", "cut off",
                "barricade", "pinch", "close", "closed", "disable", "disabled");
        boolean mentionsExitWord = containsAny(lower, "exit", "egress", "emergency exit", "exit gate");
        boolean mentionsCorridorWord = containsAny(lower,
                "corridor", "walkway", "pathway", "promenade", "bridge", "passage", "lane", "route", "edge");

        TargetMatch exitMatch = exits.isEmpty() ? null : findBestTarget(lower, exits);
        TargetMatch corridorMatch = corridors.isEmpty() ? null : findBestTarget(lower, corridors);

        if (mentionsClosure && (mentionsExitWord || (exitMatch != null && exitMatch.score() >= 100))) {
            return SimulationScenarioType.CLOSED_EXIT;
        }
        if (mentionsBlockage && (mentionsCorridorWord || (corridorMatch != null && corridorMatch.score() >= 100))) {
            return SimulationScenarioType.BLOCKED_CORRIDOR;
        }
        if (mentionsExitWord && containsAny(lower, "outage", "loss", "without", "remove", "drop")) {
            return SimulationScenarioType.CLOSED_EXIT;
        }
        if (mentionsCorridorWord && containsAny(lower, "outage", "loss", "without", "remove", "drop")) {
            return SimulationScenarioType.BLOCKED_CORRIDOR;
        }
        if (containsAny(lower, "stage", "main stage", "pit", "concert", "performer", "stage crowding")) {
            return SimulationScenarioType.STAGE_CROWDING;
        }
        if (containsAny(lower, "rush", "surge", "spike", "peak arrival", "2.5x", "arrival wave", "influx")) {
            return SimulationScenarioType.ARRIVAL_RUSH;
        }
        if (exitMatch != null && exitMatch.score() >= 100 && mentionsBlockage) {
            return SimulationScenarioType.CLOSED_EXIT;
        }
        if (corridorMatch != null && corridorMatch.score() >= 100) {
            return SimulationScenarioType.BLOCKED_CORRIDOR;
        }
        if (mentionsExitWord) {
            return SimulationScenarioType.CLOSED_EXIT;
        }
        if (mentionsCorridorWord) {
            return SimulationScenarioType.BLOCKED_CORRIDOR;
        }
        return SimulationScenarioType.NORMAL_OPERATIONS;
    }

    private int extractDurationSeconds(String prompt, AssistantDtos.WhatIfPreviewRequest overrides) {
        if (overrides != null && overrides.durationSeconds() != null) {
            return overrides.durationSeconds();
        }
        Matcher secMatcher = DURATION_SEC_PATTERN.matcher(prompt);
        if (secMatcher.find()) {
            return Integer.parseInt(secMatcher.group(1));
        }
        Matcher minMatcher = DURATION_MIN_PATTERN.matcher(prompt);
        if (minMatcher.find()) {
            return Integer.parseInt(minMatcher.group(1)) * 60;
        }
        return SimulationConfig.DEFAULT_DURATION_SECONDS;
    }

    private int extractAgentCount(String prompt,
                                  SimulationDtos.SerializedLayout layout,
                                  AssistantDtos.WhatIfPreviewRequest overrides) {
        if (overrides != null && overrides.agentCount() != null) {
            return overrides.agentCount();
        }
        Matcher m = AGENT_PATTERN.matcher(prompt);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        if (layout != null && layout.expectedAttendance() != null && layout.expectedAttendance() > 0) {
            return Math.min(SimulationConfig.MAX_AGENT_COUNT,
                    Math.max(SimulationConfig.MIN_AGENT_COUNT, Math.min(layout.expectedAttendance(), 800)));
        }
        return SimulationConfig.DEFAULT_AGENT_COUNT;
    }

    private long extractSeed(String prompt, AssistantDtos.WhatIfPreviewRequest overrides) {
        if (overrides != null && overrides.randomSeed() != null) {
            return overrides.randomSeed();
        }
        Matcher m = SEED_PATTERN.matcher(prompt);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return SimulationConfig.DEFAULT_RANDOM_SEED;
    }

    private int extractDisruptionStartSec(String prompt, int durationSec) {
        String lower = prompt.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "from t=0", "from t = 0", "from start", "immediately", "at start")) {
            return 0;
        }
        Matcher secM = DISRUPTION_TIME_SEC_PATTERN.matcher(prompt);
        if (secM.find()) {
            return Integer.parseInt(secM.group(1));
        }
        Matcher minM = DISRUPTION_TIME_MIN_PATTERN.matcher(prompt);
        if (minM.find()) {
            return Integer.parseInt(minM.group(1)) * 60;
        }
        if (containsAny(lower, "mid-run", "mid simulation", "halfway", "half way", "midway")) {
            return Math.max(0, durationSec / 3);
        }
        return 0;
    }

    private List<AssistantDtos.TargetOptionDto> extractAvailableEntries(SimulationDtos.SerializedLayout layout) {
        if (layout == null || layout.nodes() == null) {
            return List.of();
        }
        return layout.nodes().stream()
                .filter(n -> n.nodeType() == PathwayNodeType.ENTRY_GATE)
                .map(n -> new AssistantDtos.TargetOptionDto(n.nodeUid(), n.linkedInfraId(), n.label(), "Entry Gate"))
                .toList();
    }

    private List<AssistantDtos.TargetOptionDto> extractAvailableExits(SimulationDtos.SerializedLayout layout) {
        if (layout == null || layout.nodes() == null) {
            return List.of();
        }
        Map<Long, SimulationDtos.SnapElement> elementsById = new HashMap<>();
        if (layout.elements() != null) {
            for (SimulationDtos.SnapElement el : layout.elements()) {
                if (el.id() != null) {
                    elementsById.put(el.id(), el);
                }
            }
        }
        List<AssistantDtos.TargetOptionDto> exits = new ArrayList<>();
        for (SimulationDtos.SnapNode n : layout.nodes()) {
            if (n.nodeType() != PathwayNodeType.EXIT_GATE && n.nodeType() != PathwayNodeType.EMERGENCY_EXIT) {
                continue;
            }
            SimulationDtos.SnapElement el = n.linkedInfraId() != null ? elementsById.get(n.linkedInfraId()) : null;
            String label = (n.label() != null && !n.label().isBlank())
                    ? n.label()
                    : (el != null && el.label() != null ? el.label() : n.nodeType().getDisplayName());
            String detail = n.nodeType().getDisplayName()
                    + (el != null && el.throughputPersonsPerMin() != null
                    ? String.format(Locale.US, " (%.0f p/min)", el.throughputPersonsPerMin())
                    : "");
            exits.add(new AssistantDtos.TargetOptionDto(n.nodeUid(), n.linkedInfraId(), label, detail));
        }
        exits.sort(Comparator.comparing(AssistantDtos.TargetOptionDto::label, String.CASE_INSENSITIVE_ORDER));
        return exits;
    }

    private List<AssistantDtos.TargetOptionDto> extractAvailableCorridors(SimulationDtos.SerializedLayout layout) {
        if (layout == null || layout.edges() == null) {
            return List.of();
        }
        Map<String, String> nodeLabels = new HashMap<>();
        if (layout.nodes() != null) {
            for (SimulationDtos.SnapNode n : layout.nodes()) {
                if (n.nodeUid() != null) {
                    nodeLabels.put(n.nodeUid(), n.label() != null ? n.label() : n.nodeType().getDisplayName());
                }
            }
        }
        List<AssistantDtos.TargetOptionDto> corridors = new ArrayList<>();
        for (SimulationDtos.SnapEdge e : layout.edges()) {
            if (!e.active()) {
                continue;
            }
            String fromLbl = nodeLabels.getOrDefault(e.fromNodeUid(), "Node");
            String toLbl = nodeLabels.getOrDefault(e.toNodeUid(), "Node");
            String label = (e.label() != null && !e.label().isBlank())
                    ? e.label()
                    : fromLbl + " -> " + toLbl;
            String detail = String.format(Locale.US, "%s · %.1f m wide · %.1f m (%s -> %s)",
                    e.networkLayer().getDisplayName(), e.widthM(), e.lengthM(), fromLbl, toLbl);
            corridors.add(new AssistantDtos.TargetOptionDto(e.edgeUid(), e.id(), label, detail));
        }
        corridors.sort(Comparator.comparing(AssistantDtos.TargetOptionDto::label, String.CASE_INSENSITIVE_ORDER));
        return corridors;
    }

    private TargetMatch findBestTarget(String prompt, List<AssistantDtos.TargetOptionDto> options) {
        if (prompt == null || prompt.isBlank() || options.isEmpty()) {
            return null;
        }
        String normPrompt = normalizeText(prompt);
        Set<String> promptTokens = meaningfulTokens(normPrompt);

        TargetMatch best = null;
        for (AssistantDtos.TargetOptionDto option : options) {
            String normLabel = normalizeText(option.label());
            if (normLabel.isEmpty()) {
                continue;
            }
            int score = 0;
            if (normPrompt.contains(normLabel)) {
                score = 200 + normLabel.length();
            } else {
                Set<String> labelTokens = meaningfulTokens(normLabel);
                Set<String> specificLabelTokens = new LinkedHashSet<>();
                for (String tok : labelTokens) {
                    if (!GENERIC_TARGET_TOKENS.contains(tok)) {
                        specificLabelTokens.add(tok);
                    }
                }
                int specificOverlap = countOverlap(promptTokens, specificLabelTokens);
                int labelOverlap = countOverlap(promptTokens, labelTokens);

                if (!specificLabelTokens.isEmpty() && specificOverlap > 0) {
                    if (!labelTokens.isEmpty() && labelOverlap == labelTokens.size()) {
                        score = 150 + labelOverlap * 10;
                    } else {
                        score = 40 * specificOverlap;
                        for (String tok : specificLabelTokens) {
                            if (promptTokens.contains(tok) && (tok.length() == 1 || tok.matches(".*\\d.*"))) {
                                score += 55;
                            }
                        }
                    }
                }
            }

            if (score > 0 && (best == null || score > best.score())) {
                best = new TargetMatch(option, score);
            }
        }
        return best;
    }

    private SimulationDtos.SimulationRunSummaryDto resolveBaselineRun(List<SimulationDtos.SimulationRunSummaryDto> runs,
                                                                      Long requestedBaselineRunId,
                                                                      Long targetSnapshotId,
                                                                      SimulationConfig config) {
        if (runs == null || runs.isEmpty()) {
            return null;
        }
        if (requestedBaselineRunId != null) {
            for (SimulationDtos.SimulationRunSummaryDto r : runs) {
                if (requestedBaselineRunId.equals(r.id())) {
                    return r;
                }
            }
        }
        // Prefer exact parity NORMAL_OPERATIONS run on the same snapshot
        for (SimulationDtos.SimulationRunSummaryDto r : runs) {
            if (r.scenarioType() == SimulationScenarioType.NORMAL_OPERATIONS
                    && (targetSnapshotId == null || targetSnapshotId.equals(r.snapshotId()))
                    && r.randomSeed() == config.randomSeed()
                    && r.agentCount() == config.agentCount()
                    && r.durationSeconds() == config.durationSeconds()) {
                return r;
            }
        }
        return null;
    }

    private String resolveConfidence(SimulationScenarioType scenarioType, int matchQuality, boolean targetAutoSelected) {
        if (!scenarioType.requiresTargetEdge() && !scenarioType.requiresTargetExit()) {
            return scenarioType == SimulationScenarioType.NORMAL_OPERATIONS ? "MEDIUM" : "HIGH";
        }
        if (targetAutoSelected || matchQuality == 0) {
            return "LOW";
        }
        return matchQuality >= 2 ? "HIGH" : "MEDIUM";
    }

    private String buildConfirmationSummary(SimulationScenarioType scenarioType,
                                            String matchedTargetLabel,
                                            SimulationConfig config,
                                            String snapshotLabel,
                                            String baselineRunLabel,
                                            boolean willCreateBaselineRun) {
        String targetPart = matchedTargetLabel != null ? " targeting '" + matchedTargetLabel + "'" : "";
        String disruptionPart = (scenarioType.requiresTargetEdge() || scenarioType.requiresTargetExit())
                ? " (disruption at t = " + config.disruptionStartSec() + " s)"
                : "";
        String baselinePart = willCreateBaselineRun
                ? "A fresh Normal Operations baseline with identical seed/agents/duration will also be executed for side-by-side comparison."
                : "Will compare against baseline run: " + baselineRunLabel + ".";
        return String.format(Locale.US,
                "Ready to simulate %s%s%s on %s with %d agents, %d s duration, random seed %d. %s",
                scenarioType.getDisplayName(), targetPart, disruptionPart,
                snapshotLabel, config.agentCount(), config.durationSeconds(), config.randomSeed(),
                baselinePart);
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private Set<String> meaningfulTokens(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : Arrays.asList(normalized.split("\\s+"))) {
            if (!part.isEmpty() && !STOP_TOKENS.contains(part)) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private int countOverlap(Set<String> a, Set<String> b) {
        int count = 0;
        for (String token : b) {
            if (a.contains(token)) {
                count++;
            }
        }
        return count;
    }

    private record TargetMatch(AssistantDtos.TargetOptionDto option, int score) {
    }

    public record ParsedScenarioResult(
            boolean layoutReadyForSimulation,
            String readinessMessage,
            SimulationScenarioType parsedScenarioType,
            SimulationConfig proposedConfig,
            String matchedTargetType,
            String matchedTargetLabel,
            boolean targetAutoSelected,
            String confidence,
            Long targetSnapshotId,
            String targetSnapshotLabel,
            Long baselineRunId,
            String baselineRunLabel,
            boolean willCreateBaselineRun,
            List<AssistantDtos.TargetOptionDto> availableExits,
            List<AssistantDtos.TargetOptionDto> availableCorridors,
            List<String> parsingNotes,
            String confirmationSummary
    ) {
    }
}
