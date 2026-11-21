package com.vyuha.event;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Generates category-tailored safety planning checklists and computes deterministic progress metrics.
 */
@Service
public class ChecklistTemplateService {

    public record TemplateEntry(String key, String section, String title, String guidanceNote) {
    }

    /**
     * Builds initial persistent checklist items for a newly created event based on its category.
     */
    public List<EventChecklistItem> generateInitialItems(Event event) {
        List<TemplateEntry> templates = new ArrayList<>(baselineEntries());
        templates.addAll(categorySpecificEntries(event.getCategory()));

        List<EventChecklistItem> items = new ArrayList<>(templates.size());
        int order = 1;
        for (TemplateEntry entry : templates) {
            EventChecklistItem item = new EventChecklistItem();
            item.setEvent(event);
            item.setItemKey(entry.key());
            item.setSectionName(entry.section());
            item.setTitle(entry.title());
            item.setGuidanceNote(entry.guidanceNote());
            item.setStatus(ChecklistItemStatus.NOT_STARTED);
            item.setSortOrder(order++);
            items.add(item);
        }
        return items;
    }

    /**
     * Computes exact completion metrics excluding NOT_APPLICABLE items from the percentage denominator.
     */
    public ChecklistProgress computeProgress(List<EventChecklistItem> items) {
        if (items == null || items.isEmpty()) {
            return new ChecklistProgress(0, 0, 0, 0, 0, 0, 0, 0);
        }

        int completed = 0;
        int inProgress = 0;
        int notStarted = 0;
        int notApplicable = 0;
        int signedOff = 0;

        for (EventChecklistItem item : items) {
            if (item.isReviewerSignedOff()) {
                signedOff++;
            }
            switch (item.getStatus()) {
                case COMPLETE -> completed++;
                case IN_PROGRESS -> inProgress++;
                case NOT_STARTED -> notStarted++;
                case NOT_APPLICABLE -> notApplicable++;
            }
        }

        int total = items.size();
        int applicable = total - notApplicable;
        int completionPct = applicable == 0 ? 100 : (int) Math.round((completed * 100.0) / applicable);

        return new ChecklistProgress(
                total,
                applicable,
                completed,
                inProgress,
                notStarted,
                notApplicable,
                signedOff,
                completionPct
        );
    }

    private List<TemplateEntry> baselineEntries() {
        return List.of(
                new TemplateEntry(
                        "BASE_PERIMETER_EXITS",
                        "Core Site & Egress",
                        "Verify outer boundary, entry gates, and unobstructed exit widths",
                        "Confirm all planned exits open outward or clear of barricades and lead to open dispersal space."
                ),
                new TemplateEntry(
                        "BASE_EMERGENCY_LANE",
                        "Core Site & Egress",
                        "Maintain dedicated emergency vehicle access corridor (>= 3.5 m)",
                        "Keep ambulance and fire routes strictly clear of stalls, cables, and queuing lanes."
                ),
                new TemplateEntry(
                        "BASE_MEDICAL_WATER",
                        "Welfare & Operations",
                        "Confirm first-aid post placement, hydration points, and signage",
                        "Verify medical post sits adjacent to an unobstructed emergency egress or ambulance lane."
                ),
                new TemplateEntry(
                        "BASE_PA_COMMS",
                        "Welfare & Operations",
                        "Test public address coverage and inter-agency radio/contact roster",
                        "Ensure announcements are audible in holding queues, stage zones, and exit corridors."
                )
        );
    }

