package com.vyuha.capacity;

import com.vyuha.common.GeometryUtil;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.GridDtos;
import com.vyuha.grid.ZoneType;
import com.vyuha.grid.ZoneValidationService;
import com.vyuha.venue.AreaBreakdownResult;
import com.vyuha.venue.AreaBreakdownService;
import com.vyuha.venue.ExclusionArea;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;

/**
 * Pure-Java multi-constraint preliminary capacity engine.
 * Calculates C_space, C_flow, C_holding, and C_official from net usable zone areas,
 * identifies the tightest governing constraint, and enforces citation/review tracking
 * without ever inventing silent "safe" density defaults.
 */
@Service
public class CapacityCalculatorService {

    private final GeometryUtil geometryUtil;
    private final AreaBreakdownService areaBreakdownService;
    private final ZoneValidationService zoneValidationService;

    public CapacityCalculatorService(GeometryUtil geometryUtil,
                                     AreaBreakdownService areaBreakdownService,
                                     ZoneValidationService zoneValidationService) {
        this.geometryUtil = geometryUtil;
        this.areaBreakdownService = areaBreakdownService;
        this.zoneValidationService = zoneValidationService;
    }

    public CapacityDtos.CapacityCalculationResult calculate(Integer expectedAttendance,
                                                            String boundaryWkt,
                                                            List<ExclusionArea> exclusions,
                                                            List<FunctionalZone> zones,
                                                            CapacityReview review,
                                                            List<ZoneDensityAssumption> assumptions) {
        boolean hasBoundary = boundaryWkt != null && !boundaryWkt.isBlank();
        Polygon boundary = hasBoundary ? geometryUtil.parsePolygon(boundaryWkt) : null;
        AreaBreakdownResult breakdown = areaBreakdownService.calculate(boundaryWkt, exclusions);

        List<FunctionalZone> safeZones = zones != null ? zones : List.of();
        GridDtos.ZoneValidationResult zoneValidation = zoneValidationService.validate(boundary, exclusions, safeZones);

        Map<Long, ZoneDensityAssumption> assumptionByZoneId = new LinkedHashMap<>();
        if (assumptions != null) {
            for (ZoneDensityAssumption a : assumptions) {
                if (a != null && a.getFunctionalZone() != null && a.getFunctionalZone().getId() != null) {
                    assumptionByZoneId.put(a.getFunctionalZone().getId(), a);
                }
            }
        }

        List<CapacityDtos.ZoneCapacityRow> rows = new ArrayList<>();
        List<CapacityDtos.CapacityWarning> warnings = new ArrayList<>();

        if (!hasBoundary) {
            warnings.add(new CapacityDtos.CapacityWarning(
                    "BOUNDARY_MISSING",
                    "HIGH",
                    "Outer venue boundary not drawn",
                    "Trace the outer venue boundary in Venue Plan -> Site Plan before calculating zone capacities."
            ));
        }

        int gatheringCapacitySum = 0;
        boolean hasGatheringDensity = false;
        int holdingZoneCapacitySum = 0;
        boolean hasHoldingDensity = false;

        int totalCapacityZones = 0;
        int documentedOrVerifiedZones = 0;
        int verifiedZones = 0;
        int flaggedZones = 0;

        List<String> unconfiguredCapacityZoneCodes = new ArrayList<>();
        List<String> uncitedZoneCodes = new ArrayList<>();

        for (int i = 0; i < safeZones.size(); i++) {
            FunctionalZone zone = safeZones.get(i);
            double clippedArea = i < zoneValidation.perZoneClippedAreasSqm().size()
                    ? zoneValidation.perZoneClippedAreasSqm().get(i)
                    : 0.0;
            double usableArea = i < zoneValidation.perZoneUsableAreasSqm().size()
                    ? zoneValidation.perZoneUsableAreasSqm().get(i)
                    : 0.0;
            boolean isolated = zone.getId() != null
                    ? zoneValidation.isolatedZoneIds().contains(zone.getId())
                    : zone.isIsolated();

            ZoneDensityAssumption assumption = zone.getId() != null ? assumptionByZoneId.get(zone.getId()) : null;
            Double density = assumption != null ? assumption.getDensityPersonsPerSqm() : null;
            String citation = assumption != null ? assumption.getSourceCitation() : null;
            AssumptionReviewStatus rowStatus = assumption != null
                    ? assumption.getReviewStatus()
                    : AssumptionReviewStatus.PENDING_REVIEW;
            boolean hasCitation = citation != null && !citation.isBlank();
            boolean hasPositiveDensity = density != null && Double.isFinite(density) && density > 0.0;

            Integer zoneCap = null;
            if (zone.getZoneType().isCapacityBearing()) {
                totalCapacityZones++;
                if (hasPositiveDensity) {
                    int cap = (int) Math.floor(usableArea * density);
                    zoneCap = Math.max(0, cap);
                    if (zone.getZoneType().getCapacityBucket() == ZoneType.CapacityBucket.GATHERING) {
                        gatheringCapacitySum += zoneCap;
                        hasGatheringDensity = true;
                    } else if (zone.getZoneType().getCapacityBucket() == ZoneType.CapacityBucket.HOLDING) {
                        holdingZoneCapacitySum += zoneCap;
                        hasHoldingDensity = true;
                    }
                } else {
                    unconfiguredCapacityZoneCodes.add(zone.getZoneCode());
                }

                if (hasPositiveDensity && !hasCitation) {
                    uncitedZoneCodes.add(zone.getZoneCode());
                }

                if (rowStatus == AssumptionReviewStatus.FLAGGED) {
                    flaggedZones++;
                } else if (rowStatus == AssumptionReviewStatus.REVIEWER_VERIFIED) {
                    verifiedZones++;
                    documentedOrVerifiedZones++;
                } else if (rowStatus == AssumptionReviewStatus.DOCUMENTED && hasPositiveDensity && hasCitation) {
                    documentedOrVerifiedZones++;
                }
            }

            rows.add(new CapacityDtos.ZoneCapacityRow(
                    zone.getId(),
                    zone.getZoneCode(),
                    zone.getName(),
                    zone.getZoneType(),
                    zone.getZoneType().getDisplayName(),
                    zone.getZoneType().getCapacityBucket(),
                    zone.getZoneType().isCapacityBearing(),
                    zone.getColorHex(),
                    clippedArea,
                    usableArea,
                    isolated,
                    density,
                    zoneCap,
                    citation,
                    assumption != null ? assumption.getApprovedByName() : null,
                    assumption != null ? assumption.getApprovedAt() : null,
                    assumption != null && assumption.getVerifiedBy() != null
                            ? assumption.getVerifiedBy().getFullName()
                            : null,
                    rowStatus,
                    assumption != null ? assumption.getReviewerNote() : null
            ));
        }

        if (totalCapacityZones == 0) {
            warnings.add(new CapacityDtos.CapacityWarning(
                    "NO_CAPACITY_ZONES",
                    "HIGH",
                    "No gathering or holding zones defined",
                    "Assign AUDIENCE, FOOD_STALLS, QUEUE, or SECURITY_SCREENING zones in Venue Plan -> Zones & Grid."
            ));
        }
        if (!unconfiguredCapacityZoneCodes.isEmpty()) {
            warnings.add(new CapacityDtos.CapacityWarning(
                    "MISSING_ZONE_DENSITY",
                    "HIGH",
                    "Explicit density assumption required for zones: " + String.join(", ", unconfiguredCapacityZoneCodes),
                    "Enter an explicit density (persons/m²) and source citation for every gathering and holding zone."
            ));
        }
        if (!uncitedZoneCodes.isEmpty()) {
            warnings.add(new CapacityDtos.CapacityWarning(
                    "MISSING_SOURCE_CITATION",
                    "HIGH",
                    "Density assumption missing source citation: " + String.join(", ", uncitedZoneCodes),
                    "Every density assumption must record its guiding advisory or regulatory source citation."
            ));
        }

        Integer usableSpaceCapacity = hasGatheringDensity ? gatheringCapacitySum : null;

        // C_flow = min(sum(entryRate * T_ingress), sum(exitRate * T_egress))
        Integer entryFlowCapacity = null;
        if (review != null && review.getEntryThroughputPerMin() != null && review.getEntryThroughputPerMin() > 0.0
                && review.getIngressWindowMinutes() != null && review.getIngressWindowMinutes() > 0) {
            entryFlowCapacity = (int) Math.floor(review.getEntryThroughputPerMin() * review.getIngressWindowMinutes());
        }
        Integer exitFlowCapacity = null;
        if (review != null && review.getExitThroughputPerMin() != null && review.getExitThroughputPerMin() > 0.0
                && review.getEgressWindowMinutes() != null && review.getEgressWindowMinutes() > 0) {
            exitFlowCapacity = (int) Math.floor(review.getExitThroughputPerMin() * review.getEgressWindowMinutes());
        }

        Integer flowCapacity = null;
        if (entryFlowCapacity != null && exitFlowCapacity != null) {
            flowCapacity = Math.min(entryFlowCapacity, exitFlowCapacity);
        } else if (entryFlowCapacity != null) {
            flowCapacity = entryFlowCapacity;
            warnings.add(new CapacityDtos.CapacityWarning(
                    "INCOMPLETE_EXIT_FLOW",
                    "LOW",
                    "Exit throughput / egress window not entered",
                    "C_flow currently reflects entry throughput only. Enter exit throughput and target egress duration."
            ));
        } else if (exitFlowCapacity != null) {
            flowCapacity = exitFlowCapacity;
            warnings.add(new CapacityDtos.CapacityWarning(
                    "INCOMPLETE_ENTRY_FLOW",
                    "LOW",
                    "Entry throughput / ingress window not entered",
                    "C_flow currently reflects exit throughput only. Enter entry throughput and target ingress duration."
            ));
        } else {
            warnings.add(new CapacityDtos.CapacityWarning(
                    "FLOW_INPUTS_MISSING",
                    "INFO",
                    "Entry / exit flow ceiling (C_flow) not yet configured",
                    "Enter expected gate throughput rates (persons/min) and ingress/egress window durations."
            ));
        }

        // C_holding = sum(holdingArea * rho) + buffer
        int holdingBuffer = review != null ? Math.max(0, review.getHoldingBufferCapacity()) : 0;
        Integer holdingZoneAreaCapacity = hasHoldingDensity ? holdingZoneCapacitySum : null;
        Integer holdingSpaceCapacity = (hasHoldingDensity || holdingBuffer > 0)
                ? (holdingZoneCapacitySum + holdingBuffer)
                : null;

        // C_official
        Integer officialVenueLimit = (review != null && review.getOfficialVenueLimit() != null && review.getOfficialVenueLimit() > 0)
                ? review.getOfficialVenueLimit()
                : null;
        if (officialVenueLimit == null) {
            warnings.add(new CapacityDtos.CapacityWarning(
                    "OFFICIAL_LIMIT_MISSING",
                    "LOW",
                    "Official / statutory venue permit cap not entered",
                    "Record the official statutory or authority-issued venue limit (C_official) when available."
            ));
        }

        // Tightest Governing Constraint: C_preliminary = min(C_space, C_flow, C_holding, C_official)
        Integer preliminaryCapacity = null;
        GoverningConstraint governing = GoverningConstraint.INCOMPLETE;
        List<GoverningConstraint> tied = new ArrayList<>();

        Map<GoverningConstraint, Integer> candidates = new LinkedHashMap<>();
        if (usableSpaceCapacity != null) candidates.put(GoverningConstraint.USABLE_SPACE, usableSpaceCapacity);
        if (flowCapacity != null) candidates.put(GoverningConstraint.ENTRY_EXIT_FLOW, flowCapacity);
        if (holdingSpaceCapacity != null) candidates.put(GoverningConstraint.HOLDING_SPACE, holdingSpaceCapacity);
        if (officialVenueLimit != null) candidates.put(GoverningConstraint.OFFICIAL_LIMIT, officialVenueLimit);

        if (!candidates.isEmpty()) {
            int minVal = Integer.MAX_VALUE;
            for (Integer val : candidates.values()) {
                if (val < minVal) {
                    minVal = val;
                }
            }
            preliminaryCapacity = minVal;
            for (Map.Entry<GoverningConstraint, Integer> entry : candidates.entrySet()) {
                if (entry.getValue() == minVal) {
                    tied.add(entry.getKey());
                }
            }
            governing = tied.get(0);
        }

        String explanation = buildGoverningExplanation(preliminaryCapacity, governing, tied);

        Integer headroom = null;
        Double utilizationPct = null;
        boolean exceedsCeiling = false;
        if (expectedAttendance != null && expectedAttendance > 0 && preliminaryCapacity != null) {
            headroom = preliminaryCapacity - expectedAttendance;
            utilizationPct = preliminaryCapacity > 0
                    ? (expectedAttendance * 100.0) / preliminaryCapacity
                    : null;
            exceedsCeiling = expectedAttendance > preliminaryCapacity;
            if (exceedsCeiling) {
                warnings.add(new CapacityDtos.CapacityWarning(
                        "EXPECTED_ATTENDANCE_EXCEEDS_CEILING",
                        "CRITICAL",
                        "Expected attendance exceeds preliminary governing ceiling",
                        String.format(Locale.US,
                                "Expected attendance (%d) exceeds %s (%d) by %d persons.",
                                expectedAttendance, governing.getDisplayName(), preliminaryCapacity,
                                expectedAttendance - preliminaryCapacity)
                ));
            }
        }

        // Surface spatial zone validation warnings into capacity review
        for (GridDtos.ZoneFindingDto zf : zoneValidation.findings()) {
            if ("ZONE_OVERLAP".equals(zf.code()) || "ISOLATED_ZONE".equals(zf.code())) {
                warnings.add(new CapacityDtos.CapacityWarning(
                        zf.code(),
                        zf.severity(),
                        zf.title(),
                        zf.detail() + (zf.measuredValue() != null ? " (" + zf.measuredValue() + ")" : "")
                ));
            }
        }

        CapacityReviewStatus effectiveStatus = deriveEffectiveStatus(
                review, totalCapacityZones, documentedOrVerifiedZones, verifiedZones, flaggedZones);

        return new CapacityDtos.CapacityCalculationResult(
                hasBoundary,
                breakdown.grossAreaSqm(),
                breakdown.usableAreaSqm(),
                expectedAttendance,
                usableSpaceCapacity,
                entryFlowCapacity,
                exitFlowCapacity,
                flowCapacity,
                holdingZoneAreaCapacity,
                holdingBuffer,
                holdingSpaceCapacity,
                officialVenueLimit,
                preliminaryCapacity,
                governing,
                List.copyOf(tied),
                explanation,
                headroom,
                utilizationPct,
                exceedsCeiling,
                totalCapacityZones,
                documentedOrVerifiedZones,
                verifiedZones,
                flaggedZones,
                effectiveStatus,
                List.copyOf(rows),
                List.copyOf(warnings)
        );
    }

