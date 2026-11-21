package com.vyuha.operations;

import com.vyuha.capacity.CapacityDtos;
import com.vyuha.capacity.CapacityReview;
import com.vyuha.common.GeometryUtil;
import com.vyuha.grid.ZoneType;
import com.vyuha.infrastructure.InfrastructureElement;
import com.vyuha.infrastructure.InfrastructureType;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Pure-Java interval-by-interval arrival wave queue accumulation and holding-space overflow engine.
 * Implements Q_t = max(0, Q_{t-1} + Arrivals_t - GateCapacity_t) and compares against
 * reviewed holding-zone capacities without silently inventing legal density constants.
 */
@Service
public class QueueProjectionService {

    private static final DateTimeFormatter CLOCK_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String SHARED_CHANNEL = "ALL";

    private final GeometryUtil geometryUtil;

    public QueueProjectionService(GeometryUtil geometryUtil) {
        this.geometryUtil = geometryUtil;
    }

    public OperationsDtos.QueueProjectionResult project(Integer expectedAttendance,
                                                        LocalDateTime eventStart,
                                                        List<ArrivalWaveInterval> intervals,
                                                        List<InfrastructureElement> elements,
                                                        CapacityReview capacityReview,
                                                        CapacityDtos.CapacityCalculationResult capacityResult) {
        List<ArrivalWaveInterval> safeIntervals = intervals != null ? intervals : List.of();
        List<InfrastructureElement> safeElements = elements != null ? elements : List.of();

        List<OperationsDtos.EntryGateRateDto> gateDtos = new ArrayList<>();
        Map<Long, OperationsDtos.EntryGateRateDto> gateById = new LinkedHashMap<>();
        double activeGateSum = 0.0;
        int activeGateCount = 0;

        for (InfrastructureElement el : safeElements) {
            if (el == null || el.getElementType() != InfrastructureType.ENTRY_GATE) {
                continue;
            }
            double rate = resolveGateRate(el);
            boolean active = el.isActive();
            OperationsDtos.EntryGateRateDto gateDto = new OperationsDtos.EntryGateRateDto(
                    el.getId(), el.getLabel(), geometryUtil.round2(rate), active);
            gateDtos.add(gateDto);
            if (el.getId() != null) {
                gateById.put(el.getId(), gateDto);
            }
            if (active) {
                activeGateSum += rate;
                activeGateCount++;
            }
        }

        double totalActiveEntryRatePerMin;
        String rateSourceNote;
        if (!gateDtos.isEmpty()) {
            totalActiveEntryRatePerMin = geometryUtil.round2(activeGateSum);
            rateSourceNote = activeGateCount > 0
                    ? activeGateCount + " active Entry Gate element(s)"
                    : "All placed Entry Gates are inactive (0.0 p/min)";
        } else if (capacityReview != null
                && capacityReview.getEntryThroughputPerMin() != null
                && capacityReview.getEntryThroughputPerMin() > 0.0) {
            totalActiveEntryRatePerMin = geometryUtil.round2(capacityReview.getEntryThroughputPerMin());
            rateSourceNote = "Capacity Review entry throughput fallback";
        } else {
            totalActiveEntryRatePerMin = 0.0;
            rateSourceNote = "No active Entry Gates or entry rate configured";
        }

        HoldingMetrics holding = computeHoldingMetrics(capacityReview, capacityResult);
        List<OperationsDtos.QueueWarningDto> warnings = new ArrayList<>();

        if (holding.holdingZoneAreaSqm <= 0.0 && holding.holdingBufferCapacity == 0) {
            warnings.add(new OperationsDtos.QueueWarningDto(
                    "NO_HOLDING_ZONES",
                    "HIGH",
                    "No queue or holding capacity configured",
                    "Define QUEUE or SECURITY_SCREENING zones with density citations (or a holding buffer) so queue spillover can be measured against physical space."
            ));
        } else if (holding.holdingZoneAreaSqm > 0.0 && holding.effectiveHoldingDensityPerSqm == null) {
            warnings.add(new OperationsDtos.QueueWarningDto(
                    "HOLDING_DENSITY_MISSING",
                    "HIGH",
                    "Holding zone density assumption missing",
                    "Enter explicit density assumptions and citations for QUEUE / SECURITY_SCREENING zones in Capacity Review to compute holding space deficits in m²."
            ));
        }

        if (totalActiveEntryRatePerMin <= 0.0 && !safeIntervals.isEmpty()) {
            warnings.add(new OperationsDtos.QueueWarningDto(
                    "ZERO_ENTRY_RATE",
                    "CRITICAL",
                    "Zero active entry gate throughput",
                    "Place active ENTRY_GATE elements in Venue Plan -> Infrastructure or configure entry throughput in Capacity Review."
            ));
        }

        if (safeIntervals.isEmpty()) {
            return new OperationsDtos.QueueProjectionResult(
                    0,
                    expectedAttendance,
                    totalActiveEntryRatePerMin,
                    rateSourceNote,
                    activeGateCount,
                    holding.holdingZoneAreaSqm,
                    holding.holdingZoneAreaCapacity,
                    holding.holdingBufferCapacity,
                    holding.effectiveHoldingCapacity,
                    holding.effectiveHoldingDensityPerSqm,
                    0,
                    null,
                    0.0,
                    false,
                    0,
                    0,
                    0.0,
                    Math.max(10, holding.effectiveHoldingCapacity),
                    holding.effectiveHoldingCapacity > 0 ? 100 : 0,
                    List.copyOf(gateDtos),
                    List.of(),
                    List.copyOf(warnings)
            );
        }

        List<ArrivalWaveInterval> sorted = new ArrayList<>(safeIntervals);
        sorted.sort(Comparator
                .comparingInt(ArrivalWaveInterval::getStartMinute)
                .thenComparingInt(ArrivalWaveInterval::getEndMinute)
                .thenComparing(w -> w.getId() != null ? w.getId() : Long.MAX_VALUE));

        // Group intervals sharing the exact same [startMinute, endMinute] slot for accurate multi-gate parallelism
        List<List<ArrivalWaveInterval>> slots = new ArrayList<>();
        for (ArrivalWaveInterval w : sorted) {
            if (slots.isEmpty()) {
                List<ArrivalWaveInterval> first = new ArrayList<>();
                first.add(w);
                slots.add(first);
            } else {
                List<ArrivalWaveInterval> prevSlot = slots.get(slots.size() - 1);
                ArrivalWaveInterval ref = prevSlot.get(0);
                if (ref.getStartMinute() == w.getStartMinute() && ref.getEndMinute() == w.getEndMinute()) {
                    prevSlot.add(w);
                } else {
                    List<ArrivalWaveInterval> next = new ArrayList<>();
                    next.add(w);
                    slots.add(next);
                }
            }
        }

        Map<String, Integer> queueByChannel = new LinkedHashMap<>();
        List<InterimRow> interimRows = new ArrayList<>(sorted.size());

        int totalExpectedArrivals = 0;
        int peakQueueLength = 0;
        String peakQueueWindowLabel = null;
        double maxWaitMinutes = 0.0;
        boolean hasUnboundedWait = false;
        int overflowIntervalCount = 0;
        double maxDeficitSqm = 0.0;
        boolean anyDeficitComputed = false;
        int maxBarScale = Math.max(10, holding.effectiveHoldingCapacity);
        boolean warnedInactiveGate = false;

        for (List<ArrivalWaveInterval> slot : slots) {
            if (slot.size() == 1) {
                ArrivalWaveInterval w = slot.get(0);
                String channel = channelKey(w);
                // In sequential single-interval progression, any prior carryover flows into this interval
                int openingQueue = sumValues(queueByChannel);
                queueByChannel.clear();

                GateRateResolution rateRes = resolveIntervalGateRate(w, gateById, totalActiveEntryRatePerMin);
                if (rateRes.inactiveTargetedGate && !warnedInactiveGate) {
                    warnedInactiveGate = true;
                    warnings.add(new OperationsDtos.QueueWarningDto(
                            "INACTIVE_TARGET_GATE",
                            "HIGH",
                            "Arrival wave targets an inactive Entry Gate",
                            "Interval " + formatMinuteWindow(w.getStartMinute(), w.getEndMinute())
                                    + " targets inactive gate \"" + rateRes.targetGateLabel + "\" (0.0 p/min capacity)."
                    ));
                }

                int durationMin = Math.max(1, w.getEndMinute() - w.getStartMinute());
                int gateCapacity = (int) Math.floor(rateRes.ratePerMin * durationMin);
                int arrivals = Math.max(0, w.getExpectedArrivals());
                int totalDemand = openingQueue + arrivals;
                int processed = Math.min(totalDemand, gateCapacity);
                int endingQueue = Math.max(0, totalDemand - gateCapacity);
                int peakInInterval = Math.max(openingQueue, endingQueue);

                queueByChannel.put(channel, endingQueue);

                WaitMetrics wait = computeWait(endingQueue, rateRes.ratePerMin);
                OverflowMetrics ov = computeOverflow(
                        endingQueue, holding.effectiveHoldingCapacity, holding.effectiveHoldingDensityPerSqm);

                totalExpectedArrivals += arrivals;
                maxBarScale = Math.max(maxBarScale, Math.max(endingQueue, Math.max(arrivals, gateCapacity)));

                String minWin = formatMinuteWindow(w.getStartMinute(), w.getEndMinute());
                String clockWin = formatClockWindow(eventStart, w.getStartMinute(), w.getEndMinute());

                if (endingQueue > peakQueueLength) {
                    peakQueueLength = endingQueue;
                    peakQueueWindowLabel = clockWin != null ? clockWin : minWin;
                }
                if (wait.unbounded) {
                    hasUnboundedWait = true;
                } else if (wait.waitMinutes != null && wait.waitMinutes > maxWaitMinutes) {
                    maxWaitMinutes = wait.waitMinutes;
                }
                if (ov.overflow) {
                    overflowIntervalCount++;
                }
                if (ov.deficitSqm != null) {
                    anyDeficitComputed = true;
                    if (ov.deficitSqm > maxDeficitSqm) {
                        maxDeficitSqm = ov.deficitSqm;
                    }
                }

                interimRows.add(new InterimRow(
                        w, durationMin, minWin, clockWin,
                        rateRes.targetGateId, rateRes.targetGateLabel,
                        arrivals, rateRes.ratePerMin, gateCapacity,
                        openingQueue, processed, endingQueue, peakInInterval,
                        wait.waitMinutes, wait.unbounded, ov.overflow, ov.overflowPersons, ov.deficitSqm
                ));
            } else {
                // Parallel intervals in the exact same [startMinute, endMinute] slot
                int sharedCarryover = queueByChannel.getOrDefault(SHARED_CHANNEL, 0);
                queueByChannel.remove(SHARED_CHANNEL);

                List<GateRateResolution> slotRates = new ArrayList<>(slot.size());
                double slotRateSum = 0.0;
                for (ArrivalWaveInterval w : slot) {
                    GateRateResolution res = resolveIntervalGateRate(w, gateById, totalActiveEntryRatePerMin);
                    slotRates.add(res);
                    slotRateSum += res.ratePerMin;
                }

                int remainingShared = sharedCarryover;
                int slotEndQueueSum = 0;

                for (int i = 0; i < slot.size(); i++) {
                    ArrivalWaveInterval w = slot.get(i);
                    GateRateResolution rateRes = slotRates.get(i);
                    String channel = channelKey(w);

                    int sharedSlice;
                    if (i == slot.size() - 1) {
                        sharedSlice = remainingShared;
                    } else if (slotRateSum > 0.0) {
                        sharedSlice = (int) Math.floor(sharedCarryover * (rateRes.ratePerMin / slotRateSum));
                    } else {
                        sharedSlice = sharedCarryover / slot.size();
                    }
                    remainingShared = Math.max(0, remainingShared - sharedSlice);

                    int priorChannelQueue = queueByChannel.getOrDefault(channel, 0);
                    int openingQueue = priorChannelQueue + sharedSlice;
                    int durationMin = Math.max(1, w.getEndMinute() - w.getStartMinute());
                    int gateCapacity = (int) Math.floor(rateRes.ratePerMin * durationMin);
                    int arrivals = Math.max(0, w.getExpectedArrivals());
                    int totalDemand = openingQueue + arrivals;
                    int processed = Math.min(totalDemand, gateCapacity);
                    int endingQueue = Math.max(0, totalDemand - gateCapacity);
                    int peakInInterval = Math.max(openingQueue, endingQueue);

                    queueByChannel.put(channel, endingQueue);
                    slotEndQueueSum += endingQueue;

                    WaitMetrics wait = computeWait(endingQueue, rateRes.ratePerMin);
                    OverflowMetrics ov = computeOverflow(
                            endingQueue, holding.effectiveHoldingCapacity, holding.effectiveHoldingDensityPerSqm);

                    totalExpectedArrivals += arrivals;
                    maxBarScale = Math.max(maxBarScale, Math.max(endingQueue, Math.max(arrivals, gateCapacity)));

                    String minWin = formatMinuteWindow(w.getStartMinute(), w.getEndMinute());
                    String clockWin = formatClockWindow(eventStart, w.getStartMinute(), w.getEndMinute());

                    if (wait.unbounded) {
                        hasUnboundedWait = true;
                    } else if (wait.waitMinutes != null && wait.waitMinutes > maxWaitMinutes) {
                        maxWaitMinutes = wait.waitMinutes;
                    }
                    if (ov.overflow) {
                        overflowIntervalCount++;
                    }
                    if (ov.deficitSqm != null) {
                        anyDeficitComputed = true;
                        if (ov.deficitSqm > maxDeficitSqm) {
                            maxDeficitSqm = ov.deficitSqm;
                        }
                    }

                    interimRows.add(new InterimRow(
                            w, durationMin, minWin, clockWin,
                            rateRes.targetGateId, rateRes.targetGateLabel,
                            arrivals, rateRes.ratePerMin, gateCapacity,
                            openingQueue, processed, endingQueue, peakInInterval,
                            wait.waitMinutes, wait.unbounded, ov.overflow, ov.overflowPersons, ov.deficitSqm
                    ));
                }

                // Include any idle channel carryover in slot total
                int totalAtSlotEnd = sumValues(queueByChannel);
                if (totalAtSlotEnd > peakQueueLength) {
                    peakQueueLength = totalAtSlotEnd;
                    ArrivalWaveInterval ref = slot.get(0);
                    String clockWin = formatClockWindow(eventStart, ref.getStartMinute(), ref.getEndMinute());
                    peakQueueWindowLabel = clockWin != null
                            ? clockWin
                            : formatMinuteWindow(ref.getStartMinute(), ref.getEndMinute());
                }
                maxBarScale = Math.max(maxBarScale, slotEndQueueSum);
            }
        }

        int residualUnservedQueue = sumValues(queueByChannel);
        if (residualUnservedQueue > 0) {
            warnings.add(new OperationsDtos.QueueWarningDto(
                    "RESIDUAL_UNSERVED_QUEUE",
                    "HIGH",
                    "Unserved queue remains after final arrival interval",
                    String.format(Locale.US, "%d attendees remain queued after the final scheduled interval.", residualUnservedQueue)
            ));
        }

        if (overflowIntervalCount > 0) {
            String deficitText = anyDeficitComputed
                    ? String.format(Locale.US, " Peak holding space deficit: %.1f m².", maxDeficitSqm)
                    : "";
            warnings.add(new OperationsDtos.QueueWarningDto(
                    "HOLDING_SPACE_OVERFLOW",
                    "CRITICAL",
                    "Projected queue exceeds holding capacity in " + overflowIntervalCount + " interval(s)",
                    String.format(Locale.US, "Peak queue reaches %d persons vs %d holding capacity.%s",
                            peakQueueLength, holding.effectiveHoldingCapacity, deficitText)
            ));
        }

        if (expectedAttendance != null && expectedAttendance > 0 && totalExpectedArrivals != expectedAttendance) {
            int delta = totalExpectedArrivals - expectedAttendance;
            String relation = delta > 0
                    ? "+" + delta + " above expected attendance"
                    : Math.abs(delta) + " below expected attendance";
            warnings.add(new OperationsDtos.QueueWarningDto(
                    "ARRIVAL_TOTAL_MISMATCH",
                    "INFO",
                    "Wave arrivals total differs from event expected attendance",
                    String.format(Locale.US, "Scheduled wave arrivals sum to %d (%s of %d).",
                            totalExpectedArrivals, relation, expectedAttendance)
            ));
        }

        final int chartScale = Math.max(10, maxBarScale);
        List<OperationsDtos.QueueIntervalRowDto> rowDtos = new ArrayList<>(interimRows.size());
        for (InterimRow r : interimRows) {
            rowDtos.add(new OperationsDtos.QueueIntervalRowDto(
                    r.wave.getId(),
                    r.wave.getLabel(),
                    r.wave.getStartMinute(),
                    r.wave.getEndMinute(),
                    r.durationMinutes,
                    r.minuteWindowLabel,
                    r.clockWindowLabel,
                    r.targetGateId,
                    r.targetGateLabel,
                    r.expectedArrivals,
                    geometryUtil.round2(r.effectiveRatePerMin),
                    r.gateCapacity,
                    r.openingQueue,
                    r.processedCount,
                    r.endingQueue,
                    r.peakQueueInInterval,
                    r.estimatedWaitMinutes,
                    r.unboundedWait,
                    r.overflow,
                    r.overflowPersons,
                    r.holdingDeficitSqm,
                    toPct(r.endingQueue, chartScale),
                    toPct(r.expectedArrivals, chartScale),
                    toPct(r.gateCapacity, chartScale),
                    r.wave.getNotes()
            ));
        }

        int holdingLinePct = holding.effectiveHoldingCapacity > 0
                ? Math.min(100, (int) Math.round((holding.effectiveHoldingCapacity * 100.0) / chartScale))
                : 0;

        Double peakWaitOut = hasUnboundedWait ? null : Double.valueOf(geometryUtil.round2(maxWaitMinutes));
        Double maxDeficitOut;
        if (anyDeficitComputed) {
            maxDeficitOut = geometryUtil.round2(maxDeficitSqm);
        } else if (overflowIntervalCount == 0) {
            maxDeficitOut = 0.0;
        } else {
            maxDeficitOut = null;
        }

        return new OperationsDtos.QueueProjectionResult(
                totalExpectedArrivals,
                expectedAttendance,
                totalActiveEntryRatePerMin,
                rateSourceNote,
                activeGateCount,
                holding.holdingZoneAreaSqm,
                holding.holdingZoneAreaCapacity,
                holding.holdingBufferCapacity,
                holding.effectiveHoldingCapacity,
                holding.effectiveHoldingDensityPerSqm,
                peakQueueLength,
                peakQueueWindowLabel,
                peakWaitOut,
                hasUnboundedWait,
                overflowIntervalCount,
                residualUnservedQueue,
                maxDeficitOut,
                chartScale,
                holdingLinePct,
                List.copyOf(gateDtos),
                List.copyOf(rowDtos),
                List.copyOf(warnings)
        );
    }

