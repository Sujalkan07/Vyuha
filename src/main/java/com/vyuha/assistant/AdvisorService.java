package com.vyuha.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.capacity.CapacityDtos;
import com.vyuha.capacity.CapacityReviewStatus;
import com.vyuha.infrastructure.ConflictType;
import com.vyuha.infrastructure.FindingSeverity;
import com.vyuha.infrastructure.InfrastructureDtos;
import com.vyuha.network.BottleneckFindingType;
import com.vyuha.network.NetworkDtos;
import com.vyuha.simulation.SimulationDtos;
import com.vyuha.venue.AreaBreakdownResult;
import com.vyuha.venue.VenueDtos;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Pure-Java read-only Plain-Language Advisor translating verified Java geometry, capacity,
 * spatial conflict, pedestrian bottleneck, and simulation outputs into actionable guidance
 * with verbatim safety guideline citations.
 */
@Service
public class AdvisorService {

    private static final Set<String> FORBIDDEN_TERMS = Set.of(
            "STAMPEDE-PROOF", "APPROVED BY VYUHA", "CERTIFIED SAFE", "VENUE IS SAFE"
    );

    private final GuidelineSearchService guidelineSearchService;
    private final AssistantProvider assistantProvider;
    private final ObjectMapper objectMapper;

    public AdvisorService(GuidelineSearchService guidelineSearchService,
                          AssistantProvider assistantProvider,
                          ObjectMapper objectMapper) {
        this.guidelineSearchService = guidelineSearchService;
        this.assistantProvider = assistantProvider;
        this.objectMapper = objectMapper;
    }

    public record AdvisorInput(
            Long eventId,
            String eventName,
            Integer expectedAttendance,
            VenueDtos.VenueWorkspaceDto venueWorkspace,
            CapacityDtos.CapacityCalculationResult capacityResult,
            List<InfrastructureDtos.ConflictFindingDto> activeConflicts,
            List<NetworkDtos.BottleneckFindingDto> activeBottlenecks,
            SimulationDtos.SimulationRunSummaryDto latestSimulation,
            String focusArea,
            String prompt
    ) {
    }

