package com.vyuha.capacity;

import com.vyuha.auth.AppUser;
import com.vyuha.auth.UserRepository;
import com.vyuha.event.Event;
import com.vyuha.event.EventService;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.FunctionalZoneRepository;
import com.vyuha.venue.AreaBreakdownResult;
import com.vyuha.venue.AreaBreakdownService;
import com.vyuha.venue.ExclusionArea;
import com.vyuha.venue.ExclusionAreaRepository;
import com.vyuha.venue.VenuePlan;
import com.vyuha.venue.VenuePlanRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Coordinates Phase 4 Preliminary Capacity Review persistence, assumption citations,
 * and role-separated safety reviewer verification workflows.
 */
@Service
public class CapacityService {

    private final EventService eventService;
    private final VenuePlanRepository venuePlanRepository;
    private final ExclusionAreaRepository exclusionAreaRepository;
    private final FunctionalZoneRepository functionalZoneRepository;
    private final CapacityReviewRepository capacityReviewRepository;
    private final ZoneDensityAssumptionRepository assumptionRepository;
    private final UserRepository userRepository;
    private final AreaBreakdownService areaBreakdownService;
    private final CapacityCalculatorService capacityCalculatorService;

    public CapacityService(EventService eventService,
                           VenuePlanRepository venuePlanRepository,
                           ExclusionAreaRepository exclusionAreaRepository,
                           FunctionalZoneRepository functionalZoneRepository,
                           CapacityReviewRepository capacityReviewRepository,
                           ZoneDensityAssumptionRepository assumptionRepository,
                           UserRepository userRepository,
                           AreaBreakdownService areaBreakdownService,
                           CapacityCalculatorService capacityCalculatorService) {
        this.eventService = eventService;
        this.venuePlanRepository = venuePlanRepository;
        this.exclusionAreaRepository = exclusionAreaRepository;
        this.functionalZoneRepository = functionalZoneRepository;
        this.capacityReviewRepository = capacityReviewRepository;
        this.assumptionRepository = assumptionRepository;
        this.userRepository = userRepository;
        this.areaBreakdownService = areaBreakdownService;
        this.capacityCalculatorService = capacityCalculatorService;
    }

    @Transactional(readOnly = true)
    public CapacityDtos.CapacityPageView getCapacityView(Long eventId,
                                                         boolean canEditAssumptions,
                                                         boolean canVerifyReview) {
        Event event = eventService.getEventOrThrow(eventId);
        CapacityReview review = capacityReviewRepository.findByEventId(eventId).orElse(null);
        return buildPageView(event, review, canEditAssumptions, canVerifyReview);
    }

    @Transactional
    public CapacityDtos.CapacityPageView updateParameters(Long eventId,
                                                          CapacityDtos.CapacityParametersRequest request,
                                                          boolean canEditAssumptions,
                                                          boolean canVerifyReview) {
        Event event = eventService.getEventOrThrow(eventId);
        CapacityReview review = getOrCreateReview(event);

        if (request != null) {
            if (request.entryThroughputPerMin() != null && !Double.isFinite(request.entryThroughputPerMin())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Entry throughput must be a finite number.");
            }
            if (request.exitThroughputPerMin() != null && !Double.isFinite(request.exitThroughputPerMin())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Exit throughput must be a finite number.");
            }
            review.setOfficialVenueLimit(request.officialVenueLimit());
            review.setOfficialLimitSource(trimToNull(request.officialLimitSource()));
            review.setEntryThroughputPerMin(request.entryThroughputPerMin());
            review.setIngressWindowMinutes(request.ingressWindowMinutes());
            review.setExitThroughputPerMin(request.exitThroughputPerMin());
            review.setEgressWindowMinutes(request.egressWindowMinutes());
            review.setHoldingBufferCapacity(request.holdingBufferCapacity() != null
                    ? Math.max(0, request.holdingBufferCapacity())
                    : 0);
            review.setNotes(trimToNull(request.notes()));
        }

        if (review.getReviewStatus() == CapacityReviewStatus.REVIEWER_VERIFIED) {
            review.setReviewStatus(CapacityReviewStatus.PENDING_REVIEW);
            review.setVerifiedBy(null);
            review.setVerifiedAt(null);
        }

        CapacityReview saved = syncCalculatedSummaryAndSave(event, review);
        return buildPageView(event, saved, canEditAssumptions, canVerifyReview);
    }