    private double resolveGateRate(InfrastructureElement gate) {
        if (gate.getThroughputPersonsPerMin() != null && Double.isFinite(gate.getThroughputPersonsPerMin())
                && gate.getThroughputPersonsPerMin() >= 0.0) {
            return gate.getThroughputPersonsPerMin();
        }
        Double def = InfrastructureType.ENTRY_GATE.getDefaultThroughputPerMin();
        return def != null ? def : 0.0;
    }

    private GateRateResolution resolveIntervalGateRate(ArrivalWaveInterval w,
                                                       Map<Long, OperationsDtos.EntryGateRateDto> gateById,
                                                       double totalActiveRate) {
        InfrastructureElement tg = w.getTargetGate();
        if (tg == null) {
            return new GateRateResolution(null, "All Active Entry Gates", totalActiveRate, false);
        }
        OperationsDtos.EntryGateRateDto matched = tg.getId() != null ? gateById.get(tg.getId()) : null;
        if (matched != null) {
            return new GateRateResolution(
                    matched.id(),
                    matched.label(),
                    matched.active() ? matched.throughputPersonsPerMin() : 0.0,
                    !matched.active()
            );
        }
        double directRate = tg.isActive() ? resolveGateRate(tg) : 0.0;
        return new GateRateResolution(tg.getId(), tg.getLabel(), directRate, !tg.isActive());
    }

