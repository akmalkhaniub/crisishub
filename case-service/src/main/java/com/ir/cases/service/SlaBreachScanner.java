package com.ir.cases.service;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Lives in case-service because case-service owns case state; ShedLock → one instance runs it. */
@Slf4j
@Component
public class SlaBreachScanner {

    private final CaseService cases;

    public SlaBreachScanner(CaseService cases) {
        this.cases = cases;
    }

    @Scheduled(fixedDelayString = "${ir.sla.scan-ms:60000}")
    @SchedulerLock(name = "sla-breach-scan", lockAtMostFor = "PT5M")
    public void scan() {
        int n = cases.markSlaBreaches();
        if (n > 0) {
            log.info("Marked {} case(s) as SLA-breached", n);
        }
    }
}
