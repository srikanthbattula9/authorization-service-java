package com.srikanth.authservice.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Publishes authorization decisions to the same card.transactions topic the
 * Python service (card-issuer-processor) publishes to, so both services
 * participate in one shared event stream. Published only after the local
 * database transaction has committed, same ordering discipline as the
 * Python side's app/events.py: never announce a state change before it is
 * durably recorded.
 *
 * Known gap, same one documented in the Python service: this is a
 * best-effort, fire-and-forget publish, not a transactional outbox. A crash
 * between the database commit and this publish call would lose the event.
 * That is a real limitation, not silently assumed away.
 */
@Component
public class AuthorizationEventPublisher implements DisposableBean {

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String topic;

    public AuthorizationEventPublisher(
            @Value("${kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${kafka.topic}") String topic
    ) {
        this.topic = topic;
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        this.producer = new KafkaProducer<>(props);
    }

    public void publishAuthorizationDecided(String authId, String status, String declineCode, long amountMinor) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_type", "authorization.decided");
        event.put("occurred_at", Instant.now().toString());
        event.put("auth_id", authId);
        event.put("status", status);
        event.put("decline_code", declineCode);
        event.put("amount_minor", amountMinor);

        try {
            String json = objectMapper.writeValueAsString(event);
            producer.send(new ProducerRecord<>(topic, json));
            producer.flush();
        } catch (Exception e) {
            // Best-effort: a publish failure does not fail the authorization
            // that already committed. Logged, not swallowed silently.
            System.err.println("Failed to publish authorization event: " + e.getMessage());
        }
    }

    @Override
    public void destroy() {
        producer.close();
    }
}