    public AssistantDtos.AdvisorResponseDto explain(AdvisorInput input) {
        if (input == null) {
            throw new IllegalArgumentException("AdvisorInput is required.");
        }
        String focus = normalizeFocus(input.focusArea());
        String prompt = input.prompt() != null ? input.prompt().trim() : "";

        VenueDtos.VenueWorkspaceDto venue = input.venueWorkspace();
        AreaBreakdownResult area = venue != null && venue.areaBreakdown() != null
                ? venue.areaBreakdown()
                : AreaBreakdownResult.empty();
        VenueDtos.VenuePlanDto plan = venue != null ? venue.venuePlan() : null;
        CapacityDtos.CapacityCalculationResult cap = input.capacityResult();
        List<InfrastructureDtos.ConflictFindingDto> conflicts = input.activeConflicts() != null
                ? input.activeConflicts() : List.of();
        List<NetworkDtos.BottleneckFindingDto> bottlenecks = input.activeBottlenecks() != null
                ? input.activeBottlenecks() : List.of();
        SimulationDtos.SimulationRunSummaryDto latestSim = input.latestSimulation();

        int criticalConflicts = (int) conflicts.stream()
                .filter(c -> c.severity() == FindingSeverity.CRITICAL).count();
        int criticalBottlenecks = (int) bottlenecks.stream()
                .filter(b -> b.severity() == FindingSeverity.CRITICAL).count();

        AssistantDtos.VerifiedMetricsSnapshot snapshot = new AssistantDtos.VerifiedMetricsSnapshot(
                area.hasBoundary(),
                area.grossAreaSqm(),
                area.usableAreaSqm(),
                area.excludedTotalAreaSqm(),
                plan != null && plan.scaleVerified(),
                plan != null && plan.distortionWarning(),
                input.expectedAttendance(),
                cap != null ? cap.preliminaryCapacity() : null,
                cap != null && cap.governingConstraint() != null ? cap.governingConstraint().getDisplayName() : null,
                cap != null && cap.effectiveReviewStatus() != null ? cap.effectiveReviewStatus().getDisplayName() : null,
                conflicts.size(),
                criticalConflicts,
                bottlenecks.size(),
                criticalBottlenecks,
                latestSim != null ? latestSim.id() : null,
                latestSim != null ? latestSim.label() : null,
                latestSim != null && latestSim.metrics() != null ? latestSim.metrics().peakLocalDensityPsm() : null,
                latestSim != null && latestSim.metrics() != null ? latestSim.metrics().peakQueueLength() : null,
                latestSim != null && latestSim.metrics() != null ? latestSim.metrics().unservedBlockedAgentCount() : null
        );

        List<CandidateAdvice> candidates = new ArrayList<>();
        Long eventId = input.eventId();

        // 1. Site Plan & Scale Calibration checks
        addSitePlanAndCalibrationAdvice(candidates, eventId, venue, area, plan);

        // 2. Preliminary Capacity Review checks
        addCapacityAdvice(candidates, eventId, cap, input.expectedAttendance());

        // 3. Spatial Conflict checks (all 6 ConflictType variants)
        for (InfrastructureDtos.ConflictFindingDto cf : conflicts) {
            candidates.add(buildConflictAdvice(eventId, cf));
        }

        // 4. Pedestrian Bottleneck checks (all 8 BottleneckFindingType variants)
        for (NetworkDtos.BottleneckFindingDto bf : bottlenecks) {
            candidates.add(buildBottleneckAdvice(eventId, bf));
        }

        // 5. Latest Simulation Run diagnostics
        if (latestSim != null && latestSim.metrics() != null) {
            addSimulationAdvice(candidates, eventId, latestSim);
        }

        // Filter by requested focus area
        List<CandidateAdvice> filtered = candidates.stream()
                .filter(c -> matchesFocus(c.item().sourceModule(), focus))
                .sorted(Comparator
                        .comparingInt((CandidateAdvice c) -> severityRank(c.item().severity()))
                        .thenComparingInt(c -> -promptRelevanceScore(c.item(), prompt))
                        .thenComparing(c -> c.item().sourceModule())
                        .thenComparing(c -> c.item().title()))
                .toList();

        List<AssistantDtos.AdviceItemDto> recommendations = filtered.stream()
                .map(CandidateAdvice::item)
                .toList();

        // Collect unique top guideline citations across recommendations (or prompt query if none)
        Map<String, GuidelineDocument.GuidelineHit> collectedCitations = new LinkedHashMap<>();
        for (AssistantDtos.AdviceItemDto rec : recommendations) {
            if (rec.citations() != null) {
                for (GuidelineDocument.GuidelineHit hit : rec.citations()) {
                    collectedCitations.putIfAbsent(hit.sectionId(), hit);
                    if (collectedCitations.size() >= 6) {
                        break;
                    }
                }
            }
            if (collectedCitations.size() >= 6) {
                break;
            }
        }
        if (collectedCitations.isEmpty() && !prompt.isBlank()) {
            for (GuidelineDocument.GuidelineHit hit : guidelineSearchService.search(prompt, null, 3).hits()) {
                collectedCitations.putIfAbsent(hit.sectionId(), hit);
            }
        }
        if (collectedCitations.isEmpty()) {
            for (GuidelineDocument.GuidelineHit hit : guidelineSearchService.findCitationsForCode(
                    "AREA_BREAKDOWN", "usable space egress capacity review", 3)) {
                collectedCitations.putIfAbsent(hit.sectionId(), hit);
            }
        }

        String deterministicSummary = buildExecutiveSummary(
                input.eventName(), snapshot, recommendations, focus, prompt);
        String factsJson = serializeFactsSafely(snapshot);
        String finalSummary = assistantProvider.polishSummary(
                "Summarize these verified Java venue-planning findings concisely in plain English.",
                factsJson,
                deterministicSummary
        );
        if (containsForbiddenCertification(finalSummary)) {
            finalSummary = deterministicSummary;
        }

        return new AssistantDtos.AdvisorResponseDto(
                AssistantConstants.ATTRIBUTION_TAG,
                AssistantConstants.READ_ONLY_NOTICE,
                assistantProvider.providerName(),
                eventId,
                input.eventName(),
                focus,
                prompt,
                finalSummary,
                snapshot,
                recommendations,
                List.copyOf(collectedCitations.values()),
                null,
                Instant.now()
        );
    }