    private List<TemplateEntry> categorySpecificEntries(EventCategory category) {
        if (category == null) {
            return List.of();
        }
        return switch (category) {
            case COLLEGE_FESTIVAL -> List.of(
                    new TemplateEntry(
                            "FEST_STAGE_BARRIER",
                            "Campus Festival Controls",
                            "Verify main stage front-of-house barrier clearance and side relief lanes",
                            "Maintain buffer corridor between front barricade and crowd viewing zone for medical extraction."
                    ),
                    new TemplateEntry(
                            "FEST_ID_PASS_FUNNEL",
                            "Campus Festival Controls",
                            "Separate student ID / pass verification lanes from exit corridors",
                            "Prevent entry queue spillover from blocking adjacent walkways or hostel/campus roads."
                    ),
                    new TemplateEntry(
                            "FEST_FOOD_UTILITY",
                            "Campus Festival Controls",
                            "Audit food-court LPG cylinder, generator, and electrical utility spacing",
                            "Keep cooking/generator enclosures isolated from high-density pedestrian paths."
                    ),
                    new TemplateEntry(
                            "FEST_EVENING_SURGE",
                            "Campus Festival Controls",
                            "Plan headliner pre-show surge and simultaneous post-show dispersal",
                            "Stagger program transitions or widen central quadrangle promenade before peak night rush."
                    )
            );
            case RELIGIOUS_GATHERING -> List.of(
                    new TemplateEntry(
                            "REL_QUEUE_SEPARATION",
                            "Pilgrim Flow & Queuing",
                            "Verify continuous zig-zag queue-lane separation and sturdy railings",
                            "Avoid sharp 180-degree pinch turns without relief gates along long darshan/sanctum queues."
                    ),
                    new TemplateEntry(
                            "REL_FOOTWEAR_PRASAD",
                            "Pilgrim Flow & Queuing",
                            "Inspect footwear-drop and prasad/offering counter bottleneck clearance",
                            "Locate footwear and distribution counters outside primary ingress/egress walkways."
                    ),
                    new TemplateEntry(
                            "REL_ACCESSIBLE_EGRESS",
                            "Pilgrim Flow & Queuing",
                            "Establish dedicated step-free priority egress for elderly and families",
                            "Provide shaded resting bays and clear lateral exits away from main tidal crowd flow."
                    ),
                    new TemplateEntry(
                            "REL_TIDAL_HOLDING",
                            "Pilgrim Flow & Queuing",
                            "Configure metered holding pens upstream of sanctum or ritual choke points",
                            "Release batches in controlled intervals matched to downstream holding capacity."
                    )
            );
            case CONCERT -> List.of(
                    new TemplateEntry(
                            "CONCERT_PIT_EGRESS",
                            "Stage & Pit Safety",
                            "Audit front-of-stage crush barrier load rating and pit relief exits",
                            "Ensure front pit has dedicated lateral medical and security extraction gates."
                    ),
                    new TemplateEntry(
                            "CONCERT_BAG_SCREENING",
                            "Stage & Pit Safety",
                            "Size bag-check / ticket-scan apron ahead of primary entrance doors",
                            "Provide sufficient queue holding depth so entry rush does not spill onto public roadways."
                    ),
                    new TemplateEntry(
                            "CONCERT_ENCORE_DISPERSAL",
                            "Stage & Pit Safety",
                            "Verify all exit gates unlock early for simultaneous post-encore surge",
                            "Separate vehicle pick-up zones from pedestrian dispersal corridors."
                    ),
                    new TemplateEntry(
                            "CONCERT_RIGGING_BUFFER",
                            "Stage & Pit Safety",
                            "Enforce exclusion perimeter around speaker towers, pyrotechnics, and power",
                            "Mark rigging guy-wires and cable ramps visibly to prevent trip hazards in low light."
                    )
            );
            case PUBLIC_RALLY -> List.of(
                    new TemplateEntry(
                            "RALLY_DAIS_PERIMETER",
                            "Assembly & Dispersal",
                            "Separate VIP dais security buffer from general attendee circulation",
                            "Ensure security sterile zones do not sever primary emergency lateral routes."
                    ),
                    new TemplateEntry(
                            "RALLY_CONTRA_FLOW",
                            "Assembly & Dispersal",
                            "Prevent contra-flow crossings between arriving convoys and departing crowds",
                            "Designate directional one-way signage and physical dividers at major intersections."
                    ),
                    new TemplateEntry(
                            "RALLY_HEAT_HYDRATION",
                            "Assembly & Dispersal",
                            "Distribute shaded hydration stations evenly across open ground sectors",
                            "Avoid clustering water tankers directly inside narrow walkway throats."
                    )
            );
            case SPORTS_MATCH -> List.of(
                    new TemplateEntry(
                            "SPORT_TURNSTILE_APRON",
                            "Stadium Ingress & Egress",
                            "Check turnstile throughput against pre-kickoff 30-minute arrival peak",
                            "Maintain wide unobstructed approach aprons outside ticket-check barriers."
                    ),
                    new TemplateEntry(
                            "SPORT_CONCOURSE_WIDTH",
                            "Stadium Ingress & Egress",
                            "Verify concourse stairs, vomitories, and ramp egress capacities",
                            "Eliminate vendor kiosks or signage encroaching on stairwell landing widths."
                    ),
                    new TemplateEntry(
                            "SPORT_SECTOR_SEPARATION",
                            "Stadium Ingress & Egress",
                            "Confirm segregated fan concourses and non-intersecting exit streams",
                            "Keep opposing gate exits separated until dispersing into wide external plazas."
                    )
            );
            case PUBLIC_CELEBRATION -> List.of(
                    new TemplateEntry(
                            "CELEB_NIGHT_LIGHTING",
                            "Civic Celebration Controls",
                            "Confirm backup tower lighting along all exit corridors and steps",
                            "Verify emergency lighting batteries and generator failover for dusk/night dispersal."
                    ),
                    new TemplateEntry(
                            "CELEB_PARADE_CLEARANCE",
                            "Civic Celebration Controls",
                            "Protect procession route width and spectator setback barricades",
                            "Maintain ambulance crossing gaps at scheduled intervals along parade routes."
                    ),
                    new TemplateEntry(
                            "CELEB_PYRO_FALLOUT",
                            "Civic Celebration Controls",
                            "Enforce fireworks/pyrotechnics fallout exclusion perimeter",
                            "Exclude all public standing zones and dry brush within statutory fallout radius."
                    )
            );
            case EXHIBITION -> List.of(
                    new TemplateEntry(
                            "EXHIB_AISLE_CROSSING",
                            "Hall & Pavilion Layout",
                            "Verify exhibition aisle widths and eliminate dead-end pavilion pockets",
                            "Provide regular cross-aisles linking parallel exhibit rows to perimeter exits."
                    ),
                    new TemplateEntry(
                            "EXHIB_LOAD_DOCK",
                            "Hall & Pavilion Layout",
                            "Enforce cut-off time separating freight/forklift loading from public entry",
                            "Lock service dock doors or post dedicated marshals prior to visitor admission."
                    ),
                    new TemplateEntry(
                            "EXHIB_BOOTH_ENCROACHMENT",
                            "Hall & Pavilion Layout",
                            "Audit exhibitor booth spillover, demo queues, and floor cable ramps",
                            "Keep demonstration audience spills clear of marked primary egress aisles."
                    )
            );
        };
    }
}
