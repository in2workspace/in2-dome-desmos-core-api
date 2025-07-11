package es.in2.desmos.infrastructure.configs;

import es.in2.desmos.infrastructure.configs.properties.TxSubscriptionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static es.in2.desmos.domain.utils.ApplicationConstants.ROOT_OBJECTS_LIST;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class BlockchainConfig {

    private final TxSubscriptionProperties txSubscriptionProperties;

    public String getNotificationEndpoint() {
        return txSubscriptionProperties.notificationEndpoint();
    }

    public List<String> getRootObjects() {
        return ROOT_OBJECTS_LIST;
    }

}
