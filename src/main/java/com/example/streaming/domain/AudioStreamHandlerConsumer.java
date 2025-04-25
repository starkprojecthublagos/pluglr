package com.example.streaming.domain;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import com.aventrix.jnanoid.jnanoid.NanoIdUtils;
import com.example.streaming.enums.EventStatus;
import com.example.streaming.enums.StreamType;
import com.example.streaming.model.Event;
import com.example.streaming.model.Participant;
import com.example.streaming.repository.EventRepository;
import com.example.streaming.repository.ParticipantRepository;
import com.example.streaming.sessions.WebSocketSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.*;


@Component
public class AudioStreamHandlerConsumer extends AbstractWebSocketHandler {
    // Active connections tracking
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
   
    @Autowired
    private WebSocketSessionManager sessionManager;

    @Autowired
    private EventRepository eventRepository;
    
    @Autowired
    private ParticipantRepository participantRepository;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        sessions.put(session.getId(), session);
    }

    @SuppressWarnings("null")
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> payload = new ObjectMapper().readValue(message.getPayload(), Map.class);
        String type = (String) payload.get("type");
    
        if (type.equals("host_ready")) {
            // 1. Generate room ID for host
            String roomId = UUID.randomUUID().toString();
            String sessionId = session.getId();
            String hostId = NanoIdUtils.randomNanoId();
            String username = "HostUserName";
            // 2. Store host session
            sessionManager.setHost(roomId, session);
            // Create new event
            Event event = new Event();
            event.setRoomId(roomId);
            event.setHostId(hostId);
            event.setHostSessionId(sessionId);
            event.setHostUsername(username);
            event.setStatus(EventStatus.active);
            event.setStreamType(StreamType.AUDIO);
            eventRepository.save(event);
            // Prepare session data
            session.getAttributes().put("userId", hostId);
            session.getAttributes().put("isHost", true);
            session.getAttributes().put("eventId", event.getRoomId());
            // 3. Respond to host with the generated room ID
            Map<String, Object> response = new HashMap<>();
            response.put("type", "host_room_created");
            response.put("room_id", roomId);
            session.sendMessage(new TextMessage(new ObjectMapper().writeValueAsString(response)));
        }
        
        if (type.equals("viewer_ready")) {
            // 1. Extract room ID
            String eventId = (String) payload.get("room_id");
            // 1. Fetch event with participants eagerly
            Optional<Event> event = eventRepository.findByRoomIdWithParticipants(eventId);
            if (!event.isPresent()) {
                sendErrorAndClose(session, "Event not found",
                        "This event id you're trying to join is not found in the system.");
                return;
            }

            Event eventOpt = event.get();

            if (eventOpt.getStatus() != EventStatus.active) {
                sendErrorAndClose(session, "Event ended", "This event is no longer active");
                return;
            }
            String displayName = "User 1";
            String participantId = NanoIdUtils.randomNanoId();
            // 3. Check if participant already exists
            List<Participant> participants = eventOpt.getParticipants();
            Participant participant = null;
            Optional<Participant> existingUser = participantRepository.findByEventIdAndUserId(eventId, participantId);
            boolean isReconnecting = existingUser.isPresent() ? true : false;
            String userSessionId = session.getId();
            if (isReconnecting) {
                // Update existing participant
                participant.setReconnect(true);
                participant.setSessionId(session.getId());
            } else {
                // Create and add new participant
                participant = new Participant();
                participant.setEvent(eventOpt);
                participant.setUserId(participantId);
                participant.setUsername(displayName);
                participant.setSessionId(userSessionId);
                participant.setReconnect(false);
                participant.setCohost(false);

                participants.add(participant);
                if (eventOpt.getTotalParticipants() == null) {
                    eventOpt.setTotalParticipants(1);
                } else {
                    eventOpt.setTotalParticipants(eventOpt.getTotalParticipants() + 1);
                }
            }

            // 4. Save event (cascades to participants)
            eventRepository.save(eventOpt);

            // 5. Set session attributes
            session.getAttributes().put("participantId", participantId);
            session.getAttributes().put("eventId", eventId);
            // 2. Store viewer session
            sessionManager.addViewer(eventId, session);
            
            // 3. Notify host that a new viewer joined (optional)
            WebSocketSession hostSession = sessionManager.getSession(eventOpt.getHostSessionId());
            if (hostSession != null && hostSession.isOpen()) {
                Map<String, Object> notifyHost = new HashMap<>();
                notifyHost.put("type", "viewer_joined");
                notifyHost.put("viewer_id", userSessionId);
                hostSession.sendMessage(new TextMessage(new ObjectMapper().writeValueAsString(notifyHost)));
            }

        }
    
    
        if (type.equals("offer")) {
            // Host sends offer to a specific viewer
            String targetViewerId = (String) payload.get("viewer_id");
            System.out.println("Targert session is: "+targetViewerId );
            // 1. Extract room ID
            String eventId = (String) payload.get("room_id");
            // 1. Fetch event with participants eagerly
            Optional<Event> event = eventRepository.findByRoomIdWithParticipants(eventId);
            if (!event.isPresent()) {
                sendErrorAndClose(session, "Event not found",
                        "This event id you're trying to join is not found in the system.");
                return;
            }

            Event eventOpt = event.get();

            WebSocketSession viewerSession = sessionManager.getSession(targetViewerId);

            if (viewerSession != null && viewerSession.isOpen()) {
                // Add "from" field so viewer knows host id (if needed)
                payload.put("type", "stream_offer");
                payload.put("from", eventOpt.getHostSessionId()); // host id
                viewerSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            } else {
                System.out.println("Viewer session not found or closed for ID: " + targetViewerId);
            }
        }
        
        if (type.equals("answer")) {
            // Viewer sends answer back to host
            String viewer_id = (String) payload.get("viewer_id");
            // 1. Extract room ID
            String eventId = (String) payload.get("room_id");
            // 1. Fetch event with participants eagerly
            Optional<Event> event = eventRepository.findByRoomIdWithParticipants(eventId);
            if (!event.isPresent()) {
                sendErrorAndClose(session, "Event not found",
                        "This event id you're trying to join is not found in the system.");
                return;
            }

            Event eventOpt = event.get();
            WebSocketSession hostSession = sessionManager.getSession(eventOpt.getHostSessionId());
      
            if (hostSession != null && hostSession.isOpen()) {
                payload.put("type", "stream_answer");
                payload.put("from", viewer_id); // viewer id
                hostSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            } else {
                System.out.println("Host session not found or closed for ID: {}"+ viewer_id);
            }
        } 
        
        if (type.equals("ice_candidate")) {
            String targetId = (String) payload.get("viewer_id");
            String roomId = (String) payload.get("room_id");
            
            // Get either the host session or viewer session based on context
            WebSocketSession targetSession = sessionManager.getSession(targetId);
            
            if (targetSession != null && targetSession.isOpen()) {
                // Forward the ICE candidate to the target
                Map<String, Object> forwardPayload = new HashMap<>();
                forwardPayload.put("type", "ice_candidate");
                forwardPayload.put("candidate", payload.get("candidate"));
                forwardPayload.put("viewer_id", session.getId()); // who sent this candidate
                
                targetSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(forwardPayload)));
            } else {
                System.out.println("Target session not found or closed for ID: " + targetId);
            }
        }
    }


    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    private void sendErrorAndClose(WebSocketSession session, String message, String details) {
        try {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", message);
            error.put("details", details);
            error.put("type", "error");

            String errorJson = new ObjectMapper().writeValueAsString(error);
            session.sendMessage(new TextMessage(errorJson));

            // Small delay to ensure message delivery before closing
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }

            if (session.isOpen()) {
                session.close(CloseStatus.NOT_ACCEPTABLE.withReason(message));
            }
        } catch (Exception e) {
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.SERVER_ERROR.withReason("Internal server error"));
                }
            } catch (IOException ignored) {
            }
        }
    }

}