    private void addSitePlanAndCalibrationAdvice(List<CandidateAdvice> out,
                                                 Long eventId,
                                                 VenueDtos.VenueWorkspaceDto venue,
                                                 AreaBreakdownResult area,
                                                 VenueDtos.VenuePlanDto plan) {
        String siteUrl = "/events/" + eventId + "/venue?mode=site";
        if (!area.hasBoundary()) {
            out.add(new CandidateAdvice(new AssistantDtos.AdviceItemDto(
                    "SITE-NO-BOUNDARY",
                    "SITE_PLAN",
                    "HIGH",
                    "Outer Venue Boundary Not Drawn",
                    "Gross Area: 0.00 m² · Usable Area: 0.00 m²",
                    "No calibrated outer venue perimeter has been traced yet, so net usable space, grid clipping, and out-of-bounds checks cannot evaluate site limits.",
                    List.of(
                            "Open Venue Plan -> 1. Site Plan and trace the outer venue perimeter (or click 'Use Map Perimeter' on a built-in vector map).",
                            "Draw any non-standing buildings, stages, utility compounds, or reserved corridors as exclusions."
                    ),
                    null, null, siteUrl,
                    guidelineSearchService.findCitationsForCode("AREA_BREAKDOWN", "usable gathering space exclusions", 1)
            )));
            return;
        }

        if (plan != null && plan.hasBlueprint() && !plan.scaleVerified()) {
            out.add(new CandidateAdvice(new AssistantDtos.AdviceItemDto(
                    "SITE-CAL-UNVERIFIED",
                    "SITE_PLAN",
                    "HIGH",
                    "Uploaded Blueprint Scale Not Verified",
                    String.format(Locale.US, "Gross Area: %.2f m² · Scale Verified: No", area.grossAreaSqm()),
                    "An uploaded blueprint image is active without a completed two-step scale calibration check, so metric areas and corridor widths rely on unverified scale.",
                    List.of(
                            "Open Venue Plan -> 1. Site Plan -> Blueprint & Scale Calibration.",
                            "Draw both the Primary Calibration Line and a perpendicular Cross-Axis Verification Line with known real-world distances in meters."
                    ),
                    null, null, siteUrl,
                    guidelineSearchService.findCitationsForCode("CALIBRATION_UNVERIFIED", "scale verification mismatch", 1)
            )));
        }

        if (plan != null && plan.distortionWarning()) {
            Double mismatchPct = plan.calibration() != null ? plan.calibration().mismatchPercent() : null;
            String mismatchStr = mismatchPct != null
                    ? String.format(Locale.US, "Cross-axis mismatch: %.2f%% (> 5.00%% threshold)", mismatchPct)
                    : "Cross-axis mismatch exceeds 5.00% threshold";
            out.add(new CandidateAdvice(new AssistantDtos.AdviceItemDto(
                    "SITE-CAL-DISTORTION",
                    "SITE_PLAN",
                    "CRITICAL",
                    "Blueprint Perspective / Scale Distortion Warning",
                    mismatchStr,
                    "The verification measurement diverges by more than 5% from the primary scale ratio, indicating an oblique aerial angle or uneven scan distortion.",
                    List.of(
                            "Replace the oblique photo with a top-down orthographic blueprint or re-measure both perpendicular calibration lines.",
                            "Do not rely on distorted pixel-to-meter scaling for narrow corridor or ambulance-lane clearance verification."
                    ),
                    null, null, siteUrl,
                    guidelineSearchService.findCitationsForCode("CALIBRATION_DISTORTION", "perspective distortion 5 percent", 1)
            )));
        }

        if (venue != null && venue.findings() != null) {
            for (VenueDtos.VenueCheckFinding vf : venue.findings()) {
                if ("HIGH".equalsIgnoreCase(vf.severity()) || "CRITICAL".equalsIgnoreCase(vf.severity())) {
                    if ("OUTSIDE_EXCLUSIONS".equals(vf.code())) {
                        out.add(new CandidateAdvice(new AssistantDtos.AdviceItemDto(
                                "SITE-" + vf.code(),
                                "SITE_PLAN",
                                vf.severity().toUpperCase(Locale.ROOT),
                                vf.title(),
                                vf.measuredValue() != null ? vf.measuredValue() : vf.detail(),
                                vf.detail(),
                                List.of("Edit or trim the exclusion polygon inside Venue Plan -> 1. Site Plan so it sits cleanly inside the venue perimeter."),
                                vf.locateX(), vf.locateY(), siteUrl,
                                guidelineSearchService.findCitationsForCode("AREA_BREAKDOWN", "usable area exclusion boundary", 1)
                        )));
                    }
                }
            }
        }
    }

