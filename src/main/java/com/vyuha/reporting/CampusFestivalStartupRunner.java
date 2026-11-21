package com.vyuha.reporting;

import com.vyuha.event.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Seeds the flagship "TechnoVyuha Annual Campus Festival" walkthrough idempotently on startup
 * when vyuha.seed.campus-festival-enabled=true.
 */
@Component
@ConditionalOnProperty(name = "vyuha.seed.campus-festival-enabled", havingValue = "true", matchIfMissing = true)
public class CampusFestivalStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CampusFestivalStartupRunner.class);

    private final CampusFestivalSeedService seedService;

    public CampusFestivalStartupRunner(CampusFestivalSeedService seedService) {
        this.seedService = seedService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Event demo = seedService.seedIfMissing();
        log.info("Flagship Campus Festival demo ready (eventId={}, name='{}').", demo.getId(), demo.getName());
    }
}
