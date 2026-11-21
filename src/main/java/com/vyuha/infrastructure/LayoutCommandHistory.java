package com.vyuha.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vyuha.auth.AppUser;
import com.vyuha.venue.VenuePlan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Persisted command-pattern revision manager providing server-backed Undo/Redo
 * across infrastructure layout mutations.
 */
@Service
public class LayoutCommandHistory {

    public static final int MAX_REVISIONS = 50;
    private static final int DISPLAY_HISTORY_LIMIT = 15;
    private static final TypeReference<List<InfrastructureDtos.ElementSnapshot>> SNAPSHOT_LIST_TYPE =
            new TypeReference<>() {
            };

    private final LayoutRevisionRepository revisionRepository;
    private final ObjectMapper objectMapper;

    public LayoutCommandHistory(LayoutRevisionRepository revisionRepository, ObjectMapper objectMapper) {
        this.revisionRepository = revisionRepository;
        this.objectMapper = objectMapper;
    }

    public void ensureBaselineRevision(VenuePlan plan,
                                       List<InfrastructureElement> currentElements,
                                       AppUser actor) {
        List<LayoutRevision> existing = revisionRepository.findByVenuePlanIdOrderByRevisionNumberAsc(plan.getId());
        if (!existing.isEmpty()) {
            return;
        }
        LayoutRevision baseline = new LayoutRevision();
        baseline.setVenuePlan(plan);
        baseline.setRevisionNumber(0);
        baseline.setCommandType("INITIAL");
        baseline.setSummary("Initial infrastructure state");
        baseline.setSnapshotJson(serializeSnapshot(toSnapshots(currentElements)));
        baseline.setCurrent(true);
        baseline.setCreatedBy(actor);
        revisionRepository.saveAndFlush(baseline);
    }

    public void recordRevision(VenuePlan plan,
                               String commandType,
                               String summary,
                               List<InfrastructureElement> resultingElements,
                               AppUser actor) {
        ensureBaselineRevision(plan, resultingElements, actor);
        List<LayoutRevision> ordered = revisionRepository.findByVenuePlanIdOrderByRevisionNumberAsc(plan.getId());
        LayoutRevision current = ordered.stream()
                .filter(LayoutRevision::isCurrent)
                .findFirst()
                .orElse(ordered.get(ordered.size() - 1));

        int currentRevNum = current.getRevisionNumber();
        List<LayoutRevision> tail = ordered.stream()
                .filter(r -> r.getRevisionNumber() > currentRevNum)
                .toList();
        if (!tail.isEmpty()) {
            revisionRepository.deleteAll(tail);
            revisionRepository.flush();
        }

        current.setCurrent(false);
        revisionRepository.saveAndFlush(current);

        LayoutRevision next = new LayoutRevision();
        next.setVenuePlan(plan);
        next.setRevisionNumber(currentRevNum + 1);
        next.setCommandType(commandType != null && !commandType.isBlank() ? commandType : "UPDATE");
        next.setSummary(truncateSummary(summary));
        next.setSnapshotJson(serializeSnapshot(toSnapshots(resultingElements)));
        next.setCurrent(true);
        next.setCreatedBy(actor);
        revisionRepository.saveAndFlush(next);

        pruneIfNeeded(plan.getId());
    }

    public UndoRedoOutcome undo(VenuePlan plan) {
        List<LayoutRevision> ordered = revisionRepository.findByVenuePlanIdOrderByRevisionNumberAsc(plan.getId());
        if (ordered.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to undo.");
        }
        int currentIdx = findCurrentIndex(ordered);
        if (currentIdx <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to undo.");
        }

        LayoutRevision current = ordered.get(currentIdx);
        LayoutRevision target = ordered.get(currentIdx - 1);
        current.setCurrent(false);
        target.setCurrent(true);
        revisionRepository.saveAllAndFlush(List.of(current, target));

        return new UndoRedoOutcome(
                current.getSummary(),
                target.getRevisionNumber(),
                deserializeSnapshot(target.getSnapshotJson())
        );
    }

    public UndoRedoOutcome redo(VenuePlan plan) {
        List<LayoutRevision> ordered = revisionRepository.findByVenuePlanIdOrderByRevisionNumberAsc(plan.getId());
        if (ordered.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to redo.");
        }
        int currentIdx = findCurrentIndex(ordered);
        if (currentIdx < 0 || currentIdx >= ordered.size() - 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to redo.");
        }

        LayoutRevision current = ordered.get(currentIdx);
        LayoutRevision target = ordered.get(currentIdx + 1);
        current.setCurrent(false);
        target.setCurrent(true);
        revisionRepository.saveAllAndFlush(List.of(current, target));

        return new UndoRedoOutcome(
                target.getSummary(),
                target.getRevisionNumber(),
                deserializeSnapshot(target.getSnapshotJson())
        );
    }