    private CapacityReviewStatus deriveEffectiveStatus(CapacityReview review,
                                                       int totalCapacityZones,
                                                       int documentedOrVerifiedZones,
                                                       int verifiedZones,
                                                       int flaggedZones) {
        if (flaggedZones > 0 || (review != null && review.getReviewStatus() == CapacityReviewStatus.FLAGGED)) {
            return CapacityReviewStatus.FLAGGED;
        }
        if (review != null && review.getReviewStatus() == CapacityReviewStatus.REVIEWER_VERIFIED
                && totalCapacityZones > 0 && verifiedZones == totalCapacityZones) {
            return CapacityReviewStatus.REVIEWER_VERIFIED;
        }
        if (totalCapacityZones > 0 && documentedOrVerifiedZones == totalCapacityZones) {
            return CapacityReviewStatus.DOCUMENTED;
        }
        return CapacityReviewStatus.PENDING_REVIEW;
    }

    private String buildGoverningExplanation(Integer preliminaryCapacity,
                                             GoverningConstraint governing,
                                             List<GoverningConstraint> tied) {
        if (preliminaryCapacity == null || governing == GoverningConstraint.INCOMPLETE) {
            return "Configure gathering/holding zone density assumptions, flow windows, or an official venue limit to establish a preliminary ceiling.";
        }
        if (tied.size() > 1) {
            String tiedNames = tied.stream().map(GoverningConstraint::getDisplayName).reduce((a, b) -> a + " and " + b).orElse("");
            return String.format(Locale.US,
                    "Tied tightest constraints at %,d persons: %s.",
                    preliminaryCapacity, tiedNames);
        }
        return String.format(Locale.US,
                "%s sets the tightest planning ceiling at %,d persons.",
                governing.getDisplayName(), preliminaryCapacity);
    }
}
