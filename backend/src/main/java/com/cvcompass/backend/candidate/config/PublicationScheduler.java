package com.cvcompass.backend.candidate.config;

import com.cvcompass.backend.candidate.service.PublicationService;
import com.cvcompass.backend.candidate.service.PublicationSweep;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PublicationProperties.class)
public class PublicationScheduler {
    private final PublicationProperties properties;
    private final PublicationSweep sweep;
    private final PublicationService publication;
    private final ExecutorService executor;

    public PublicationScheduler(
            PublicationProperties properties,
            PublicationSweep sweep,
            PublicationService publication) {
        this.properties = properties;
        this.sweep = sweep;
        this.publication = publication;
        executor = Executors.newFixedThreadPool(properties.getConcurrency());
    }

    @Scheduled(fixedDelayString = "${cv.candidate.publication.delay:10s}")
    public void poll() {
        if (!properties.isEnabled()) return;
        var ids = sweep.reserve(properties.getPageSize());
        var tasks = ids.stream().map(id -> executor.submit(() -> publication.publish(id))).toList();
        for (var task : tasks)
            try {
                task.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException ex) {
                LoggerFactory.getLogger(getClass())
                        .warn("event=PUBLICATION_RETRY errorCode=PUBLICATION_FAILED");
            }
    }

    @PreDestroy
    public void close() {
        executor.close();
    }
}
