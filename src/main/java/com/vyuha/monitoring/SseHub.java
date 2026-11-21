package com.vyuha.monitoring;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Manages per-event Server-Sent Events (SSE) streams for live monitoring updates and alerts.
 */
@Component
public class SseHub {

    private static final long DEFAULT_TIMEOUT_MS = 300_000L;

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long eventId) {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT_MS);
        CopyOnWriteArrayList<SseEmitter> list =
                subscribers.computeIfAbsent(eventId, id -> new CopyOnWriteArrayList<>());
        list.add(emitter);

        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(ex -> remove.run());

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("eventId", eventId, "connectedAt", Instant.now().toString())));
        } catch (IOException | IllegalStateException ex) {
            list.remove(emitter);
        }
        return emitter;
    }

    public void publish(Long eventId, String eventName, Object payload) {
        List<SseEmitter> list = subscribers.get(eventId);
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(payload));
            } catch (IOException | IllegalStateException ex) {
                list.remove(emitter);
            }
        }
    }

    public int activeSubscriberCount(Long eventId) {
        List<SseEmitter> list = subscribers.get(eventId);
        return list != null ? list.size() : 0;
    }
}
