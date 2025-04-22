package com.example.streaming.sessions;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
public class WebSocketSessionManager {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> participantIdToSessionId = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> eventIdToParticipantSessionIds = new ConcurrentHashMap<>();

    public void addSession(String sessionId, WebSocketSession session) {
        sessions.put(sessionId, session);
    }

    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
    }

    public WebSocketSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public WebSocketSession getSessionByParticipantId(String participantId) {
        String sessionId = participantIdToSessionId.get(participantId);
        if (sessionId != null) {
            return sessions.get(sessionId);
        }
        return null;
    }

    public List<WebSocketSession> getAllSessionsByEvent(String eventId) {
        Set<String> sessionIds = eventIdToParticipantSessionIds.getOrDefault(eventId, Collections.emptySet());
        return sessionIds.stream()
                .map(sessions::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public void registerSession(String sessionId, WebSocketSession session, String participantId, String eventId) {
        sessions.put(sessionId, session);
        participantIdToSessionId.put(participantId, sessionId);

        eventIdToParticipantSessionIds
                .computeIfAbsent(eventId, k -> ConcurrentHashMap.newKeySet())
                .add(sessionId);
    }


}