    private void addCapacityAdvice(List<CandidateAdvice> out,
                                   Long eventId,
                                   CapacityDtos.CapacityCalculationResult cap,
                                   Integer expectedAttendance) {
        if (cap == null) {
            return;
        }
        String capUrl = "/events/" + eventId + "/capacity";
        if (cap.exceedsPreliminaryCeiling() && cap.preliminaryCapacity() != null && expectedAttendance != null) {
            String constraintName = cap.governingConstraint() != null
                    ? cap.governingConstraint().getDisplayName() : "Tightest Constraint";
            out.add(new CandidateAdvice(new AssistantDtos.AdviceItemDto(
                    "CAP-EXCEEDS",
                    "CAPACITY",
                    "CRITICAL",
                    "Expected Attendance Exceeds Preliminary Capacity Ceiling",
                    String.format(Locale.US,
                            "Expected: %d persons vs. Preliminary Ceiling: %d persons (Governing: %s; Space=%s, Flow=%s, Holding=%s, Official=%s)",
                            expectedAttendance,
                            cap.preliminaryCapacity(),
                            constraintName,
                            formatIntOpt(cap.usableSpaceCapacity()),
                            formatIntOpt(cap.flowCapacity()),
                            formatIntOpt(cap.holdingSpaceCapacity()),
                            formatIntOpt(cap.officialVenueLimit())),
                    String.format(Locale.US,
                            "Expected attendance (%d) exceeds the preliminary governing limit (%d) driven by %s. %s",
                            expectedAttendance,
                            cap.preliminaryCapacity(),
                            constraintName,
                            cap.governingExplanation() != null ? cap.governingExplanation() : ""),
                    List.of(
                            "Either reduce planned attendance/ticket cap below " + cap.preliminaryCapacity()
                                    + " persons or expand the governing bottleneck (" + constraintName + ").",
                            "Review ingress/egress gate rates, holding zone area, and zone density citations with a Safety Reviewer."
                    ),
                    null, null, capUrl,
                    guidelineSearchService.findCitationsForCode("CAPACITY_GOVERNING", "governing constraint preliminary capacity", 2)
            )));
        }

        if (cap.totalCapacityZones() > 0 && cap.documentedCapacityZones() < cap.totalCapacityZones()) {
            int missing = cap.totalCapacityZones() - cap.documentedCapacityZones();
            out.add(new CandidateAdvice(new AssistantDtos.AdviceItemDto(
                    "CAP-MISSING-CITATIONS",
                    "CAPACITY",
                    "HIGH",
                    "Zone Density Assumptions Missing Source Citations",
                    String.format(Locale.US, "%d of %d gathering/holding zones documented",
                            cap.documentedCapacityZones(), cap.totalCapacityZones()),
                    missing + " capacity-bearing zone(s) do not yet have an explicit density assumption and source citation entered.",
                    List.of(
                            "Open Capacity & Staff -> Capacity Review and enter an explicit persons/m² value and source citation for every gathering or queue zone.",
                            "Submit assumptions for Safety Reviewer verification."
                    ),
                    null, null, capUrl,
                    guidelineSearchService.findCitationsForCode("CAPACITY_SPACE", "density assumption citation professional review", 1)
            )));
        }

        if (cap.effectiveReviewStatus() == CapacityReviewStatus.PENDING_REVIEW
                || cap.effectiveReviewStatus() == CapacityReviewStatus.DOCUMENTED
                || cap.effectiveReviewStatus() == CapacityReviewStatus.FLAGGED) {
            out.add(new CandidateAdvice(new AssistantDtos.AdviceItemDto(
                    "CAP-REVIEW-STATUS",
                    "CAPACITY",
                    cap.effectiveReviewStatus() == CapacityReviewStatus.FLAGGED ? "HIGH" : "LOW",
                    "Capacity Review Status: " + cap.effectiveReviewStatus().getDisplayName(),
                    String.format(Locale.US, "Verified zones: %d/%d · Flagged: %d",
                            cap.verifiedCapacityZones(), cap.totalCapacityZones(), cap.flaggedCapacityZones()),
                    "Preliminary capacity limits require documented human Safety Reviewer sign-off before event readiness.",
                    List.of(
                            "Have a SAFETY_REVIEWER verify all zone density assumptions and record sign-off in Capacity & Staff."
                    ),
                    null, null, capUrl,
                    guidelineSearchService.findCitationsForCode("CAPACITY_GOVERNING", "professional review capacity", 1)
            )));
        }
    }