    private HoldingMetrics computeHoldingMetrics(CapacityReview review,
                                                 CapacityDtos.CapacityCalculationResult capacityResult) {
        double holdingAreaSqm = 0.0;
        double configuredAreaSqm = 0.0;
        double weightedCapacitySum = 0.0;
        int zoneCapSum = 0;
        boolean hasConfiguredZoneCap = false;

        if (capacityResult != null && capacityResult.zoneRows() != null) {
            for (CapacityDtos.ZoneCapacityRow row : capacityResult.zoneRows()) {
                if (row.capacityBucket() != ZoneType.CapacityBucket.HOLDING) {
                    continue;
                }
                holdingAreaSqm += row.usableZoneAreaSqm();
                if (row.densityPersonsPerSqm() != null && row.densityPersonsPerSqm() > 0.0) {
                    configuredAreaSqm += row.usableZoneAreaSqm();
                    weightedCapacitySum += row.usableZoneAreaSqm() * row.densityPersonsPerSqm();
                    int cap = row.computedCapacity() != null
                            ? row.computedCapacity()
                            : (int) Math.floor(row.usableZoneAreaSqm() * row.densityPersonsPerSqm());
                    zoneCapSum += Math.max(0, cap);
                    hasConfiguredZoneCap = true;
                }
            }
        }

        int buffer = review != null ? Math.max(0, review.getHoldingBufferCapacity()) : 0;
        Integer zoneCapOut = hasConfiguredZoneCap ? zoneCapSum : null;
        int effectiveHolding = (zoneCapOut != null ? zoneCapOut : 0) + buffer;
        Double effectiveDensity = configuredAreaSqm > 1e-6
                ? geometryUtil.round2(weightedCapacitySum / configuredAreaSqm)
                : null;

        return new HoldingMetrics(
                geometryUtil.round2(holdingAreaSqm),
                zoneCapOut,
                buffer,
                effectiveHolding,
                effectiveDensity
        );
    }

