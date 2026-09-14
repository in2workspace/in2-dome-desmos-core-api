package es.in2.desmos.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.inflators.ScorpioInflator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static es.in2.desmos.domain.utils.ApplicationConstants.ROOT_OBJECTS_LIST;

/**
 * Empties a Scorpio broker of every entity of every replicable type.
 * <p>
 * Exists because {@code ContainerManager}'s brokers are JVM-wide statics shared by every
 * integration test class: entities one class creates outlive it and are still there when the next
 * class boots. That is not inert leftover state -- a fresh context's {@code ApplicationRunner}
 * startup sync asks the broker what it holds and writes a PUBLISHED/PRODUCER audit record for every
 * entity lacking one (see {@code P2PDataSyncJobImpl.createLocalMvEntitiesByType} and
 * {@code AuditRecordServiceImpl.buildAndSaveAuditRecordFromUnregisteredOrOutdatedEntity}), which is
 * how one class's entities turned into rows another class's assertions tripped over.
 * <p>
 * Individual classes already delete the specific ids they seeded. This is the wholesale version,
 * run for every class by {@link ScorpioResetExtension} so isolation does not depend on each test
 * author remembering to clean up.
 */
public final class ScorpioReset {

    private static final Logger log = LoggerFactory.getLogger(ScorpioReset.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /**
     * Kept at Scorpio's own default {@code scorpio.entity.max-limit}. Going over it is not a
     * truncated page but a 403 (same constraint documented on {@code broker.pageSize} in
     * application.yml), so this must never be raised without raising the broker's limit too.
     */
    private static final int PAGE_LIMIT = 1000;

    /** Ids per delete call. Well under any batch-operation cap, and keeps one failure small. */
    private static final int DELETE_CHUNK = 500;

    private ScorpioReset() {
    }

    /**
     * Deletes every entity of every type in {@code ROOT_OBJECTS_LIST} from the given broker, then
     * verifies the broker really is empty.
     *
     * @throws IllegalStateException if anything survives -- a partial wipe silently reintroduces
     *                               exactly the cross-class bleed this class exists to prevent,
     *                               and is far harder to diagnose downstream than a failure here.
     */
    public static void wipe(String brokerBaseUri) {
        List<String> ids = allEntityIds(brokerBaseUri);
        if (ids.isEmpty()) {
            return;
        }

        for (int from = 0; from < ids.size(); from += DELETE_CHUNK) {
            List<String> chunk = ids.subList(from, Math.min(from + DELETE_CHUNK, ids.size()));
            ScorpioInflator.deleteInitialEntitiesFromContextBroker(brokerBaseUri, chunk);
        }
        log.info("Wiped {} entities from broker {}", ids.size(), brokerBaseUri);

        List<String> survivors = allEntityIds(brokerBaseUri);
        if (!survivors.isEmpty()) {
            throw new IllegalStateException(
                    "Scorpio wipe left %d entities behind at %s: %s"
                            .formatted(survivors.size(), brokerBaseUri, survivors));
        }
    }

    /** Every entity id the broker holds across all replicable types, de-duplicated. */
    private static List<String> allEntityIds(String brokerBaseUri) {
        Set<String> ids = new LinkedHashSet<>();
        for (String type : ROOT_OBJECTS_LIST) {
            ids.addAll(idsOfType(brokerBaseUri, type));
        }
        return List.copyOf(ids);
    }

    private static List<String> idsOfType(String brokerBaseUri, String type) {
        List<String> ids = new ArrayList<>();
        long offset = 0;
        long total;
        do {
            ResponseEntity<String> page = fetchPage(brokerBaseUri, type, offset);
            List<String> pageIds = parseIds(page.getBody());
            ids.addAll(pageIds);

            total = resultsCount(page, ids.size());
            offset += PAGE_LIMIT;
            // A page shorter than the limit means the broker has nothing more to give, whatever
            // the count header claims -- without this an absent or stale header could spin here.
            if (pageIds.size() < PAGE_LIMIT) {
                break;
            }
        } while (offset < total);
        return ids;
    }

    private static ResponseEntity<String> fetchPage(String brokerBaseUri, String type, long offset) {
        String uri = brokerBaseUri + "/ngsi-ld/v1/entities/"
                + "?type=%s&options=keyValues&limit=%d&offset=%d&count=true"
                .formatted(type, PAGE_LIMIT, offset);
        return WebClient.create()
                .get()
                .uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(new ParameterizedTypeReference<String>() {
                })
                .block(TIMEOUT);
    }

    private static List<String> parseIds(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            List<String> ids = new ArrayList<>();
            for (JsonNode entity : root) {
                JsonNode id = entity.get("id");
                if (id != null && !id.asText().isBlank()) {
                    ids.add(id.asText());
                }
            }
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse Scorpio entity page: " + body, e);
        }
    }

    private static long resultsCount(ResponseEntity<String> page, long fallback) {
        String header = page.getHeaders().getFirst("NGSILD-Results-Count");
        if (header == null) {
            return fallback;
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
