package com.example.streaming.sessions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
public class WebSocketSessionManager {
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> hostSessions = new ConcurrentHashMap<>();
    private final Map<String, List<WebSocketSession>> viewersByRoom = new ConcurrentHashMap<>();

    public void addSession(WebSocketSession session) {
        sessions.put(session.getId(), session);
    }

    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        hostSessions.values().removeIf(s -> s.getId().equals(sessionId));
        viewersByRoom.values().forEach(list -> list.removeIf(s -> s.getId().equals(sessionId)));
    }

    public void setHost(String roomId, WebSocketSession session) {
        hostSessions.put(roomId, session);
        addSession(session);
    }

    public WebSocketSession getHost(String roomId) {
        return hostSessions.get(roomId);
    }

    public void addViewer(String roomId, WebSocketSession session) {
        viewersByRoom.computeIfAbsent(roomId, k -> new ArrayList<>()).add(session);
        addSession(session);
    }

    public WebSocketSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public List<WebSocketSession> getViewers(String roomId) {
        return viewersByRoom.getOrDefault(roomId, Collections.emptyList());
    }
}
