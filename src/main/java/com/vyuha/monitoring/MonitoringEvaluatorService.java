package com.vyuha.monitoring;

import com.vyuha.capacity.AssumptionReviewStatus;
import com.vyuha.capacity.CapacityDtos;
import com.vyuha.common.GeometryUtil;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.infrastructure.FindingSeverity;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Service;

/**
 * Pure-Java deterministic evaluator for live occupancy monitoring channels.
 * Strictly isolates MANUAL_STAFF, PASS_SCAN, and PRACTICE_SIMULATION readings
 * with zero blending, computes zone load bands and staleness flags, and plans
 * threshold / staleness alert creations and auto-resolutions.
 */
@Service
public class MonitoringEvaluatorService {

    public static final double DEFAULT_WARNING_THRESHOLD_PCT = 80.0;
    public static final double DEFAULT_CRITICAL_THRESHOLD_PCT = 95.0;
    public static final int DEFAULT_STALE_AFTER_MINUTES = 15;

    private final GeometryUtil geometryUtil;

    public MonitoringEvaluatorService(GeometryUtil geometryUtil) {
        this.geometryUtil = geometryUtil;
    }

    public record ChannelEvaluationResult(
            MonitoringDtos.ChannelSummaryDto summary,
            List<MonitoringDtos.ZoneOccupancyDto> zones
    ) {
    }

    public record PendingAlert(
            Long zoneId,
            OccupancyDataSource dataSource,
            FindingSeverity severity,
            MonitoringAlertType alertType,
            String message
    ) {
    }

    public record AlertMutationPlan(
            List<PendingAlert> toCreate,
            List<Long> alertIdsToResolve
    ) {
    }

