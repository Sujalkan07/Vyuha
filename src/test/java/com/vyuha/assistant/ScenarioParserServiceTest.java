package com.vyuha.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyuha.grid.ZoneType;
import com.vyuha.infrastructure.InfrastructureType;
import com.vyuha.network.NetworkLayer;
import com.vyuha.network.PathwayDirection;
import com.vyuha.network.PathwayNodeType;
import com.vyuha.simulation.SimulationDtos;
import com.vyuha.simulation.SimulationScenarioType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScenarioParserServiceTest {

    private final ScenarioParserService parser = new ScenarioParserService();

    private SimulationDtos.SerializedLayout sampleLayout() {
        List<SimulationDtos.SnapNode> nodes = List.of(
                new SimulationDtos.SnapNode(1L, "node-entry-1", PathwayNodeType.ENTRY_GATE, "North Entry",
                        10, 10, null, 101L, "#10B981", "#047857"),
                new SimulationDtos.SnapNode(2L, "node-hub-1", PathwayNodeType.ZONE_HUB, "Main Lawn Hub",
                        50, 45, 201L, null, "#3B82F6", "#1D4ED8"),
                new SimulationDtos.SnapNode(3L, "node-exit-a", PathwayNodeType.EXIT_GATE, "Exit Gate A",
                        90, 20, null, 102L, "#059669", "#065F46"),
                new SimulationDtos.SnapNode(4L, "node-exit-b", PathwayNodeType.EXIT_GATE, "Exit Gate B",
                        90, 70, null, 103L, "#059669", "#065F46")
        );
        List<SimulationDtos.SnapEdge> edges = List.of(
                new SimulationDtos.SnapEdge(11L, "edge-c1", "node-entry-1", "node-hub-1", "Entry Funnel",
                        NetworkLayer.NORMAL_ENTRY, 4.0, 45.0, PathwayDirection.ONE_WAY, true, "#10B981", false, null, List.of()),
                new SimulationDtos.SnapEdge(12L, "edge-c2", "node-hub-1", "node-exit-a", "Main Corridor C2",
                        NetworkLayer.NORMAL_EXIT, 2.2, 42.0, PathwayDirection.BIDIRECTIONAL, true, "#059669", false, null, List.of()),
                new SimulationDtos.SnapEdge(13L, "edge-c3", "node-hub-1", "node-exit-b", "South Exit Walkway",
                        NetworkLayer.NORMAL_EXIT, 4.0, 42.0, PathwayDirection.BIDIRECTIONAL, true, "#059669", false, null, List.of())
        );
        List<SimulationDtos.SnapElement> elements = List.of(
                new SimulationDtos.SnapElement(101L, "el-1", InfrastructureType.ENTRY_GATE, "North Entry",
                        10, 10, 6, 2, 0, 180.0, true, "#10B981", "#047857", null, List.of()),
                new SimulationDtos.SnapElement(102L, "el-2", InfrastructureType.EXIT_GATE, "Exit Gate A",
                        90, 20, 5, 2, 0, 140.0, true, "#059669", "#065F46", null, List.of()),
                new SimulationDtos.SnapElement(103L, "el-3", InfrastructureType.EXIT_GATE, "Exit Gate B",
                        90, 70, 5, 2, 0, 140.0, true, "#059669", "#065F46", null, List.of())
        );
        List<SimulationDtos.SnapZone> zones = List.of(
                new SimulationDtos.SnapZone(201L, "AUD-1", "Main Lawn", ZoneType.AUDIENCE,
                        true, false, "#3B82F6", "#1D4ED8", null, List.of(), null)
        );
        return new SimulationDtos.SerializedLayout(
                "Sample Event", 500, "INTERACTIVE_MAP", "campus-quadrangle", false, null,
                null, null, null, "POLYGON ((0 0, 100 0, 100 80, 0 80, 0 0))", List.of(), null,
                List.of(), zones, elements, nodes, edges, List.of(), List.of()
        );
    }

    @Test
    void parsesClosedExitWithMatchingGateLabel() {
        List<SimulationDtos.LayoutSnapshotSummaryDto> snaps = List.of(
                new SimulationDtos.LayoutSnapshotSummaryDto(
                        5L, 1, "Initial V1", null, "/thumb.png", 4, 3, 3, 1, "Organizer", Instant.now())
        );

        ScenarioParserService.ParsedScenarioResult parsed = parser.parse(
                "What happens if Exit Gate B closes during a sudden arrival rush?",
                sampleLayout(), snaps, List.of(), null);

        assertThat(parsed.layoutReadyForSimulation()).isTrue();
        assertThat(parsed.parsedScenarioType()).isEqualTo(SimulationScenarioType.CLOSED_EXIT);
        assertThat(parsed.matchedTargetType()).isEqualTo("EXIT");
        assertThat(parsed.matchedTargetLabel()).isEqualTo("Exit Gate B");
        assertThat(parsed.proposedConfig().targetExitNodeUid()).isEqualTo("node-exit-b");
        assertThat(parsed.proposedConfig().targetExitInfraId()).isEqualTo(103L);
        assertThat(parsed.targetAutoSelected()).isFalse();
        assertThat(parsed.confidence()).isEqualTo("HIGH");
        assertThat(parsed.targetSnapshotId()).isEqualTo(5L);
        assertThat(parsed.willCreateBaselineRun()).isTrue();
        assertThat(parsed.parsingNotes()).anyMatch(n -> n.contains("arrival rush"));
    }

    @Test
    void parsesBlockedCorridorWithNumericParameters() {
        ScenarioParserService.ParsedScenarioResult parsed = parser.parse(
                "Test blocking Main Corridor C2 after 60 seconds with 600 agents for 8 minutes seed 99",
                sampleLayout(), List.of(), List.of(), null);

        assertThat(parsed.parsedScenarioType()).isEqualTo(SimulationScenarioType.BLOCKED_CORRIDOR);
        assertThat(parsed.matchedTargetType()).isEqualTo("CORRIDOR");
        assertThat(parsed.matchedTargetLabel()).isEqualTo("Main Corridor C2");
        assertThat(parsed.proposedConfig().targetEdgeUid()).isEqualTo("edge-c2");
        assertThat(parsed.proposedConfig().targetEdgeId()).isEqualTo(12L);
        assertThat(parsed.proposedConfig().disruptionStartSec()).isEqualTo(60);
        assertThat(parsed.proposedConfig().agentCount()).isEqualTo(600);
        assertThat(parsed.proposedConfig().durationSeconds()).isEqualTo(480);
        assertThat(parsed.proposedConfig().randomSeed()).isEqualTo(99L);
        assertThat(parsed.confidence()).isEqualTo("HIGH");
    }

    @Test
    void parsesStageCrowdingAndArrivalRushAndFlagsAmbiguousTargetAsAutoSelected() {
        ScenarioParserService.ParsedScenarioResult rush = parser.parse(
                "Test a 2.5x arrival rush surge with 450 visitors",
                sampleLayout(), List.of(), List.of(), null);
        assertThat(rush.parsedScenarioType()).isEqualTo(SimulationScenarioType.ARRIVAL_RUSH);
        assertThat(rush.proposedConfig().agentCount()).isEqualTo(450);

        ScenarioParserService.ParsedScenarioResult stage = parser.parse(
                "Simulate heavy stage crowding near the main performance",
                sampleLayout(), List.of(), List.of(), null);
        assertThat(stage.parsedScenarioType()).isEqualTo(SimulationScenarioType.STAGE_CROWDING);

        ScenarioParserService.ParsedScenarioResult ambiguous = parser.parse(
                "What if an unspecified exit closes?",
                sampleLayout(), List.of(), List.of(), null);
        assertThat(ambiguous.parsedScenarioType()).isEqualTo(SimulationScenarioType.CLOSED_EXIT);
        assertThat(ambiguous.targetAutoSelected()).isTrue();
        assertThat(ambiguous.confidence()).isEqualTo("LOW");
        assertThat(ambiguous.parsingNotes()).anyMatch(n -> n.contains("pre-selected"));
    }
}
