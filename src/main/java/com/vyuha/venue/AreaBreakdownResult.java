package com.vyuha.venue;

import com.vyuha.common.GeometryUtil;
import java.util.List;
import java.util.Map;

/**
 * Deterministic JTS-computed real-world space breakdown (m²) free of overlap double-counting.
 */
public record AreaBreakdownResult(
        boolean hasBoundary,
        double grossAreaSqm,
        double boundaryPerimeterM,
        double restrictedAreaSqm,
        double corridorsAreaSqm,
        double excludedTotalAreaSqm,
        double usableAreaSqm,
        double usablePercent,
        double restrictedPercent,
        double corridorsPercent,
        double excludedPercent,
        double overlapDeduplicatedSqm,
        double totalOutsideBoundarySqm,
        Map<String, Double> areaByCategorySqm,
        List<ExclusionBreakdownItem> exclusions,
        GeometryUtil.Bounds boundaryBounds
) {
    public record ExclusionBreakdownItem(
            Long exclusionId,
            String label,
            ExclusionType exclusionType,
            boolean corridor,
            double rawAreaSqm,
            double clippedAreaSqm,
            double outsideBoundarySqm,
            GeometryUtil.Point2D centroid
    ) {
    }

    public static AreaBreakdownResult empty() {
        return new AreaBreakdownResult(
                false, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                Map.of(), List.of(), null
        );
    }
}