    private WaitMetrics computeWait(int endingQueue, double ratePerMin) {
        if (endingQueue <= 0) {
            return new WaitMetrics(0.0, false);
        }
        if (ratePerMin <= 1e-6) {
            return new WaitMetrics(null, true);
        }
        return new WaitMetrics(geometryUtil.round2(endingQueue / ratePerMin), false);
    }

    private OverflowMetrics computeOverflow(int endingQueue,
                                            int effectiveHoldingCapacity,
                                            Double effectiveHoldingDensity) {
        if (endingQueue <= effectiveHoldingCapacity) {
            return new OverflowMetrics(false, 0, 0.0);
        }
        int overflowPersons = endingQueue - effectiveHoldingCapacity;
        Double deficitSqm = (effectiveHoldingDensity != null && effectiveHoldingDensity > 1e-6)
                ? geometryUtil.round2(overflowPersons / effectiveHoldingDensity)
                : null;
        return new OverflowMetrics(true, overflowPersons, deficitSqm);
    }

    private String channelKey(ArrivalWaveInterval w) {
        return w.getTargetGate() != null && w.getTargetGate().getId() != null
                ? "GATE:" + w.getTargetGate().getId()
                : SHARED_CHANNEL;
    }

    private int sumValues(Map<String, Integer> map) {
        int sum = 0;
        for (Integer v : map.values()) {
            if (v != null && v > 0) {
                sum += v;
            }
        }
        return sum;
    }

