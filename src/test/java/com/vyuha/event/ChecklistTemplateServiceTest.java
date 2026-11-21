package com.vyuha.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ChecklistTemplateServiceTest {

    private final ChecklistTemplateService service = new ChecklistTemplateService();

    @Test
    void checklistsDifferMeaningfullyAcrossCategories() {
        Event festival = new Event();
        festival.setCategory(EventCategory.COLLEGE_FESTIVAL);

        Event religious = new Event();
        religious.setCategory(EventCategory.RELIGIOUS_GATHERING);

        Event concert = new Event();
        concert.setCategory(EventCategory.CONCERT);

        Set<String> festKeys = service.generateInitialItems(festival).stream()
                .map(EventChecklistItem::getItemKey)
                .collect(Collectors.toSet());
        Set<String> relKeys = service.generateInitialItems(religious).stream()
                .map(EventChecklistItem::getItemKey)
                .collect(Collectors.toSet());
        Set<String> concertKeys = service.generateInitialItems(concert).stream()
                .map(EventChecklistItem::getItemKey)
                .collect(Collectors.toSet());

        assertThat(festKeys).contains("BASE_PERIMETER_EXITS", "FEST_STAGE_BARRIER", "FEST_FOOD_UTILITY");
        assertThat(relKeys).contains("BASE_PERIMETER_EXITS", "REL_QUEUE_SEPARATION", "REL_FOOTWEAR_PRASAD");
        assertThat(concertKeys).contains("BASE_PERIMETER_EXITS", "CONCERT_PIT_EGRESS", "CONCERT_ENCORE_DISPERSAL");

        assertThat(festKeys).doesNotContain("REL_QUEUE_SEPARATION", "CONCERT_PIT_EGRESS");
        assertThat(relKeys).doesNotContain("FEST_ID_PASS_FUNNEL", "CONCERT_RIGGING_BUFFER");

        for (EventCategory category : EventCategory.values()) {
            Event ev = new Event();
            ev.setCategory(category);
            List<EventChecklistItem> items = service.generateInitialItems(ev);
            assertThat(items).hasSizeGreaterThanOrEqualTo(7);
            assertThat(items).allSatisfy(item -> {
                assertThat(item.getStatus()).isEqualTo(ChecklistItemStatus.NOT_STARTED);
                assertThat(item.getSortOrder()).isPositive();
            });
        }
    }

    @Test
    void computeProgressExcludesNotApplicableAndCountsSignOffs() {
        assertThat(service.computeProgress(List.of())).isEqualTo(new ChecklistProgress(0, 0, 0, 0, 0, 0, 0, 0));

        EventChecklistItem doneSigned = new EventChecklistItem();
        doneSigned.setStatus(ChecklistItemStatus.COMPLETE);
        doneSigned.setReviewerSignedOff(true);

        EventChecklistItem inProgress = new EventChecklistItem();
        inProgress.setStatus(ChecklistItemStatus.IN_PROGRESS);

        EventChecklistItem notApplicable = new EventChecklistItem();
        notApplicable.setStatus(ChecklistItemStatus.NOT_APPLICABLE);

        ChecklistProgress progress = service.computeProgress(List.of(doneSigned, inProgress, notApplicable));
        assertThat(progress.totalItems()).isEqualTo(3);
        assertThat(progress.applicableItems()).isEqualTo(2);
        assertThat(progress.completedCount()).isEqualTo(1);
        assertThat(progress.inProgressCount()).isEqualTo(1);
        assertThat(progress.notApplicableCount()).isEqualTo(1);
        assertThat(progress.signedOffCount()).isEqualTo(1);
        assertThat(progress.completionPct()).isEqualTo(50);

        EventChecklistItem onlyNa = new EventChecklistItem();
        onlyNa.setStatus(ChecklistItemStatus.NOT_APPLICABLE);
        assertThat(service.computeProgress(List.of(onlyNa)).completionPct()).isEqualTo(100);
    }
}