    @Transactional
    public CapacityDtos.CapacityPageView saveZoneAssumption(Long eventId,
                                                            Long zoneId,
                                                            CapacityDtos.ZoneAssumptionRequest request,
                                                            boolean canEditAssumptions,
                                                            boolean canVerifyReview) {
        Event event = eventService.getEventOrThrow(eventId);
        VenuePlan plan = venuePlanRepository.findByEventId(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Venue plan not found."));
        FunctionalZone zone = functionalZoneRepository.findByIdAndVenuePlanId(zoneId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Functional zone not found."));

        if (!zone.getZoneType().isCapacityBearing()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Density assumptions apply only to gathering and holding zones (AUDIENCE, FOOD_STALLS, QUEUE, SECURITY_SCREENING).");
        }
        if (request == null || request.densityPersonsPerSqm() == null
                || !Double.isFinite(request.densityPersonsPerSqm())
                || request.densityPersonsPerSqm() <= 0.0
                || request.densityPersonsPerSqm() > 10.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Density assumption must be between 0.05 and 10.0 persons/m².");
        }
        if (request.sourceCitation() == null || request.sourceCitation().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Source citation is required for every density assumption.");
        }

        CapacityReview review = getOrCreateReview(event);
        ZoneDensityAssumption assumption = assumptionRepository
                .findByCapacityReviewIdAndFunctionalZoneId(review.getId(), zone.getId())
                .orElseGet(() -> {
                    ZoneDensityAssumption created = new ZoneDensityAssumption();
                    created.setCapacityReview(review);
                    created.setFunctionalZone(zone);
                    return created;
                });

        assumption.setDensityPersonsPerSqm(request.densityPersonsPerSqm());
        assumption.setSourceCitation(request.sourceCitation().trim());
        assumption.setApprovedByName(trimToNull(request.approvedByName()));
        assumption.setApprovedAt(null);
        assumption.setVerifiedBy(null);
        assumption.setReviewStatus(AssumptionReviewStatus.DOCUMENTED);
        assumptionRepository.save(assumption);

        if (review.getReviewStatus() == CapacityReviewStatus.REVIEWER_VERIFIED) {
            review.setReviewStatus(CapacityReviewStatus.PENDING_REVIEW);
            review.setVerifiedBy(null);
            review.setVerifiedAt(null);
        }

        CapacityReview saved = syncCalculatedSummaryAndSave(event, review);
        return buildPageView(event, saved, canEditAssumptions, canVerifyReview);
    }

    @Transactional
    public CapacityDtos.CapacityPageView reviewZoneAssumption(Long eventId,
                                                              Long zoneId,
                                                              Long reviewerUserId,
                                                              CapacityDtos.ReviewerAssumptionAction action,
                                                              boolean canEditAssumptions,
                                                              boolean canVerifyReview) {
        Event event = eventService.getEventOrThrow(eventId);
        AppUser reviewer = userRepository.findById(reviewerUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Reviewer account not found."));
        VenuePlan plan = venuePlanRepository.findByEventId(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Venue plan not found."));
        FunctionalZone zone = functionalZoneRepository.findByIdAndVenuePlanId(zoneId, plan.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Functional zone not found."));

        if (!zone.getZoneType().isCapacityBearing()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only gathering and holding zones carry capacity density assumptions.");
        }

        CapacityReview review = getOrCreateReview(event);
        ZoneDensityAssumption assumption = assumptionRepository
                .findByCapacityReviewIdAndFunctionalZoneId(review.getId(), zone.getId())
                .orElseGet(() -> {
                    ZoneDensityAssumption created = new ZoneDensityAssumption();
                    created.setCapacityReview(review);
                    created.setFunctionalZone(zone);
                    return created;
                });

        AssumptionReviewStatus targetStatus = action.reviewStatus();
        if (targetStatus == AssumptionReviewStatus.REVIEWER_VERIFIED) {
            if (assumption.getDensityPersonsPerSqm() == null || assumption.getDensityPersonsPerSqm() <= 0.0
                    || assumption.getSourceCitation() == null || assumption.getSourceCitation().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Enter a positive density and source citation for " + zone.getZoneCode() + " before verifying.");
            }
            assumption.setReviewStatus(AssumptionReviewStatus.REVIEWER_VERIFIED);
            assumption.setVerifiedBy(reviewer);
            assumption.setApprovedAt(Instant.now());
            if (assumption.getApprovedByName() == null || assumption.getApprovedByName().isBlank()) {
                assumption.setApprovedByName(reviewer.getFullName());
            }
        } else {
            assumption.setReviewStatus(targetStatus);
            assumption.setVerifiedBy(targetStatus == AssumptionReviewStatus.FLAGGED ? reviewer : null);
            assumption.setApprovedAt(null);
            if (review.getReviewStatus() == CapacityReviewStatus.REVIEWER_VERIFIED) {
                review.setReviewStatus(CapacityReviewStatus.PENDING_REVIEW);
                review.setVerifiedBy(null);
                review.setVerifiedAt(null);
            }
        }
        assumption.setReviewerNote(trimToNull(action.reviewerNote()));
        assumptionRepository.save(assumption);

        CapacityReview saved = syncCalculatedSummaryAndSave(event, review);
        return buildPageView(event, saved, canEditAssumptions, canVerifyReview);
    }

    @Transactional
    public CapacityDtos.CapacityPageView reviewOverallCapacity(Long eventId,
                                                               Long reviewerUserId,
                                                               CapacityDtos.ReviewerOverallAction action,
                                                               boolean canEditAssumptions,
                                                               boolean canVerifyReview) {
        Event event = eventService.getEventOrThrow(eventId);
        AppUser reviewer = userRepository.findById(reviewerUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Reviewer account not found."));
        CapacityReview review = getOrCreateReview(event);

        CapacityDtos.CapacityCalculationResult currentCalc = computeCalculation(event, review);
        CapacityReviewStatus targetStatus = action.reviewStatus();

        if (targetStatus == CapacityReviewStatus.REVIEWER_VERIFIED) {
            if (currentCalc.totalCapacityZones() == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Define at least one gathering or holding zone before verifying the capacity review.");
            }
            if (currentCalc.verifiedCapacityZones() < currentCalc.totalCapacityZones()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "All gathering and holding zone density assumptions (" + currentCalc.verifiedCapacityZones()
                                + "/" + currentCalc.totalCapacityZones()
                                + ") must be Reviewer Verified first.");
            }
            review.setReviewStatus(CapacityReviewStatus.REVIEWER_VERIFIED);
            review.setVerifiedBy(reviewer);
            review.setVerifiedAt(Instant.now());
        } else if (targetStatus == CapacityReviewStatus.FLAGGED) {
            review.setReviewStatus(CapacityReviewStatus.FLAGGED);
            review.setVerifiedBy(reviewer);
            review.setVerifiedAt(Instant.now());
        } else {
            review.setReviewStatus(targetStatus);
            review.setVerifiedBy(null);
            review.setVerifiedAt(null);
        }

        review.setReviewerNotes(trimToNull(action.reviewerNotes()));
        CapacityReview saved = syncCalculatedSummaryAndSave(event, review);
        return buildPageView(event, saved, canEditAssumptions, canVerifyReview);
    }

    private CapacityReview syncCalculatedSummaryAndSave(Event event, CapacityReview review) {
        CapacityDtos.CapacityCalculationResult calc = computeCalculation(event, review);
        review.setGoverningConstraint(calc.governingConstraint());
        review.setPreliminaryCapacity(calc.preliminaryCapacity());
        return capacityReviewRepository.save(review);
    }

    private CapacityDtos.CapacityCalculationResult computeCalculation(Event event, CapacityReview review) {
        VenuePlan plan = venuePlanRepository.findByEventId(event.getId()).orElse(null);
        List<ExclusionArea> exclusions = plan != null
                ? exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<FunctionalZone> zones = plan != null
                ? functionalZoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<ZoneDensityAssumption> assumptions = review != null && review.getId() != null
                ? assumptionRepository.findByCapacityReviewIdOrderByIdAsc(review.getId())
                : List.of();

        return capacityCalculatorService.calculate(
                event.getExpectedAttendance(),
                plan != null ? plan.getBoundaryWkt() : null,
                exclusions,
                zones,
                review,
                assumptions
        );
    }

    private CapacityDtos.CapacityPageView buildPageView(Event event,
                                                         CapacityReview review,
                                                         boolean canEditAssumptions,
                                                         boolean canVerifyReview) {
        VenuePlan plan = venuePlanRepository.findByEventId(event.getId()).orElse(null);
        List<ExclusionArea> exclusions = plan != null
                ? exclusionAreaRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<FunctionalZone> zones = plan != null
                ? functionalZoneRepository.findByVenuePlanIdOrderBySortOrderAscIdAsc(plan.getId())
                : List.of();
        List<ZoneDensityAssumption> assumptions = review != null && review.getId() != null
                ? assumptionRepository.findByCapacityReviewIdOrderByIdAsc(review.getId())
                : List.of();

        String boundaryWkt = plan != null ? plan.getBoundaryWkt() : null;
        AreaBreakdownResult areaBreakdown = areaBreakdownService.calculate(boundaryWkt, exclusions);
        CapacityDtos.CapacityCalculationResult calculation = capacityCalculatorService.calculate(
                event.getExpectedAttendance(), boundaryWkt, exclusions, zones, review, assumptions);

        CapacityDtos.CapacityParametersView paramsView = new CapacityDtos.CapacityParametersView(
                review != null ? review.getOfficialVenueLimit() : null,
                review != null ? review.getOfficialLimitSource() : null,
                review != null ? review.getEntryThroughputPerMin() : null,
                review != null ? review.getIngressWindowMinutes() : null,
                review != null ? review.getExitThroughputPerMin() : null,
                review != null ? review.getEgressWindowMinutes() : null,
                review != null ? review.getHoldingBufferCapacity() : 0,
                review != null ? review.getNotes() : null,
                review != null ? review.getReviewerNotes() : null,
                review != null && review.getVerifiedBy() != null ? review.getVerifiedBy().getFullName() : null,
                review != null ? review.getVerifiedAt() : null,
                review != null ? review.getReviewStatus() : CapacityReviewStatus.PENDING_REVIEW
        );

        return new CapacityDtos.CapacityPageView(
                event.getId(),
                event.getName(),
                event.getExpectedAttendance(),
                canEditAssumptions,
                canVerifyReview,
                areaBreakdown,
                paramsView,
                calculation
        );
    }

    private CapacityReview getOrCreateReview(Event event) {
        return capacityReviewRepository.findByEventId(event.getId())
                .orElseGet(() -> {
                    CapacityReview created = new CapacityReview();
                    created.setEvent(event);
                    created.setReviewStatus(CapacityReviewStatus.PENDING_REVIEW);
                    created.setGoverningConstraint(GoverningConstraint.INCOMPLETE);
                    created.setHoldingBufferCapacity(0);
                    return capacityReviewRepository.save(created);
                });
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