    private CandidateAdvice buildConflictAdvice(Long eventId, InfrastructureDtos.ConflictFindingDto cf) {
        String infraUrl = "/events/" + eventId + "/venue?mode=infrastructure";
        ConflictType type = cf.conflictType();
        String explanation;
        List<String> steps;

        if (type == ConflictType.EMERGENCY_LANE_INTRUSION) {
            explanation = cf.description() + " Any solid structure inside an emergency vehicle lane prevents ambulances or fire tenders from maintaining continuous 3.5 m access.";
            steps = List.of(
                    "Open Venue Plan -> 3. Infrastructure and click Locate on this conflict"
                            + formatCoordsSuffix(cf.locationX(), cf.locationY()) + ".",
                    "Move or resize the intruding fixture out of the emergency corridor/ambulance lane so full >= 3.50 m clear width is restored.",
                    "Re-run conflict checks to confirm the finding clears automatically."
            );
        } else if (type == ConflictType.EXIT_OBSTRUCTION) {
            explanation = cf.description() + " Obstacles inside an exit discharge apron create dangerous back-pressure and trip hazards during rapid egress.";
            steps = List.of(
                    "Open Venue Plan -> 3. Infrastructure and relocate the obstructing structure outside the exit's clearance apron (>= 3.0 m buffer).",
                    "Keep sightlines and outward fan-out space unobstructed on both sides of the exit portal."
            );
        } else if (type == ConflictType.OVERLAPPING_RIGID_STRUCTURES) {
            explanation = cf.description() + " Overlapping rigid structures reduce effective walkable circulation and create pinch traps.";
            steps = List.of(
                    "Separate the overlapping footprints in Venue Plan -> 3. Infrastructure so rigid structures do not intersect.",
                    "If an element is an intentional boundary barricade, verify its placement or mark linear alignment explicitly."
            );
        } else if (type == ConflictType.OUT_OF_BOUNDS) {
            explanation = cf.description() + " Infrastructure outside the calibrated venue boundary falls outside verified usable-space and access checks.";
            steps = List.of(
                    "Move the element inside the traced outer boundary polygon in Venue Plan -> 3. Infrastructure, or expand the boundary if the parcel is valid."
            );
        } else if (type == ConflictType.UNREACHABLE_SERVICE_POST) {
            explanation = cf.description() + " Medical posts and control rooms must stay directly connected to walkable public zones and emergency lanes.";
            steps = List.of(
                    "Reposition the Medical Post or Control Room within 2.0 m of a walkable zone or emergency corridor.",
                    "Verify a continuous emergency vehicle route connects it to the perimeter."
            );
        } else {
            explanation = cf.description() + " Water points placed in narrow choke corridors or next to stage power utilities increase slip and congestion risks.";
            steps = List.of(
                    "Move the Water Point out of corridors narrower than 3.0 m and away from stage rear utility footprints into an open service pocket."
            );
        }

        List<GuidelineDocument.GuidelineHit> citations = guidelineSearchService.findCitationsForCode(
                type.name(), cf.title() + " " + type.getDisplayName(), 2);

        return new CandidateAdvice(new AssistantDtos.AdviceItemDto(
                "CONF-" + (cf.id() != null ? cf.id() : cf.findingKey()),
                "CONFLICTS",
                cf.severity().name(),
                cf.title(),
                cf.measuredValue() != null && !cf.measuredValue().isBlank()
                        ? cf.measuredValue()
                        : type.getDisplayName(),
                explanation,
                steps,
                cf.locationX(),
                cf.locationY(),
                infraUrl,
                citations
        ));
    }

