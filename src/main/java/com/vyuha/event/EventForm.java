package com.vyuha.event;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Form backing object for creating and updating events, including incomplete Draft saves.
 */
public class EventForm {

    @NotBlank(message = "Event name is required.")
    @Size(max = 180, message = "Event name must be at most 180 characters.")
    private String name;

    @NotNull(message = "Event category is required.")
    private EventCategory category = EventCategory.COLLEGE_FESTIVAL;

    @NotNull(message = "Event status is required.")
    private EventStatus status = EventStatus.PLANNING;

    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime startDateTime;

    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime endDateTime;

    @Size(max = 2000, message = "Schedule timeline must be at most 2000 characters.")
    private String scheduleTimeline;

    @Min(value = 1, message = "Expected attendance must be at least 1.")
    @Max(value = 5000000, message = "Expected attendance cannot exceed 5,000,000.")
    private Integer expectedAttendance;

    @Size(max = 200, message = "Location name must be at most 200 characters.")
    private String locationName;

    @Size(max = 500, message = "Location address must be at most 500 characters.")
    private String locationAddress;

    @Size(max = 2000, message = "Description must be at most 2000 characters.")
    private String description;

    private Long assignedReviewerId;

    public static EventForm fromEntity(Event event) {
        EventForm form = new EventForm();
        form.setName(event.getName());
        form.setCategory(event.getCategory());
        form.setStatus(event.getStatus());
        form.setStartDateTime(event.getStartDateTime());
        form.setEndDateTime(event.getEndDateTime());
        form.setScheduleTimeline(event.getScheduleTimeline());
        form.setExpectedAttendance(event.getExpectedAttendance());
        form.setLocationName(event.getLocationName());
        form.setLocationAddress(event.getLocationAddress());
        form.setDescription(event.getDescription());
        if (event.getAssignedReviewer() != null) {
            form.setAssignedReviewerId(event.getAssignedReviewer().getId());
        }
        return form;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public EventCategory getCategory() {
        return category;
    }

    public void setCategory(EventCategory category) {
        this.category = category;
    }

    public EventStatus getStatus() {
        return status;
    }

    public void setStatus(EventStatus status) {
        this.status = status;
    }

    public LocalDateTime getStartDateTime() {
        return startDateTime;
    }

    public void setStartDateTime(LocalDateTime startDateTime) {
        this.startDateTime = startDateTime;
    }

    public LocalDateTime getEndDateTime() {
        return endDateTime;
    }

    public void setEndDateTime(LocalDateTime endDateTime) {
        this.endDateTime = endDateTime;
    }

    public String getScheduleTimeline() {
        return scheduleTimeline;
    }

    public void setScheduleTimeline(String scheduleTimeline) {
        this.scheduleTimeline = scheduleTimeline;
    }

    public Integer getExpectedAttendance() {
        return expectedAttendance;
    }

    public void setExpectedAttendance(Integer expectedAttendance) {
        this.expectedAttendance = expectedAttendance;
    }

    public String getLocationName() {
        return locationName;
    }

    public void setLocationName(String locationName) {
        this.locationName = locationName;
    }

    public String getLocationAddress() {
        return locationAddress;
    }

    public void setLocationAddress(String locationAddress) {
        this.locationAddress = locationAddress;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getAssignedReviewerId() {
        return assignedReviewerId;
    }

    public void setAssignedReviewerId(Long assignedReviewerId) {
        this.assignedReviewerId = assignedReviewerId;
    }
}