    private String formatMinuteWindow(int startMin, int endMin) {
        return startMin + "–" + endMin + " min";
    }

    private String formatClockWindow(LocalDateTime eventStart, int startMin, int endMin) {
        if (eventStart == null) {
            return null;
        }
        LocalDateTime s = eventStart.plusMinutes(startMin);
        LocalDateTime e = eventStart.plusMinutes(endMin);
        return CLOCK_FMT.format(s) + "–" + CLOCK_FMT.format(e);
    }

    private int toPct(int val, int maxScale) {
        if (val <= 0 || maxScale <= 0) {
            return 0;
        }
        return Math.min(100, Math.max(2, (int) Math.round((val * 100.0) / maxScale)));
    }

    private record GateRateResolution(
            Long targetGateId,
            String targetGateLabel,
            double ratePerMin,
            boolean inactiveTargetedGate
    ) {
    }

    private record HoldingMetrics(
            double holdingZoneAreaSqm,
            Integer holdingZoneAreaCapacity,
            int holdingBufferCapacity,
            int effectiveHoldingCapacity,
            Double effectiveHoldingDensityPerSqm
    ) {
    }

    private record WaitMetrics(Double waitMinutes, boolean unbounded) {
    }

    private record OverflowMetrics(boolean overflow, int overflowPersons, Double deficitSqm) {
    }

    private record InterimRow(
            ArrivalWaveInterval wave,
            int durationMinutes,
            String minuteWindowLabel,
            String clockWindowLabel,
            Long targetGateId,
            String targetGateLabel,
            int expectedArrivals,
            double effectiveRatePerMin,
            int gateCapacity,
            int openingQueue,
            int processedCount,
            int endingQueue,
            int peakQueueInInterval,
            Double estimatedWaitMinutes,
            boolean unboundedWait,
            boolean overflow,
            int overflowPersons,
            Double holdingDeficitSqm
    ) {
    }
}
