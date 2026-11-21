package com.vyuha.event;

import com.vyuha.auth.AppUser;
import com.vyuha.auth.UserRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages the append-only human safety review log and organizer responses.
 */
@Service
public class SafetyReviewService {

    private final SafetyReviewRecordRepository reviewRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;

    public SafetyReviewService(SafetyReviewRecordRepository reviewRepository,
                               EventRepository eventRepository,
                               UserRepository userRepository) {
        this.reviewRepository = reviewRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<SafetyReviewRecord> getReviewsForEvent(Long eventId) {
        return reviewRepository.findByEventIdWithUsers(eventId);
    }

    @Transactional(readOnly = true)
    public Map<Long, SafetyReviewStatus> getLatestReviewStatuses(List<Event> events) {
        Map<Long, SafetyReviewStatus> latestByEvent = new HashMap<>();
        for (Event event : events) {
            List<SafetyReviewRecord> records = reviewRepository.findByEventIdWithUsers(event.getId());
            if (!records.isEmpty()) {
                latestByEvent.put(event.getId(), records.get(0).getReviewStatus());
            }
        }
        return latestByEvent;
    }

    @Transactional
    public SafetyReviewRecord issueReview(Long eventId,
                                          Long reviewerUserId,
                                          SafetyReviewStatus reviewStatus,
                                          String findingsSummary) {
        if (reviewStatus == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Review status is required.");
        }
        if (findingsSummary == null || findingsSummary.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Findings summary is required.");
        }
        if (findingsSummary.trim().length() > 3000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Findings summary must be at most 3000 characters.");
        }

        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found."));
        AppUser reviewer = userRepository.findById(reviewerUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Reviewer account not found."));

        SafetyReviewRecord record = new SafetyReviewRecord();
        record.setEvent(event);
        record.setReviewer(reviewer);
        record.setReviewStatus(reviewStatus);
        record.setFindingsSummary(findingsSummary.trim());
        record.setReviewedAt(Instant.now());
        return reviewRepository.save(record);
    }

    @Transactional
    public SafetyReviewRecord addOrganizerResponse(Long eventId,
                                                   Long reviewId,
                                                   Long responderUserId,
                                                   String organizerResponse) {
        if (organizerResponse == null || organizerResponse.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Response text is required.");
        }
        if (organizerResponse.trim().length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Response must be at most 2000 characters.");
        }

        SafetyReviewRecord record = reviewRepository.findByIdAndEventIdWithUsers(reviewId, eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Safety review record not found."));
        AppUser responder = userRepository.findById(responderUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User account not found."));

        record.setOrganizerResponse(organizerResponse.trim());
        record.setRespondedBy(responder);
        record.setRespondedAt(Instant.now());
        return reviewRepository.save(record);
    }
}
