package com.example.streaming.clients;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import com.example.streaming.exceptions.UserClientNotFoundException;
import com.example.streaming.responses.UserResponseDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;
import com.example.streaming.dtos.UserDataDTO;

@Service
public class UserServiceClient {

    private final WebClient webClient;

    @Autowired
    public UserServiceClient(WebClient.Builder webClientBuilder, @Value("${auth-service.base-url}") String baseUrl) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    public UserDataDTO getUserById(Long id) {
        System.out.println(id);
        UserDataDTO response = this.webClient.get()
                .uri("/account/user/id/{userId}/", id)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .flatMap(errorMessage -> {
                                    if (clientResponse.statusCode().is4xxClientError()) {
                                        String details = extractDetailsFromError(errorMessage); // optional helper
                                        return Mono.error(new UserClientNotFoundException("User not found: " + details,
                                                errorMessage));
                                    }
                                    return Mono.error(new RuntimeException("Server error: " + errorMessage));
                                }))
                .bodyToMono(UserDataDTO.class)
                .switchIfEmpty(Mono.error(new UserClientNotFoundException("User not found: No data returned", null)))
                .block();

        return response;
    }

    public UserDataDTO getUserByUsername(String username) {
        return this.webClient.get()
                .uri("/account/user/username/{username}/", username)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .flatMap(errorMessage -> {
                                    if (clientResponse.statusCode().is4xxClientError()) {
                                        String details = extractDetailsFromError(errorMessage);
                                        return Mono.error(new UserClientNotFoundException("User not found", details));
                                    }
                                    return Mono.error(new RuntimeException("Server error"));
                                }))
                .bodyToMono(UserResponseDTO.class)
                .map(UserResponseDTO::getData)
                .switchIfEmpty(Mono.error(new UserClientNotFoundException("User not found: No data returned", null)))
                .block();
    }


    private String extractDetailsFromError(String errorMessage) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(errorMessage);
            return rootNode.path("message").asText();
        } catch (JsonProcessingException e) {
            return "No details available";
        }
    }

}