    private CandidateAdvice buildBottleneckAdvice(Long eventId, NetworkDtos.BottleneckFindingDto bf) {
        String pathwaysUrl = "/events/" + eventId + "/venue?mode=pathways";
        BottleneckFindingType type = bf.findingType();
        String explanation;
        List<String> steps;

        switch (type) {
            case DISCONNECTED_ZONE -> {
                explanation = bf.description() + " Attendees in an isolated zone cannot reliably reach entry, normal exit, or emergency exit routes.";
                steps = List.of(
                        "Open Venue Plan -> 4. Pathways and connect the zone hub to at least one Entry Gate, one Normal Exit, and one Emergency Exit.",
                        "Confirm edge directionality permits both ingress and egress."
                );
            }
            case ISOLATED_EXIT -> {
                explanation = bf.description() + " An unlinked exit contributes zero usable egress capacity during circulation or evacuation.";
                steps = List.of(
                        "Connect the isolated Exit / Emergency Exit node to the main walkable circulation graph in Venue Plan -> 4. Pathways."
                );
            }
            case DEAD_END_CORRIDOR -> {
                explanation = bf.description() + " Long dead-end stems (> 10 m) trap crowds attempting to turn back under surge pressure.";
                steps = List.of(
                        "Connect the dead-end tip to a looping cross-walkway or an alternate exit in Venue Plan -> 4. Pathways, or shorten the stem below 10 m."
                );
            }
            case NARROW_WALKWAY -> {
                explanation = bf.description() + " Narrow passages throttle pedestrian flow and rapidly form upstream queues.";
                steps = List.of(
                        "Widen the corridor edge above 2.4 m for general pedestrian circulation (or >= 3.5 m for emergency access lanes) in Venue Plan -> 4. Pathways.",
                        "If widening is physically impossible, add a parallel relief walkway or restrict to low-volume one-way flow."
                );
            }
            case HIGH_CONVERGENCE_MERGE -> {
                explanation = bf.description() + " When multiple wide walkways funnel into a narrower downstream segment (< 75% of combined incoming width), compression builds sharply at the junction.";
                steps = List.of(
                        "Widen the downstream corridor so it meets at least 75% of combined incoming widths, or split one incoming branch toward an alternate exit."
                );
            }
            case CONTRA_FLOW_CONFLICT -> {
                explanation = bf.description() + " Opposing entry and exit streams colliding in the same path or narrow bidirectional corridor cause severe friction and deadlock.";
                steps = List.of(
                        "Separate Normal Entry and Normal Exit layers into distinct parallel ONE_WAY corridors or widen shared bidirectional corridors above 4.0 m."
                );
            }
            case CROSSING_QUEUE_CONFLICT -> {
                explanation = bf.description() + " Circulation edges cutting across waiting queue zones disrupt queuing discipline and create cross-stream collisions.";
                steps = List.of(
                        "Re-route the pedestrian pathway edge around the perimeter of the QUEUE zone instead of crossing through it."
                );
            }
            case ENTRY_GATE_OVERLOAD -> {
                explanation = bf.description() + " Expected peak arrival demand exceeds connected entry gate processing throughput, creating ingress spillback.";
                steps = List.of(
                        "Add or widen entry gate lanes, increase checkpoint staffing, or stagger arrival waves in Capacity & Staff -> Arrival Waves."
                );
            }
            default -> {
                explanation = bf.description();
                steps = List.of("Inspect the flagged node or corridor in Venue Plan -> 4. Pathways.");
            }
        }

        String measured = bf.measuredValue() != null ? bf.measuredValue() : "";
        if (bf.thresholdNote() != null && !bf.thresholdNote().isBlank()) {
            measured = measured.isBlank() ? bf.thresholdNote() : measured + " (" + bf.thresholdNote() + ")";
        }

        List<GuidelineDocument.GuidelineHit> citations = guidelineSearchService.findCitationsForCode(
                type.name(), bf.title() + " " + type.getDisplayName(), 2);

        return new CandidateAdvice(new AssistantDtos.AdviceItemDto(
                "BOTT-" + (bf.id() != null ? bf.id() : bf.findingKey()),
                "BOTTLENECKS",
                bf.severity().name(),
                bf.title(),
                measured,
                explanation,
                steps,
                bf.locationX(),
                bf.locationY(),
                pathwaysUrl,
                citations
        ));
    }

