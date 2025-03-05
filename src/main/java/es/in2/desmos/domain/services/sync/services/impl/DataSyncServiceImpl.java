package es.in2.desmos.domain.services.sync.services.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import es.in2.desmos.application.workflows.jobs.P2PDataSyncJob;
import es.in2.desmos.domain.exceptions.BrokerEntityRetrievalException;
import es.in2.desmos.domain.exceptions.HashLinkException;
import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.BlockchainNotification;
import es.in2.desmos.domain.models.Entity;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.api.QueueService;
import es.in2.desmos.domain.services.sync.services.DataSyncService;
import es.in2.desmos.domain.utils.Base64Converter;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.security.M2MAccessTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.NoSuchAlgorithmException;

import static es.in2.desmos.domain.utils.ApplicationUtils.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataSyncServiceImpl implements DataSyncService {

    private final ApiConfig apiConfig;
    private final AuditRecordService auditRecordService;
    private final P2PDataSyncJob p2PDataSyncJob;
    private final QueueService queueServiceImpl;
    private final M2MAccessTokenProvider m2MAccessTokenProvider;

    /*
     *  Workflow steps:
     *  1. Get all ProductOffering ID entities from the local Broker.
     *  2. Send a POST request to the configured external Broker with the ProductOffering ID entity list.
     *  3. Compare the response with the list of ProductOffering ID entities from the local Broker.
     *  4. If the response contains new ProductOffering ID entities,
     *  send a request to the external Broker to get the new ProductOfferings and its related entities.
     *  5. Publish the new ProductOfferings and its related entities to the local Broker.
     */
    @Override
    public Mono<Void> synchronizeData(String processId) {
        log.debug("ProcessID: {} - Synchronizing data...", processId);

        queueServiceImpl.pause();

        return p2PDataSyncJob.synchronizeData(processId)
                .doOnTerminate(() -> {
                    log.info("ProcessID: {} - DataSyncWorkflow completed. Restarting queues...", processId);

                    queueServiceImpl.resume();
                })
                .then();
    }

    /*
     *  This method retrieves the entity from the external broker executing the URL provided in the dataLocation
     *  field of the BlockchainNotification.
     *  If the entity is successfully retrieved, the method follows by checking the data integrity of itself.
     */
    @Override
    public Flux<String> getEntityFromExternalSource(String processId, BlockchainNotification blockchainNotification) {
        log.debug("ProcessID: {} - Retrieving entity from the external broker...", processId);

        String externalBrokerURL = extractContextBrokerUrlFromDataLocation(blockchainNotification.dataLocation());
        log.debug("ProcessID: {} - External Broker URL: {}", processId, externalBrokerURL);

        return m2MAccessTokenProvider.getM2MAccessToken()
                .flatMapMany(accessToken ->
                        apiConfig.webClient()
                                .get()
                                .uri(externalBrokerURL)
                                .accept(MediaType.APPLICATION_JSON)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                                .retrieve()
                                .onStatus(status -> status != null && status.isSameCodeAs(HttpStatusCode.valueOf(200)),
                                        clientResponse -> {
                                            log.debug("ProcessID: {} - Entity retrieved successfully from the external broker", processId);
                                            return Mono.empty();
                                        })
                                .onStatus(status -> status != null && status.is5xxServerError(),
                                        clientResponse ->
                                                Mono.error(new BrokerEntityRetrievalException(
                                                        "Error occurred while retrieving entity from the external" +
                                                                " broker")))
                                .bodyToFlux(Entity.class)
                                .map(Entity::value)
                                .retry(3)
                                .map(Base64Converter::convertBase64ToString));
    }

    @Override
    public Mono<String> verifyRetrievedEntityData(String processId, BlockchainNotification blockchainNotification, String retrievedBrokerEntity) {
        // Verify Data Integrity of the Retrieved Entity
        return verifyRetrievedEntityDataIntegrity(processId, blockchainNotification, retrievedBrokerEntity)
                // Verify Data Consistency of the Retrieved Entity
                .then(verifyRetrievedEntityDataConsistency(processId, blockchainNotification, retrievedBrokerEntity));
    }

    // Verify Data Integrity of the Retrieved Entity (Focuses on preventing corruption and ensuring the data remains accurate and unaltered.)
    private Mono<String> verifyRetrievedEntityDataIntegrity(String processId,
                                                            BlockchainNotification blockchainNotification,
                                                            String retrievedBrokerEntity) {
        log.debug("ProcessID: {} - Verifying the data integrity of the retrieved entity...", processId);
        try {
            // Get the hash of the retrieved entity
            String retrievedEntityHash = calculateSHA256(retrievedBrokerEntity);
            // To verify the data integrity: hash(previousEntityHashLink + retrievedEntityHash) = dataLocationHashLink
            String previousEntityHashLink = blockchainNotification.previousEntityHashLink().substring(2);
            String dataLocationHashLink = extractHashLinkFromDataLocation(blockchainNotification.dataLocation());
            if (dataLocationHashLink.equals(previousEntityHashLink)) {
                // It is the first entity in the chain, and we need to verify that
                // the hash of the retrieved entity is equal to the hash in the dataLocation
                if (!retrievedEntityHash.equals(dataLocationHashLink)) {
                    log.error("ProcessID: {} - Error occurred while verifying the data integrity of the retrieved entity: Hash verification failed", processId);
                    return Mono.error(new HashLinkException("Hash verification failed"));
                }
            } else {
                // It is not the first entity in the chain, and we need to verify that
                // the hash of the retrieved entity plus the previousEntitytHash is equal
                // to the hashLink in the dataLocation
                String calculatedEntityHasLink = calculateHashLink(previousEntityHashLink, retrievedEntityHash);
                if (!calculatedEntityHasLink.equals(dataLocationHashLink)) {
                    log.error("ProcessID: {} - Error occurred while verifying the data integrity of the retrieved entity: HashLink verification failed", processId);
                    return Mono.error(new HashLinkException("HashLink verification failed"));
                }
            }
            return Mono.just(retrievedBrokerEntity);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            log.warn("ProcessID: {} - Error occurred while sorting the attributes of the retrieved entity: {}", processId, e.getMessage());
            return Mono.error(new HashLinkException("Integrity of the retrieved entity verification failed"));
        }
    }

    /*
     * 1. We search in the DB if there is a record with the same entity ID (Consumer and Published status)
     * 2. If it exists, we capture the hash of the entity with status (Published), that is, the one that ended
     * your integration process with the local Context Broker successfully.
     * 3. We validate that the hash of the database entity corresponds to the previousHash of the notification.
     * 4. If applicable, the consistency is valid, and we have not lost any data change/stage between both processes.
     */
    private Mono<String> verifyRetrievedEntityDataConsistency(String processId, BlockchainNotification blockchainNotification, String retrievedBrokerEntity) {
        return auditRecordService.findLatestConsumerPublishedAuditRecordByEntityId(processId, blockchainNotification.entityId())
                // If audit record exists,
                // verify the data consistency of the retrieved entity,
                // and it means that the retrieved entity is not the first entity in the chain
                .flatMap(auditRecords ->
                        verifyDataConsistency(processId, auditRecords, blockchainNotification, retrievedBrokerEntity))
                // If audit record does not exist,
                // it means that the retrieved entity is the first entity in the chain,
                // and we do not need to verify the data consistency of the retrieved entity
                .switchIfEmpty(Mono.just(retrievedBrokerEntity));
    }

    private Mono<String> verifyDataConsistency(String processId, AuditRecord auditRecord, BlockchainNotification blockchainNotification, String retrievedBrokerEntity) {
        String previousEntityHash = blockchainNotification.previousEntityHashLink().substring(2);
        if (auditRecord.getEntityHashLink().equals(previousEntityHash)) {
            log.info("ProcessID: {} - Data consistency verification passed successfully", processId);
            return Mono.just(retrievedBrokerEntity);
        } else {
            log.warn("ProcessID: {} - Error occurred while verifying the data consistency of the retrieved entity: Data consistency verification failed", processId);
            return Mono.error(new HashLinkException("Data consistency verification failed"));
        }
    }

}