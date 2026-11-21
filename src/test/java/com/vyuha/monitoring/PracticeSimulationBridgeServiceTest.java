package com.vyuha.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.vyuha.common.GeometryUtil;
import com.vyuha.grid.FunctionalZone;
import com.vyuha.grid.ZoneType;
import com.vyuha.simulation.SimulationDtos;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeSimulationBridgeServiceTest {

    private final PracticeSimulationBridgeService bridge = new PracticeSimulationBridgeService(new GeometryUtil());

    @Test
    void mapsSimulationFrameAgentsIntoEnclosingZones() {
        FunctionalZone z1 = new FunctionalZone();
        ReflectionTestUtils.setField(z1, "id", 1L);
        z1.setZoneCode("AUD-1");
        z1.setName("Main Audience");
        z1.setZoneType(ZoneType.AUDIENCE);
        z1.setColorHex(ZoneType.AUDIENCE.getFillColor());
        z1.setPolygonWkt("POLYGON ((10 10, 40 10, 40 40, 10 40, 10 10))");

        FunctionalZone z2 = new FunctionalZone();
        ReflectionTestUtils.setField(z2, "id", 2L);
        z2.setZoneCode("QUE-1");
        z2.setName("Queue Pen");
        z2.setZoneType(ZoneType.QUEUE);
        z2.setColorHex(ZoneType.QUEUE.getFillColor());
        z2.setPolygonWkt("POLYGON ((50 10, 70 10, 70 30, 50 30, 50 10))");

        SimulationDtos.SimulationFrame frame = new SimulationDtos.SimulationFrame(
                30.0,
                5,
                1,
                0,
                1,
                0.5,
                List.of(
                        new double[]{1, 20.0, 20.0, 0}, // inside AUD-1
                        new double[]{2, 25.0, 25.0, 2}, // inside AUD-1 (congested)
                        new double[]{3, 60.0, 20.0, 1}, // inside QUE-1 (queuing)
                        new double[]{4, 95.0, 95.0, 0}, // outside all zones -> unmapped
                        new double[]{5, 15.0, 15.0, 3}  // state 3 (exited) -> skipped
                ),
                List.of()
        );

        PracticeSimulationBridgeService.MappedFrameCounts result =
                bridge.mapFrameAgentsToZoneCounts(List.of(z1, z2), frame);

        assertThat(result.countsByZoneId()).containsEntry(1L, 2);
        assertThat(result.countsByZoneId()).containsEntry(2L, 1);
        assertThat(result.mappedAgentsCount()).isEqualTo(3);
        assertThat(result.unmappedAgentsCount()).isEqualTo(1);
    }
}
