package com.nhlpool.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import org.springframework.beans.factory.annotation.Value;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${app.cors.origins:http://localhost:4200,http://localhost:4201}")
    private String corsOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Plain native WebSocket endpoint — used by @stomp/stompjs in the browser
        registry.addEndpoint("/ws-native")
                .setAllowedOriginPatterns("*");

        // SockJS fallback endpoint (kept for completeness / legacy)
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(corsOrigins.split(","))
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Messages published to /topic/** are broadcast to all subscribers
        config.enableSimpleBroker("/topic");
        // Messages sent from client start with /app (not needed yet but good practice)
        config.setApplicationDestinationPrefixes("/app");
    }
}