    private void addSimulationAdvice(List<CandidateAdvice> out,
                                     Long eventId,
                                     SimulationDtos.SimulationRunSummaryDto run) {
        SimulationDtos.SimulationMetricsSummary m = run.metrics();
        String simUrl = "/events/" + eventId + "/simulation?runId=" + run.id();
        double threshold = m.highDensityThresholdPsm() > 0 ? m.highDensityThresholdPsm() : 2.0;

        String severity;
        if (m.unservedBlockedAgentCount() > 0 || m.peakLocalDensityPsm() >= threshold * 1.4) {
            severity = "CRITICAL";
        } else if (m.peakLocalDensityPsm() >= threshold || m.peakQueueLength() >= 25) {
            severity = "HIGH";
        } else {
            severity = "INFO";
        }

        String hotspotText = (m.hotspots() != null && !m.hotspots().isEmpty())
                ? " Tightest hotspot: " + m.hotspots().get(0).label() + String.format(Locale.US,
                " (peak %.2f p/m², congested %.1f s, effective width %.1f/%.1f m).",
                m.hotspots().get(0).peakDensityPsm(),
                m.hotspots().get(0).congestedDurationSec(),
                m.hotspots().get(0).effectiveWidthM(),
                m.hotspots().get(0).nominalWidthM())
                : "";

        String explanation = String.format(Locale.US,
                "Latest simplified planning simulation '%s' (%s, seed %d, %d agents) recorded peak local density %.2f p/m², "
                + "peak queue %d persons (max wait %.1f s), P95 clearance %.1f s, and %d unserved/blocked agents.%s",
                run.label(), run.scenarioDisplayName(), run.randomSeed(), run.agentCount(),
                m.peakLocalDensityPsm(), m.peakQueueLength(), m.maxQueueWaitTimeSec(),
                m.p95TravelClearanceTimeSec(), m.unservedBlockedAgentCount(), hotspotText);

        List<String> steps = new ArrayList<>();
        if (m.unservedBlockedAgentCount() > 0) {
            steps.add("Investigate blocked or unserved agents (" + m.unservedBlockedAgentCount()
                    + ") by checking disconnected corridors, closed exits, or pinched edges in Simulation replay.");
        }
        if (m.peakLocalDensityPsm() >= threshold) {
            steps.add(String.format(Locale.US,
                    "Peak simulated density (%.2f p/m²) reached or exceeded %.2f p/m² threshold; widen hotspot corridors or add parallel egress routes and re-compare with the same seed.",
                    m.peakLocalDensityPsm(), threshold));
        }
        if (m.peakQueueLength() > 0) {
            steps.add("Test widening entry/checkpoint capacity or staggering arrival waves to shorten peak queue length.");
        }
        steps.add("Note: Simulation metrics reflect simplified planning comparisons only, not exact real-world predictions.");

        out.add(new CandidateAdvice(new AssistantDtos.AdviceItemDto(
                "SIM-" + run.id(),
                "SIMULATION",
                severity,
                "Latest Simulation Diagnostics: " + run.label(),
                String.format(Locale.US,
                        "Peak %.2f p/m² · Peak Queue %d · Congested %.1f s · Blocked/Unserved %d",
                        m.peakLocalDensityPsm(), m.peakQueueLength(),
                        m.bottleneckCongestionDurationSec(), m.unservedBlockedAgentCount()),
                explanation,
                steps,
                m.peakDensityLocationX(),
                m.peakDensityLocationY(),
                simUrl,
                guidelineSearchService.findCitationsForCode("SIMULATION_INTERPRETATION", "simulation comparison density", 1)
        )));
    }