    public InfrastructureDtos.HistoryStateDto getHistoryState(Long venuePlanId) {
        if (venuePlanId == null) {
            return new InfrastructureDtos.HistoryStateDto(false, false, 0, 0, null, null, List.of());
        }
        List<LayoutRevision> ordered = revisionRepository.findByVenuePlanIdOrderByRevisionNumberAsc(venuePlanId);
        if (ordered.isEmpty()) {
            return new InfrastructureDtos.HistoryStateDto(false, false, 0, 0, null, null, List.of());
        }

        int currentIdx = findCurrentIndex(ordered);
        LayoutRevision current = ordered.get(currentIdx);
        boolean canUndo = currentIdx > 0;
        boolean canRedo = currentIdx < ordered.size() - 1;
        String undoLabel = canUndo ? current.getSummary() : null;
        String redoLabel = canRedo ? ordered.get(currentIdx + 1).getSummary() : null;

        List<InfrastructureDtos.RevisionSummaryDto> recent = ordered.stream()
                .sorted(Comparator.comparingInt(LayoutRevision::getRevisionNumber).reversed())
                .limit(DISPLAY_HISTORY_LIMIT)
                .map(rev -> new InfrastructureDtos.RevisionSummaryDto(
                        rev.getRevisionNumber(),
                        rev.getCommandType(),
                        rev.getSummary(),
                        rev.isCurrent(),
                        rev.getCreatedBy() != null ? rev.getCreatedBy().getFullName() : "System",
                        rev.getCreatedAt()
                ))
                .toList();

        return new InfrastructureDtos.HistoryStateDto(
                canUndo,
                canRedo,
                current.getRevisionNumber(),
                ordered.size(),
                undoLabel,
                redoLabel,
                recent
        );
    }

    private void pruneIfNeeded(Long venuePlanId) {
        List<LayoutRevision> ordered = revisionRepository.findByVenuePlanIdOrderByRevisionNumberAsc(venuePlanId);
        if (ordered.size() <= MAX_REVISIONS) {
            return;
        }
        int excess = ordered.size() - MAX_REVISIONS;
        List<LayoutRevision> toDelete = new ArrayList<>();
        for (LayoutRevision rev : ordered) {
            if (toDelete.size() >= excess) {
                break;
            }
            if (!rev.isCurrent()) {
                toDelete.add(rev);
            }
        }
        if (!toDelete.isEmpty()) {
            revisionRepository.deleteAll(toDelete);
            revisionRepository.flush();
        }
    }

    private int findCurrentIndex(List<LayoutRevision> ordered) {
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).isCurrent()) {
                return i;
            }
        }
        return ordered.size() - 1;
    }

    private List<InfrastructureDtos.ElementSnapshot> toSnapshots(List<InfrastructureElement> elements) {
        if (elements == null || elements.isEmpty()) {
            return List.of();
        }
        return elements.stream()
                .map(el -> new InfrastructureDtos.ElementSnapshot(
                        el.getElementUid(),
                        el.getElementType(),
                        el.getLabel(),
                        el.getCenterX(),
                        el.getCenterY(),
                        el.getWidthM(),
                        el.getHeightM(),
                        el.getRotationDeg(),
                        el.getThroughputPersonsPerMin(),
                        el.isActive(),
                        el.getMetadataJson(),
                        el.getSortOrder()
                ))
                .toList();
    }

    private String serializeSnapshot(List<InfrastructureDtos.ElementSnapshot> snapshots) {
        try {
            return objectMapper.writeValueAsString(snapshots);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize layout revision snapshot.", ex);
        }
    }

    private List<InfrastructureDtos.ElementSnapshot> deserializeSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<InfrastructureDtos.ElementSnapshot> parsed = objectMapper.readValue(json, SNAPSHOT_LIST_TYPE);
            return parsed != null ? List.copyOf(parsed) : List.of();
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to restore layout revision snapshot.", ex);
        }
    }

    private String truncateSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return "Updated infrastructure layout";
        }
        String trimmed = summary.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    public record UndoRedoOutcome(
            String actionSummary,
            int restoredRevisionNumber,
            List<InfrastructureDtos.ElementSnapshot> elements
    ) {
    }
}