    public ChannelEvaluationResult evaluateChannel(
            OccupancyDataSource source,
            List<FunctionalZone> zones,
            List<CapacityDtos.ZoneCapacityRow> capacityRows,
            List<OccupancyReading> allReadings,
            List<MonitoringAlertRule> rules,
            Instant now
    ) {
        Instant evalTime = now != null ? now : Instant.now();
        List<FunctionalZone> safeZones = zones != null ? zones : List.of();

        Map<Long, CapacityDtos.ZoneCapacityRow> capRowByZone = new LinkedHashMap<>();
        if (capacityRows != null) {
            for (CapacityDtos.ZoneCapacityRow row : capacityRows) {
                if (row != null && row.zoneId() != null) {
                    capRowByZone.put(row.zoneId(), row);
                }
            }
        }

        EffectiveRule defaultRule = resolveDefaultRule(rules);
        Map<Long, EffectiveRule> zoneRules = resolveZoneRules(rules, defaultRule);

        // Strictly filter readings to ONE dataSource — never blend across channels.
        Map<Long, OccupancyReading> latestByZone = new LinkedHashMap<>();
        OccupancyReading latestUnzoned = null;
        if (allReadings != null) {
            List<OccupancyReading> sortedForSource = allReadings.stream()
                    .filter(r -> r != null && r.getDataSource() == source)
                    .sorted(Comparator
                            .comparing(OccupancyReading::getRecordedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(OccupancyReading::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();

            for (OccupancyReading r : sortedForSource) {
                if (r.getZone() != null && r.getZone().getId() != null) {
                    latestByZone.putIfAbsent(r.getZone().getId(), r);
                } else if (latestUnzoned == null) {
                    latestUnzoned = r;
                }
            }
        }

        List<MonitoringDtos.ZoneOccupancyDto> zoneDtos = new ArrayList<>(safeZones.size());
        int totalZoned = 0;
        int reportingZoneCount = 0;
        int staleZoneCount = 0;
        Instant latestChannelUpdate = null;

        for (FunctionalZone zone : safeZones) {
            Long zoneId = zone.getId();
            EffectiveRule rule = zoneRules.getOrDefault(zoneId, defaultRule);
            CapacityDtos.ZoneCapacityRow capRow = zoneId != null ? capRowByZone.get(zoneId) : null;

            double usableArea = capRow != null ? capRow.usableZoneAreaSqm() : parseFallbackArea(zone.getPolygonWkt());
            Integer reviewedCap = capRow != null ? capRow.computedCapacity() : null;
            String capStatusLabel = capRow != null
                    ? capRow.reviewStatus().getDisplayName()
                    : AssumptionReviewStatus.PENDING_REVIEW.getDisplayName();

            OccupancyReading reading = zoneId != null ? latestByZone.get(zoneId) : null;
            Integer count = reading != null ? Math.max(0, reading.getCountValue()) : null;
            Instant updatedAt = reading != null ? reading.getRecordedAt() : null;
            String recordedBy = reading != null ? reading.getRecordedBy() : null;
            String note = reading != null ? reading.getNote() : null;

            Long ageMinutes = null;
            boolean stale = false;
            if (updatedAt != null) {
                long ageSeconds = Math.max(0L, Duration.between(updatedAt, evalTime).getSeconds());
                ageMinutes = ageSeconds / 60L;
                stale = ageSeconds >= (long) rule.staleAfterMinutes() * 60L;
                if (latestChannelUpdate == null || updatedAt.isAfter(latestChannelUpdate)) {
                    latestChannelUpdate = updatedAt;
                }
            }

            if (count != null) {
                totalZoned += count;
                reportingZoneCount++;
                if (stale) {
                    staleZoneCount++;
                }
            }

            Double occupancyPct = (count != null && reviewedCap != null && reviewedCap > 0)
                    ? round1((count * 100.0) / reviewedCap)
                    : null;
            Double currentDensityPsm = (count != null && usableArea > 0.0)
                    ? geometryUtil.round2(count / usableArea)
                    : null;

            ZoneCongestionBand band = classifyBand(count, occupancyPct, stale, rule);

            List<GeometryUtil.PolygonShape> shapes = List.of();
            GeometryUtil.Point2D labelPoint = null;
            if (zone.getPolygonWkt() != null && !zone.getPolygonWkt().isBlank()) {
                try {
                    Geometry geom = geometryUtil.parsePolygonal(zone.getPolygonWkt());
                    shapes = geometryUtil.extractPolygonShapes(geom);
                    labelPoint = geometryUtil.interiorPoint(geom);
                } catch (IllegalArgumentException ignored) {
                }
            }

            zoneDtos.add(new MonitoringDtos.ZoneOccupancyDto(
                    zoneId,
                    zone.getZoneCode(),
                    zone.getName(),
                    zone.getZoneType(),
                    zone.getZoneType().getDisplayName(),
                    usableArea,
                    reviewedCap,
                    capStatusLabel,
                    source,
                    source.getShortLabel(),
                    source.getBadgeClass(),
                    count,
                    occupancyPct,
                    currentDensityPsm,
                    updatedAt,
                    recordedBy,
                    note,
                    ageMinutes,
                    stale,
                    rule.warningThresholdPct(),
                    rule.criticalThresholdPct(),
                    rule.staleAfterMinutes(),
                    band,
                    band.getLabel(),
                    band.getFillColor(),
                    band.getStrokeColor(),
                    band.isHatched(),
                    band.getBadgeClass(),
                    shapes,
                    labelPoint
            ));
        }

        int unzonedCount = latestUnzoned != null ? Math.max(0, latestUnzoned.getCountValue()) : 0;
        if (latestUnzoned != null && latestUnzoned.getRecordedAt() != null) {
            Instant unzonedAt = latestUnzoned.getRecordedAt();
            if (latestChannelUpdate == null || unzonedAt.isAfter(latestChannelUpdate)) {
                latestChannelUpdate = unzonedAt;
            }
        }

        boolean channelStale = staleZoneCount > 0 || (latestChannelUpdate != null
                && Duration.between(latestChannelUpdate, evalTime).getSeconds()
                        >= (long) defaultRule.staleAfterMinutes() * 60L);

        MonitoringDtos.ChannelSummaryDto summary = new MonitoringDtos.ChannelSummaryDto(
                source,
                source.getDisplayName(),
                source.getShortLabel(),
                source.getBadgeClass(),
                totalZoned + unzonedCount,
                unzonedCount,
                latestChannelUpdate,
                channelStale,
                staleZoneCount,
                reportingZoneCount,
                defaultRule.staleAfterMinutes()
        );

        return new ChannelEvaluationResult(summary, List.copyOf(zoneDtos));
    }

    public AlertMutationPlan evaluateAlertMutations(
            OccupancyDataSource source,
            List<MonitoringDtos.ZoneOccupancyDto> zoneDtos,
            List<MonitoringAlertEvent> existingActiveAlertsForSource,
            Instant now
    ) {
        List<PendingAlert> toCreate = new ArrayList<>();
        Set<Long> toResolveIds = new LinkedHashSet<>();

        Map<AlertKey, List<MonitoringAlertEvent>> activeByKey = new LinkedHashMap<>();
        if (existingActiveAlertsForSource != null) {
            for (MonitoringAlertEvent alert : existingActiveAlertsForSource) {
                if (alert == null || alert.getDataSource() != source || alert.getResolvedAt() != null) {
                    continue;
                }
                Long zoneId = alert.getZone() != null ? alert.getZone().getId() : null;
                activeByKey.computeIfAbsent(new AlertKey(zoneId, alert.getAlertType()), k -> new ArrayList<>()).add(alert);
            }
        }

        if (zoneDtos != null) {
            for (MonitoringDtos.ZoneOccupancyDto z : zoneDtos) {
                Long zoneId = z.zoneId();
                if (zoneId == null) {
                    continue;
                }

                // 1. Threshold alerts (CRITICAL vs HIGH warning)
                if (z.occupancyPct() != null && z.currentCount() != null) {
                    double pct = z.occupancyPct();
                    if (pct >= z.criticalThresholdPct()) {
                        resolveIfPresent(activeByKey, zoneId, MonitoringAlertType.CAPACITY_WARNING, toResolveIds);
                        if (!activeByKey.containsKey(new AlertKey(zoneId, MonitoringAlertType.CAPACITY_CRITICAL))) {
                            toCreate.add(new PendingAlert(
                                    zoneId,
                                    source,
                                    FindingSeverity.CRITICAL,
                                    MonitoringAlertType.CAPACITY_CRITICAL,
                                    String.format(Locale.US,
                                            "%s (%s) at %.1f%% capacity (%d / %d) via %s — exceeds %.0f%% critical threshold.",
                                            z.zoneCode(), z.zoneName(), pct, z.currentCount(),
                                            z.reviewedCapacity(), z.sourceShortLabel(), z.criticalThresholdPct())
                            ));
                        }
                    } else if (pct >= z.warningThresholdPct()) {
                        resolveIfPresent(activeByKey, zoneId, MonitoringAlertType.CAPACITY_CRITICAL, toResolveIds);
                        if (!activeByKey.containsKey(new AlertKey(zoneId, MonitoringAlertType.CAPACITY_WARNING))) {
                            toCreate.add(new PendingAlert(
                                    zoneId,
                                    source,
                                    FindingSeverity.HIGH,
                                    MonitoringAlertType.CAPACITY_WARNING,
                                    String.format(Locale.US,
                                            "%s (%s) at %.1f%% capacity (%d / %d) via %s — exceeds %.0f%% warning threshold.",
                                            z.zoneCode(), z.zoneName(), pct, z.currentCount(),
                                            z.reviewedCapacity(), z.sourceShortLabel(), z.warningThresholdPct())
                            ));
                        }
                    } else {
                        resolveIfPresent(activeByKey, zoneId, MonitoringAlertType.CAPACITY_WARNING, toResolveIds);
                        resolveIfPresent(activeByKey, zoneId, MonitoringAlertType.CAPACITY_CRITICAL, toResolveIds);
                    }
                } else {
                    resolveIfPresent(activeByKey, zoneId, MonitoringAlertType.CAPACITY_WARNING, toResolveIds);
                    resolveIfPresent(activeByKey, zoneId, MonitoringAlertType.CAPACITY_CRITICAL, toResolveIds);
                }

                // 2. Staleness alerts
                if (z.currentCount() != null && z.stale()) {
                    if (!activeByKey.containsKey(new AlertKey(zoneId, MonitoringAlertType.STALE_DATA))) {
                        long age = z.ageMinutes() != null ? z.ageMinutes() : z.staleAfterMinutes();
                        toCreate.add(new PendingAlert(
                                zoneId,
                                source,
                                FindingSeverity.LOW,
                                MonitoringAlertType.STALE_DATA,
                                String.format(Locale.US,
                                        "%s (%s) %s reading is %d min old (stale after %d min).",
                                        z.zoneCode(), z.zoneName(), z.sourceShortLabel(), age, z.staleAfterMinutes())
                        ));
                    }
                } else if (z.currentCount() != null && !z.stale()) {
                    resolveIfPresent(activeByKey, zoneId, MonitoringAlertType.STALE_DATA, toResolveIds);
                }
            }
        }

        return new AlertMutationPlan(List.copyOf(toCreate), List.copyOf(toResolveIds));
    }

    private ZoneCongestionBand classifyBand(Integer count,
                                            Double occupancyPct,
                                            boolean stale,
                                            EffectiveRule rule) {
        if (count == null) {
            return ZoneCongestionBand.NO_DATA;
        }
        if (stale) {
            return ZoneCongestionBand.STALE;
        }
        if (occupancyPct == null) {
            return ZoneCongestionBand.UNRATED;
        }
        if (occupancyPct >= rule.criticalThresholdPct()) {
            return ZoneCongestionBand.CRITICAL_LOAD;
        }
        if (occupancyPct >= rule.warningThresholdPct()) {
            return ZoneCongestionBand.HIGH_LOAD;
        }
        double moderateCutoff = Math.min(60.0, rule.warningThresholdPct() * 0.75);
        if (occupancyPct >= moderateCutoff) {
            return ZoneCongestionBand.MODERATE_LOAD;
        }
        return ZoneCongestionBand.LOW_LOAD;
    }

    private EffectiveRule resolveDefaultRule(List<MonitoringAlertRule> rules) {
        if (rules != null) {
            for (MonitoringAlertRule r : rules) {
                if (r != null && r.getZone() == null) {
                    return new EffectiveRule(
                            r.getWarningThresholdPct(),
                            r.getCriticalThresholdPct(),
                            Math.max(1, r.getStaleAfterMinutes())
                    );
                }
            }
        }
        return new EffectiveRule(DEFAULT_WARNING_THRESHOLD_PCT, DEFAULT_CRITICAL_THRESHOLD_PCT, DEFAULT_STALE_AFTER_MINUTES);
    }

    private Map<Long, EffectiveRule> resolveZoneRules(List<MonitoringAlertRule> rules, EffectiveRule defaultRule) {
        Map<Long, EffectiveRule> map = new LinkedHashMap<>();
        if (rules != null) {
            for (MonitoringAlertRule r : rules) {
                if (r != null && r.getZone() != null && r.getZone().getId() != null) {
                    map.put(r.getZone().getId(), new EffectiveRule(
                            r.getWarningThresholdPct(),
                            r.getCriticalThresholdPct(),
                            Math.max(1, r.getStaleAfterMinutes())
                    ));
                }
            }
        }
        return map;
    }

    private double parseFallbackArea(String polygonWkt) {
        if (polygonWkt == null || polygonWkt.isBlank()) {
            return 0.0;
        }
        try {
            return geometryUtil.round2(geometryUtil.polygonalArea(geometryUtil.parsePolygonal(polygonWkt)));
        } catch (IllegalArgumentException ex) {
            return 0.0;
        }
    }

    private void resolveIfPresent(Map<AlertKey, List<MonitoringAlertEvent>> activeByKey,
                                  Long zoneId,
                                  MonitoringAlertType type,
                                  Set<Long> toResolveIds) {
        List<MonitoringAlertEvent> alerts = activeByKey.get(new AlertKey(zoneId, type));
        if (alerts != null) {
            for (MonitoringAlertEvent alert : alerts) {
                if (alert.getId() != null) {
                    toResolveIds.add(alert.getId());
                }
            }
        }
    }

    private double round1(double v) {
        if (!Double.isFinite(v)) {
            return 0.0;
        }
        return Math.round(v * 10.0) / 10.0;
    }

    private record EffectiveRule(double warningThresholdPct, double criticalThresholdPct, int staleAfterMinutes) {
    }

    private record AlertKey(Long zoneId, MonitoringAlertType alertType) {
    }
}