    private String buildExecutiveSummary(String eventName,
                                         AssistantDtos.VerifiedMetricsSnapshot metrics,
                                         List<AssistantDtos.AdviceItemDto> recommendations,
                                         String focusArea,
                                         String prompt) {
        StringBuilder sb = new StringBuilder();
        if (recommendations.isEmpty()) {
            sb.append(String.format(Locale.US,
                    "For '%s' (Usable Area: %.2f m², Expected Attendance: %s): No automated layout warnings were detected for the current saved data%s; human professional safety review and statutory approvals remain required.",
                    eventName,
                    metrics.usableAreaSqm(),
                    metrics.expectedAttendance() != null ? metrics.expectedAttendance() + " persons" : "Unspecified",
                    "ALL".equals(focusArea) ? "" : " in focus '" + focusArea + "'"));
            return sb.toString();
        }

        long criticalCount = recommendations.stream().filter(r -> "CRITICAL".equals(r.severity())).count();
        long highCount = recommendations.stream().filter(r -> "HIGH".equals(r.severity())).count();

        sb.append(String.format(Locale.US,
                "Planning summary for '%s': %d prioritized item(s) reviewed (%d CRITICAL, %d HIGH). ",
                eventName, recommendations.size(), criticalCount, highCount));

        if (metrics.hasBoundary()) {
            sb.append(String.format(Locale.US,
                    "Verified site space: %.2f m² gross, %.2f m² net usable gathering area. ",
                    metrics.grossAreaSqm(), metrics.usableAreaSqm()));
        }
        if (metrics.preliminaryCapacity() != null) {
            sb.append(String.format(Locale.US,
                    "Preliminary capacity ceiling: %d persons (%s). ",
                    metrics.preliminaryCapacity(),
                    metrics.governingConstraint() != null ? metrics.governingConstraint() : "Calculated"));
        }
        if (metrics.activeConflictCount() > 0 || metrics.activeBottleneckCount() > 0) {
            sb.append(String.format(Locale.US,
                    "Active layout findings: %d spatial conflict(s) and %d network bottleneck(s). ",
                    metrics.activeConflictCount(), metrics.activeBottleneckCount()));
        }
        if (metrics.latestPeakDensityPsm() != null) {
            sb.append(String.format(Locale.US,
                    "Latest simulation peak density: %.2f p/m². ",
                    metrics.latestPeakDensityPsm()));
        }
        if (prompt != null && !prompt.isBlank()) {
            sb.append("Results are ranked with relevance to your question. ");
        }
        sb.append("All recommendations require manual layout edits by an organizer and formal safety reviewer verification.");
        return sb.toString();
    }

    private boolean matchesFocus(String sourceModule, String focusArea) {
        if ("ALL".equals(focusArea)) {
            return true;
        }
        return switch (focusArea) {
            case "CONFLICTS" -> "CONFLICTS".equals(sourceModule);
            case "BOTTLENECKS" -> "BOTTLENECKS".equals(sourceModule);
            case "CAPACITY" -> "CAPACITY".equals(sourceModule) || "SITE_PLAN".equals(sourceModule);
            case "SIMULATION" -> "SIMULATION".equals(sourceModule);
            default -> true;
        };
    }

    private String normalizeFocus(String focusArea) {
        if (focusArea == null || focusArea.isBlank()) {
            return "ALL";
        }
        String upper = focusArea.trim().toUpperCase(Locale.ROOT);
        if (Set.of("ALL", "CONFLICTS", "BOTTLENECKS", "CAPACITY", "SIMULATION").contains(upper)) {
            return upper;
        }
        return "ALL";
    }

    private int severityRank(String severity) {
        if (severity == null) {
            return 4;
        }
        return switch (severity.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "LOW" -> 2;
            default -> 3;
        };
    }

    private int promptRelevanceScore(AssistantDtos.AdviceItemDto item, String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return 0;
        }
        List<String> tokens = GuidelineSearchService.tokenize(prompt);
        if (tokens.isEmpty()) {
            return 0;
        }
        String text = (item.title() + " " + item.measuredEvidence() + " " + item.plainLanguageExplanation())
                .toLowerCase(Locale.ROOT);
        int score = 0;
        for (String tok : tokens) {
            if (text.contains(tok)) {
                score++;
            }
        }
        return score;
    }

    private boolean containsForbiddenCertification(String text) {
        if (text == null) {
            return false;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        for (String term : FORBIDDEN_TERMS) {
            if (upper.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String formatCoordsSuffix(Double x, Double y) {
        if (x == null || y == null) {
            return "";
        }
        return String.format(Locale.US, " near (%.1f m, %.1f m)", x, y);
    }

    private String formatIntOpt(Integer v) {
        return v != null ? v.toString() : "N/A";
    }

    private String serializeFactsSafely(AssistantDtos.VerifiedMetricsSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private record CandidateAdvice(AssistantDtos.AdviceItemDto item) {
    }
}
