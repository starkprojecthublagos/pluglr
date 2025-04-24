package com.example.streaming.domain;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import com.example.streaming.clients.UserServiceClient;
import com.example.streaming.dtos.UserDataDTO;
import com.example.streaming.enums.EventStatus;
import com.example.streaming.enums.StreamType;
import com.example.streaming.model.ChatMessage;
import com.example.streaming.model.Event;
import com.example.streaming.model.Participant;
import com.example.streaming.poto.libs.RNNoiseProcessor;
import com.example.streaming.repository.ChatMessageRepository;
import com.example.streaming.repository.EventRepository;
import com.example.streaming.repository.ParticipantRepository;
import com.example.streaming.responses.StreamLinkMessage;
import com.example.streaming.sessions.WebSocketSessionManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.*;

@Component
public class AudioStreamHandlerConsumer extends AbstractWebSocketHandler {

    private static final short SILENCE_THRESHOLD = 500;
    private final ConcurrentMap<String, String> sessionToRoomMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();
    // Active connections tracking
    private final Map<String, Event> activeEvents = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private static final Map<String, Set<WebSocketSession>> eventSessions = new HashMap<>();
    private final Map<String, String> sessionToEventMap = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    

    @Autowired
    private EventRepository eventRepository;


    @Autowired
    private ChatMessageRepository chatMessageRepository;


    @Autowired
    private ParticipantRepository participantRepository;


    @Autowired
    private UserServiceClient userServiceClient;


    @Autowired
    private WebSocketSessionManager sessionManager;
    

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        URI uri = session.getUri();
        if (uri == null) {
            session.close();
            return;
        }
        String path = uri.getPath();
        String[] segments = path.split("/");
        // Handle active-streams request
        if (path.endsWith("/ws/list/active-streams/")) {
            //handleActiveStreamsRequest(session);
            return;
        }
        
        Map<String, String> pathVariables = extractPathVariables(uri);

        String userId = pathVariables.get("userId");

        if (segments.length == 6 && segments[5].matches("\\d+") && segments.length <= 6) {
            String exractedHostId = segments[5];
            long hosterId = Long.parseLong(exractedHostId);

            if (userId != null && !userId.isEmpty()) {
                handleHostConnection(session, hosterId);
                session.getAttributes().put("userId", userId);
                session.getAttributes().put("eventId", "event-" + userId);
            }
        } else if (segments.length >= 7 && "join".equals(segments[4])) {
            String eventId = segments[6];
            String username = segments[7];
            String participantId = segments[8];
            handleParticipantConnection(session, eventId, participantId, username);
            session.getAttributes().put("eventId", eventId);
            session.getAttributes().put("username", username);
            session.getAttributes().put("participantId", participantId);
            eventSessions.computeIfAbsent(eventId, k -> new HashSet<>()).add(session);
        }
        sessions.put(session.getId(), session);
    }


    @Override
    protected void handleBinaryMessage(WebSocketSession senderSession, BinaryMessage message) throws Exception {
        String senderSessionId = senderSession.getId();
        String roomId = getRoomIdBySessionId(senderSessionId);
        if (roomId == null)
            return;

        // 2. Process audio frame
        byte[] audioData = message.getPayload().array();
        // Silence detection - only if you want server-side filtering
        boolean isSilent = true;
        for (int i = 0; i < audioData.length; i += 2) {
            short sample = (short) ((audioData[i + 1] << 8) | (audioData[i] & 0xFF));
            // Adjust threshold as needed
            if (Math.abs(sample) > SILENCE_THRESHOLD) {
                isSilent = false;
                break;
            }
        }
        if (isSilent) {
            System.out.println("🔇 Silent audio skipped.");
            return;
        }

       
        BinaryMessage audioMessage = new BinaryMessage(audioData);

        // Get ALL active sessions in the room (host + cohosts + participants)
        List<WebSocketSession> allSessions = getRoomParticipants(roomId, senderSessionId);

        // Broadcast to everyone except sender
        for (WebSocketSession recipient : allSessions) {
            try {
                if (recipient.isOpen()) {
                    recipient.sendMessage(audioMessage);
                }
            } catch (IOException e) {
                System.out.println("❌ Failed to send to session " + recipient.getId() + ": " + e.getMessage());
                cleanupDisconnectedSession(recipient.getId());
            }
        }
    }

    // Helper method to get all active participants in a room
    private List<WebSocketSession> getRoomParticipants(String roomId, String excludeSessionId) {
        List<WebSocketSession> participants = new ArrayList<>();
        // Get all participant session IDs for this room
        List<String> sessionIds = participantRepository.findSessionIdsByRoomId(roomId);

        for (String sessionId : sessionIds) {
            if (!sessionId.equals(excludeSessionId)) {
                WebSocketSession session = sessionManager.getSession(sessionId);
                if (session != null && session.isOpen()) {
                    participants.add(session);
                }
            }
        }

        // Optionally add host if stored separately
        eventRepository.findByRoomId(roomId).ifPresent(event -> {
            String hostSessionId = event.getHostSessionId();
            if (hostSessionId != null && !hostSessionId.equals(excludeSessionId)) {
                WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                if (hostSession != null && hostSession.isOpen()) {
                    participants.add(hostSession);
                }
            }
        });

        return participants;
    }


    private String getRoomIdBySessionId(String sessionId) {
        // 1. Check if session is the main host session
        Optional<Event> mainHostEvent = eventRepository.findByHostSessionId(sessionId);
        if (mainHostEvent.isPresent()) {
            return mainHostEvent.get().getRoomId().toString();
        }
        return null;
    }

    
    // Helper method to clean up disconnected sessions
    private void cleanupDisconnectedSession(String sessionId) {
        sessionManager.removeSession(sessionId);
    }
 

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    message.getPayload(),
                    new TypeReference<Map<String, Object>>() {
                    });
            
            String type = (String) payload.get("type");
            String eventId = (String) payload.get("event_id");
            Optional<Event> event = eventRepository.findByRoomId(eventId);
            
            if (!event.isPresent()) {
                sendErrorAndClose(session, "Event not found",
                        "This event id you're trying to join is not found in the system.");
                return;
            }

            if (type.equals("stream_ended")) {
                // Redis key
                Optional<Event> checkEvent = eventRepository.findByRoomId(eventId);
                if (!checkEvent.isPresent()) {
                    sendErrorAndClose(session, "Room not found", "This event room doesn't exist or has no host details.");
                    return;
                }
                
                Event eventOpt = checkEvent.get();
                endEvent(eventOpt);
            }

            if (type.equals("invite_cohost")) {
                String participantId = (String) payload.get("user_id");
                handleUserInviteCohost(eventId, participantId);
            }

            if (type.equals("accept_cohost")) {
                String participantId = (String) payload.get("user_id");
                handleUserAcceptCohost(session, eventId, participantId);
            }

            if (type.equals("leave_room")) {
                String participantId = (String) payload.get("user_id");
                handleUserExistRoom(eventId, participantId);
            }

            if (type.equals("remove_user_in_room")) {
                String participantId = (String) payload.get("user_id");
                handleRemoveUserFromRoomByHost(eventId, participantId);
            }

            if (type.equals("remove_cohost")) {
                String participantId = (String) payload.get("user_id");
                handleRemoveCohost(eventId, participantId);
            }

            if (type.equals("leave_cohost")) {
                String participantId = (String) payload.get("user_id");
                handleUserCohost(eventId, participantId);
            }
            
            if ("chat_message".equals(type) || type.equals("text_message") || type.equals("chat") || "broadcast_message".equals(type)) {
                String textMessage = (String) payload.get("message");
                String participantId = (String) payload.get("user_id");
                if (textMessage != null && !textMessage.trim().isEmpty()) {
                    handleChatMessage(eventId, textMessage.trim(), participantId);
                }
            }

            if (type.equals("cohost_audio_status")) {
                String participantId = (String) payload.get("user_id");
                Boolean isSpeaking = (Boolean) payload.get("isSpeaking");
                handleCohostSpeakingStatus(eventId, participantId, isSpeaking);
            }

        } catch (Exception e) {
            sendErrorAndClose(session, "Error", "Invalid message format");
        }
    }

    
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

   
    private void handleHostConnection(WebSocketSession session, Long userId) throws IOException {
        UserDataDTO userDetails = userServiceClient.getUserById(userId);
        if(userDetails.getData() ==null){
            sendErrorAndClose(session, "Unauthorized", "User not found.");
            return;
        }
       
        // Create new event
        Event event = new Event();
        event.setRoomId(UUID.randomUUID().toString());
        event.setHostId(userId.toString());
        event.setHostSessionId(session.getId());
        event.setHostUsername(userDetails.getData().getUsername());
        event.setStatus(EventStatus.active);
        event.setStreamType(StreamType.AUDIO);
        eventRepository.save(event);

        activeEvents.put(event.getRoomId(), event);

        // Prepare session data
        String sessionId = session.getId();
        session.getAttributes().put("userId", userId);
        session.getAttributes().put("isHost", true);
        session.getAttributes().put("eventId", event.getRoomId());
        // hostDetails
        Map<String, Object> hostDetails = new HashMap<>();
        hostDetails.put("startedAt", Instant.now().toString());
        hostDetails.put("userId", userId);
        hostDetails.put("username", userDetails.getData().getUsername());

        // cohosts map 
        Map<String, Object> cohosts = new HashMap<>();

        // hostSessions
        Map<String, Object> hostSessions = new HashMap<>();
        hostSessions.put("main", sessionId);
        hostSessions.put("cohosts", cohosts);

        // participants map (initially empty)
        Map<String, Object> participants = new HashMap<>();

        // eventData (final result structure to store)
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("hostDetails", hostDetails);
        eventData.put("total_participants", 0);
        eventData.put("hostSessions", hostSessions);
        eventData.put("hostId", userId);
        eventData.put("roomId", event.getRoomId());
        eventData.put("participants", participants);

       // Store in memory
        sessions.put(session.getId(), session);
        sessionToEventMap.put(session.getId(), event.getRoomId());
        sessionManager.addSession(session.getId(), session);

        // Set session attributes
        session.getAttributes().put("userId", userId);
        session.getAttributes().put("isHost", true);
        session.getAttributes().put("eventId", event.getRoomId());

        // Send stream link
        String joinUrl = generateStreamingLink(event.getRoomId());
        sendMessage(session, new StreamLinkMessage("stream_link", event.getRoomId(), joinUrl));
    }

       
    @SuppressWarnings("null")
    private void handleParticipantConnection(WebSocketSession session, String eventId, String participantId,
            String username) throws IOException {
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

        // 2. Determine display name
        String displayName = (username != null) ? username : "Guest";

        // 3. Check if participant already exists
        List<Participant> participants = eventOpt.getParticipants();
        Participant participant = null;

        for (Participant p : participants) {
            if (p.getUserId().equals(participantId)) {
                participant = p;
                break;
            }
        }

       
        Optional<Participant> existingUser = participantRepository.findByEventIdAndUserId(eventId, participantId);
        boolean isReconnecting = existingUser.isPresent() ? true : false;
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
            participant.setSessionId(session.getId());
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

        // 6. Register session with manager
        sessionManager.addSession(session.getId(), session);
        activeSessions.put(session.getId(), session);
        sessionToRoomMap.put(session.getId(), eventId);

        // 7. Notify participants
        String joinMessage = displayName + (isReconnecting ? " reconnected" : " joined") + " the live event";
        broadcastMessage(eventId, joinMessage, displayName, participantId);
        broadcastParticipantList(eventId);
        broadcastParticipantCount(eventId);
        sendChatHistory(eventId, participantId);
    }
    

    public void broadcastMessage(String roomId, String message, String username, String userId) {
        try {
            // Create the message payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "broadcast_message");
            payload.put("message", message);
            payload.put("username", username);
            payload.put("user_id", userId);
            payload.put("timestamp", Instant.now().toString());

            // 1. Fetch room from RoomService (in-memory or DB)
            Event room = eventRepository.findAllParticipantsByRoomId(roomId);
            if (room == null) {
                System.out.println("❌ No room found with ID: " + roomId);
                return;
            }

            // Convert participants List to Map
            Map<String, Participant> participants = room.getParticipants().stream()
                    .collect(Collectors.toMap(Participant::getUserId, Function.identity()));

            if (participants == null || participants.isEmpty()) {
                System.out.println("⚠️ No participants found in room: " + roomId);
                return;
            }

            // 2. Convert payload to JSON
            ObjectMapper mapper = new ObjectMapper();
            String jsonMessage = mapper.writeValueAsString(payload);
            TextMessage textMessage = new TextMessage(jsonMessage);

            // 3. Broadcast message
            for (Participant participant : participants.values()) {
                String sessionId = participant.getSessionId();
                if (sessionId != null) {
                    WebSocketSession session = sessionManager.getSession(sessionId);
                    if (session != null && session.isOpen()) {
                        session.sendMessage(textMessage);
                    } else {
                        sessionManager.removeSession(sessionId);
                    }
                }
            }

        } catch (JsonProcessingException e) {
            System.out.println("❌ JSON processing error: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("❌ Failed to send WebSocket message: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("❌ Unexpected error during broadcast: " + e.getMessage());
        }
    }


    private void broadcastParticipantList(String eventId) {
        try {
            // 1. Get event with participants from DB
            Optional<Event> optionalEvent = eventRepository.findByRoomIdWithParticipants(eventId);
            if (optionalEvent.isEmpty()) {
                return;
            }

            Event event = optionalEvent.get();
            List<Participant> participants = event.getParticipants();

            if (participants.isEmpty()) {
                return;
            }

            // 2. Prepare participant list payload
            List<Map<String, Object>> participantList = participants.stream()
                    .map(participant -> {
                        Map<String, Object> simplified = new HashMap<>();
                        simplified.put("id", participant.getUserId());
                        simplified.put("username", participant.getUsername());
                        simplified.put("is_cohost", participant.isCohost());
                        return simplified;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "participant_list");
            payload.put("participants", participantList);
         
            ObjectMapper mapper = new ObjectMapper();
            String jsonMessage = mapper.writeValueAsString(payload);
            TextMessage textMessage = new TextMessage(jsonMessage);

            // 3. Send to each participant
            for (Participant participant : participants) {
                String sessionId = participant.getSessionId();
                WebSocketSession session = sessionManager.getSession(sessionId);
                if (session != null && session.isOpen()) {
                    try {
                        session.sendMessage(textMessage);
                    } catch (IOException e) {
                        sessionManager.removeSession(sessionId);
                    }
                }
            }

            // 4. Optionally send to host
            if (event.getHostSessionId() != null) {
                WebSocketSession hostSession = sessionManager.getSession(event.getHostSessionId());
                if (hostSession != null && hostSession.isOpen()) {
                    try {
                        hostSession.sendMessage(textMessage);
                    } catch (IOException e) {
                        System.out.println("Failed to send to host participants list");
                    }
                } else {
                    System.out.println("Host WebSocketSession is null or closed");
                }
            }

        } catch (Exception e) {
            System.out.println("Error broadcasting participant list: " + e.getMessage());
        }
    }


    private void broadcastParticipantCount(String eventId) {
        try {
            // 1. Get event with participants from DB
            Optional<Event> optionalEvent = eventRepository.findByRoomIdWithParticipants(eventId);
            if (optionalEvent.isEmpty()) {
                return;
            }

            Event event = optionalEvent.get();
            List<Participant> participants = event.getParticipants();
            int participantCount = participants.size();

            // 2. Prepare payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "participant_count");
            payload.put("count", participantCount);

            ObjectMapper mapper = new ObjectMapper();
            String jsonMessage = mapper.writeValueAsString(payload);
            TextMessage textMessage = new TextMessage(jsonMessage);

            // 3. Send to host (if hostSessionId is tracked)
            if (event.getHostSessionId() != null) {
                WebSocketSession hostSession = sessionManager.getSession(event.getHostSessionId());
                if (hostSession != null && hostSession.isOpen()) {
                    try {
                        hostSession.sendMessage(textMessage);
                    } catch (IOException e) {
                        System.out.println("Failed to send to host: " + e.getMessage());
                        sessionManager.removeSession(event.getHostSessionId());
                    }
                }
            }

            // 4. Send to each participant
            for (Participant participant : participants) {
                String sessionId = participant.getSessionId();
                if (sessionId != null) {
                    WebSocketSession session = sessionManager.getSession(sessionId);
                    if (session != null && session.isOpen()) {
                        try {
                            session.sendMessage(textMessage);
                        } catch (IOException e) {
                            sessionManager.removeSession(sessionId);
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("Broadcast error: " + e.getMessage());
        }
    }
    
    
    // Updated implementation that gets user details internally
    private String generateStreamingLink(String eventId) {
        return String.format("wss://apps.pluglr.com/ws/stream/live/join/event/%s/", eventId);
    }
    
    
    // Utility methods
    private Map<String, String> extractPathVariables(URI uri) {
        Map<String, String> pathVariables = new HashMap<>();
        String path = uri.getPath();
        String[] parts = path.split("/");
        if (parts.length > 3)
            pathVariables.put("userId", parts[3]);
        if (parts.length > 4)
            pathVariables.put("eventId", parts[4]);
        if (parts.length > 5)
            pathVariables.put("participantId", parts[5]);
        if (parts.length > 6)
            pathVariables.put("username", parts[6]);

        return pathVariables;
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

    
    private void sendMessage(WebSocketSession session, Object message) throws IOException {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to send message", e);
        }
    }

    
    public String getEventHost(String eventId) {
        Optional<Event> hostKey = eventRepository.findByRoomId(eventId);
        if (!hostKey.isPresent())
            return null;

        try {
            ObjectMapper mapper = new ObjectMapper();

            // Convert the Event object to a JSON string
            String eventJson = mapper.writeValueAsString(hostKey.get());

            // Now use readTree with the JSON string
            JsonNode root = mapper.readTree(eventJson);
            JsonNode hostDetails = root.path("hostDetails");
            return hostDetails.path("userId").asText();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    
    public boolean isCohost(String eventId, String participantId) {
        List<Participant> userList = participantRepository.findByRoomIdAndParticipantId(eventId, participantId);

        if (userList == null || userList.isEmpty())
            return false;

        try {
            ObjectMapper mapper = new ObjectMapper();

            // Convert the List<Participant> to a JSON string
            String participantJson = mapper.writeValueAsString(userList);

            // Now readTree with the JSON string
            JsonNode root = mapper.readTree(participantJson);

            JsonNode participantsNode = root.path("participants");
            String hostId = getEventHost(eventId);

            for (JsonNode participant : participantsNode) {
                String id = participant.path("participantId").asText();
                if (participantId.equals(id) && !participantId.equals(hostId)) {
                    return true;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    
    private void endEvent(Event event) {
        String eventId = event.getRoomId();

        // 1. First broadcast the event ending
        Map<String, Object> endPayload = new HashMap<>();
        endPayload.put("type", "event_ended");
        endPayload.put("message", "Host has ended the event");
        endPayload.put("timestamp", Instant.now().toString());

        // Broadcast to all active sessions
        broadcastToAllSessions(eventId, endPayload);

        // 2. Then update database
        event.setStatus(EventStatus.ended);
        event.setEndTimestamp(Instant.now());
        eventRepository.save(event);
    }

    // Enhanced broadcast method
    public void broadcastToAllSessions(String roomId, Object payload) {
        try {
            // 1. Fetch event by roomId with participants
            Event event = eventRepository.findAllParticipantsByRoomId(roomId);
            if (event == null) {
                System.out.println("Room not found for roomId: " + roomId);
                return;
            }

            List<Participant> participants = event.getParticipants();
            if (participants == null || participants.isEmpty()) {
                System.out.println("No participants found in room");
                return;
            }

            String jsonMessage = objectMapper.writeValueAsString(payload);
            TextMessage textMessage = new TextMessage(jsonMessage);

            // 5. Broadcast to all participants
            for (Participant participant : participants) {
                String sessionId = participant.getSessionId();
                WebSocketSession targetSession = sessionManager.getSession(sessionId);

                if (targetSession != null && targetSession.isOpen()) {
                    try {
                        targetSession.sendMessage(textMessage);
                    } catch (IOException e) {
                        sessionManager.removeSession(sessionId);
                        System.out.println("Cleaned up disconnected session: " + sessionId);
                    }
                }
            }

            // 6. Send to host
            WebSocketSession hostSession = sessionManager.getSession(event.getHostSessionId());
            if (hostSession != null && hostSession.isOpen()) {
                try {
                    hostSession.sendMessage(textMessage);
                } catch (IOException e) {
                    sessionManager.removeSession(event.getHostSessionId());
                    System.out.println("Failed to send to host: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.out.println("❌ Error in broadcastToAllSessions: " + e.getMessage());
        }
    }

    
    private void handleUserInviteCohost(String roomId, String participantId) {
        try {
            // 1. Fetch event by roomId
            Optional<Event> event = eventRepository.findByRoomId(roomId);
            if (event.isEmpty()) {
                System.out.println("Room not found for roomId: " + roomId);
                return;
            }

            // 2. Fetch participants for the event
            Event eventWithParticipants = eventRepository.findAllParticipantsById(event.get().getId());
            List<Participant> participants = eventWithParticipants.getParticipants();

            if (participants == null || participants.isEmpty()) {
                System.out.println("⚠️ No participants in room: " + roomId);
                return;
            }

            // 3. Find the target participant
            Participant targetParticipant = participants.stream()
                    .filter(p -> participantId.equals(p.getUserId()))
                    .findFirst()
                    .orElse(null);

            if (targetParticipant == null) {
                System.out.println("Participant not found: " + participantId);
                return;
            }

            // 4. Create and send the invitation message
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "cohost_invite");
            payload.put("message", "You've been invited to be a co-host on this event.");
            payload.put("timestamp", Instant.now().toString());

            String jsonMessage = objectMapper.writeValueAsString(payload);
            TextMessage textMessage = new TextMessage(jsonMessage);

            String sessionId = targetParticipant.getSessionId();
            WebSocketSession session = sessionManager.getSession(sessionId);

            if (session != null && session.isOpen()) {
                session.sendMessage(textMessage);
            } else {
                System.out.println("Participant session not found or closed for: " + participantId);
            }

        } catch (Exception e) {
            System.out.println("Error inviting co-host: " + e.getMessage());
            e.printStackTrace();
        }
    }


    private void handleUserAcceptCohost(WebSocketSession session, String roomId, String participantId) {
        try {
            // 1. Fetch event by roomId with participants
            Event event = eventRepository.findAllParticipantsByRoomId(roomId);
            if (event == null) {
                System.out.println("Room not found for roomId: " + roomId);
                return;
            }

            List<Participant> participants = event.getParticipants();
            if (participants == null || participants.isEmpty()) {
                System.out.println("No participants found in room");
                return;
            }

            // 2. Find and update the participant's co-host status
            Participant cohost = null;
            for (Participant participant : participants) {
                if (participantId.equals(participant.getUserId())) {
                    participant.setCohost(true);
                    participantRepository.save(participant);
                    cohost = participant;
                    break;
                }
            }

            if (cohost == null) {
                System.out.println("Participant not found in room: " + participantId);
                return;
            }

            // 4. Prepare notification
            String username = cohost.getUsername() != null ? cohost.getUsername() : "A participant";
            Map<String, Object> notificationPayload = new HashMap<>();
            notificationPayload.put("type", "cohost_joined");
            notificationPayload.put("message", username + " is now a co-host");
            notificationPayload.put("participant_id", participantId);
            notificationPayload.put("isCoHost", true);
            notificationPayload.put("username", username);
            notificationPayload.put("timestamp", Instant.now().toString());

            ObjectMapper mapper = new ObjectMapper();
            String notificationMessage = mapper.writeValueAsString(notificationPayload);
            TextMessage textMessage = new TextMessage(notificationMessage);

            // 5. Broadcast to all participants
            for (Participant participant : participants) {
                String sessionId = participant.getSessionId();
                WebSocketSession targetSession = sessionManager.getSession(sessionId);

                if (targetSession != null && targetSession.isOpen()) {
                    try {
                        targetSession.sendMessage(textMessage);
                    } catch (IOException e) {
                        sessionManager.removeSession(sessionId);
                        System.out.println("Cleaned up disconnected session: " + sessionId);
                    }
                }
            }

            // 6. Send to host
            WebSocketSession hostSession = sessionManager.getSession(event.getHostSessionId());
            if (hostSession != null && hostSession.isOpen()) {
                try {
                    hostSession.sendMessage(textMessage);
                } catch (IOException e) {
                    sessionManager.removeSession(event.getHostSessionId());
                    System.out.println("Failed to send to host: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            System.out.println("Error handling co-host acceptance: " + e.getMessage());
            e.printStackTrace();
        }
    }


    private void handleUserExistRoom(String roomId, String participantId) {
        try {
            // 1. Fetch the Event by roomId from DB with participants eagerly loaded
            Optional<Event> optionalEvent = eventRepository.findByRoomIdWithParticipants(roomId);
            if (optionalEvent.isEmpty()) {
                System.out.println("Room not found for roomId: " + roomId);
                return;
            }

            Event event = optionalEvent.get();
            List<Participant> participants = event.getParticipants();

            if (participants == null || participants.isEmpty()) {
                System.out.println("No participants found in room");
                return;
            }

            // 2. Find and remove the participant
            String username = null;
            Participant leavingParticipant = null;
            Iterator<Participant> iterator = participants.iterator();
            while (iterator.hasNext()) {
                Participant p = iterator.next();
                if (participantId.equals(p.getUserId())) {
                    username = p.getUsername();
                    leavingParticipant = p;
                    iterator.remove();
                    break;
                }
            }

            if (username == null || leavingParticipant == null) {
                System.out.println("Participant not found in room: " + participantId);
                return;
            }

            // 3. Update total participants
            int currentCount = event.getTotalParticipants() != null ? event.getTotalParticipants() : 0;
            event.setTotalParticipants(Math.max(0, currentCount - 1));

            // 4. Persist changes
            eventRepository.save(event);
            participantRepository.delete(leavingParticipant);

            // 5. Build leave message payload
            Map<String, Object> leavePayload = new HashMap<>();
            leavePayload.put("type", "participant_left");
            leavePayload.put("message", username + " left the room");
            leavePayload.put("participantId", participantId);
            leavePayload.put("timestamp", Instant.now().toString());
            broadcastParticipantList(roomId);
            String leaveMessage = objectMapper.writeValueAsString(leavePayload);
            TextMessage leaveTextMessage = new TextMessage(leaveMessage);

            // 6. Broadcast to remaining participants
            for (Participant p : participants) {
                String sessionId = p.getSessionId();
                if (sessionId != null) {
                    WebSocketSession wsSession = sessionManager.getSession(sessionId);
                    if (wsSession != null && wsSession.isOpen()) {
                        try {
                            wsSession.sendMessage(leaveTextMessage);
                        } catch (IOException e) {
                            sessionManager.removeSession(sessionId);
                            System.out.println("Cleaned up disconnected session for: " + p.getUserId());
                        }
                    }
                }
            }

            // 7. Notify host
            String hostSessionId = event.getHostSessionId();
            if (hostSessionId != null) {
                WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                if (hostSession != null && hostSession.isOpen()) {
                    try {
                        hostSession.sendMessage(leaveTextMessage);
                    } catch (IOException e) {
                        sessionManager.removeSession(hostSessionId);
                        System.out.println("Failed to send leave message to host: " + e.getMessage());
                    }
                }
            }

            // 8. Refresh participants UI
            
            broadcastParticipantCount(roomId);

        } catch (Exception e) {
            System.out.println("Error handling user exit: " + e.getMessage());
            e.printStackTrace();
        }
    }


    private void handleRemoveUserFromRoomByHost(String eventId, String participantId) {
        try {
            // 1. Fetch event from the database
            Event room = eventRepository.findByRoomId(eventId).orElse(null);
            if (room == null) {
                System.out.println("Room not found for eventId: " + eventId);
                return;
            }

            List<Participant> participantsList = room.getParticipants();
            if (participantsList == null || participantsList.isEmpty()) {
                System.out.println("No participants found in room");
                return;
            }

            // 2. Find and remove the participant
            Participant participantToRemove = null;
            for (Participant p : participantsList) {
                if (participantId.equals(p.getUserId())) {
                    participantToRemove = p;
                    break;
                }
            }

            if (participantToRemove == null) {
                System.out.println("Participant not found in room: " + participantId);
                return;
            }

            String username = participantToRemove.getUsername();
            participantsList.remove(participantToRemove);
            room.setParticipants(participantsList);

            // 3. Update participant count
            int updatedCount = Math.max(0, room.getTotalParticipants() - 1);
            room.setTotalParticipants(updatedCount);

            // 4. Save updated room to the database
            eventRepository.save(room);

            // 6. Prepare leave notification
            Map<String, Object> leavePayload = new HashMap<>();
            leavePayload.put("type", "participant_removed");
            leavePayload.put("message", username + " has been removed from the room.");
            leavePayload.put("participantId", participantId);
            leavePayload.put("timestamp", Instant.now().toString());

            String leaveMessage = new ObjectMapper().writeValueAsString(leavePayload);
            TextMessage leaveTextMessage = new TextMessage(leaveMessage);

            // 7. Broadcast to all remaining participants
            for (Participant p : participantsList) {
                String pid = p.getUserId();
                String pSessionId = participantRepository.findSessionIdForParticipant(pid);

                if (pSessionId != null) {
                    WebSocketSession session = sessionManager.getSession(pSessionId);
                    if (session != null && session.isOpen()) {
                        try {
                            session.sendMessage(leaveTextMessage);
                        } catch (IOException e) {
                            sessionManager.removeSession(pSessionId);
                            System.out.println("Cleaned up disconnected session for: " + pid);
                        }
                    }
                }
            }

            // 8. Broadcast updated participant list and count
            broadcastParticipantList(eventId);
            broadcastParticipantCount(eventId);

            // 9. Notify host
            if (room != null) {
                WebSocketSession hostSession = sessionManager.getSession(room.getHostSessionId());
                if (hostSession != null && hostSession.isOpen()) {
                    try {
                        hostSession.sendMessage(leaveTextMessage);
                    } catch (IOException e) {
                        System.out.println("Failed to send to host: " + e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("Error handling user removal by host: " + e.getMessage());
            e.printStackTrace();
        }
    }

    
    private void handleRemoveCohost(String eventId, String participantId) {
        try {
            // 1. Fetch room data from database (replace Redis with DB)
            Event room = eventRepository.findByRoomId(eventId).orElse(null);

            if (room == null) {
                System.out.println("Room not found for eventId: " + eventId);
                return;
            }

            // 2. Convert participants list to a map using participantId as the key
            List<Participant> participantsList = room.getParticipants();
            if (participantsList == null || participantsList.isEmpty()) {
                System.out.println("No participants found in room");
                return;
            }

            // Convert List<Participant> to Map<String, Participant>
            Map<String, Participant> participantsMap = participantsList.stream()
                    .collect(Collectors.toMap(Participant::getUserId, participant -> participant));

            // 3. Find and update the participant's co-host status to false
            boolean participantFound = false;
            String username = null;
            for (Map.Entry<String, Participant> entry : participantsMap.entrySet()) {
                Participant participant = entry.getValue();
                if (participantId.equals(participant.getUserId())) {
                    participant.setCohost(false);
                    username = participant.getUsername();
                    participantFound = true;
                    break;
                }
            }

            if (!participantFound) {
                System.out.println("Participant not found in room: " + participantId);
                return;
            }

            // 4. Save the updated room data back to the database
            room.setParticipants(new ArrayList<>(participantsMap.values()));
            eventRepository.save(room);

            // 5. Prepare removal notification
            Map<String, Object> notificationPayload = new HashMap<>();
            notificationPayload.put("type", "cohost_removed");
            notificationPayload.put("message", username + " has been removed as co-host");
            notificationPayload.put("participant_id", participantId);
            notificationPayload.put("isCoHost", false);
            notificationPayload.put("username", username);
            notificationPayload.put("timestamp", Instant.now().toString());

            String notificationMessage = new ObjectMapper().writeValueAsString(notificationPayload);
            TextMessage textMessage = new TextMessage(notificationMessage);

            // 6. Broadcast notification to all participants and host
            for (Participant participant : participantsMap.values()) {
                String sessionId = participant.getSessionId();
                if (sessionId != null) {
                    WebSocketSession session = sessionManager.getSession(sessionId);
                    if (session != null && session.isOpen()) {
                        try {
                            session.sendMessage(textMessage);
                        } catch (IOException e) {
                            sessionManager.removeSession(sessionId);
                            System.out.println(
                                    "Cleaned up disconnected participant session: " + participant.getUserId());
                        }
                    }
                }
            }

           
            if (room.getHostSessionId() != null) {
                WebSocketSession hostSession = sessionManager.getSession(room.getHostSessionId());
                if (hostSession != null && hostSession.isOpen()) {
                    try {
                        hostSession.sendMessage(textMessage);
                    } catch (IOException e) {
                        System.out.println("Failed to send to host: " + e.getMessage());
                    }
                }
            }
            
            // 7. Broadcast updated participant list to everyone
            broadcastParticipantList(eventId);

        } catch (Exception e) {
            System.out.println("Error removing co-host: " + e.getMessage());
            e.printStackTrace();
        }
    }


    private void handleUserCohost(String eventId, String participantId) {
        try {
            Optional<Event> optionalEvent = eventRepository.findByRoomId(eventId);
            if (optionalEvent.isEmpty()) {
                System.out.println("Room not found for eventId: " + eventId);
                return;
            }

            Event event = optionalEvent.get();
            List<Participant> participants = participantRepository.findByEventRoomId(event.getRoomId());

            if (participants.isEmpty()) {
                System.out.println("No participants found in room");
                return;
            }

            boolean participantFound = false;
            String username = null;

            for (Participant participant : participants) {
                if (participant.getUserId().equals(participantId)) {
                    participant.setCohost(false); 
                    username = participant.getUsername();
                    participantFound = true;
                    break;
                }
            }

            if (!participantFound) {
                System.out.println("Participant not found in room: " + participantId);
                return;
            }

            // Persist updated participant info
            participantRepository.saveAll(participants);

            // Create notification
            Map<String, Object> notificationPayload = new HashMap<>();
            notificationPayload.put("type", "cohost_removed");
            notificationPayload.put("message", username + " is no longer co-host");
            notificationPayload.put("participant_id", participantId);
            notificationPayload.put("isCoHost", false);
            notificationPayload.put("username", username);
            notificationPayload.put("timestamp", Instant.now().toString());

            ObjectMapper mapper = new ObjectMapper();
            String notificationMessage = mapper.writeValueAsString(notificationPayload);
            TextMessage textMessage = new TextMessage(notificationMessage);

            // Broadcast to participants
            for (Participant participant : participants) {
                String sessionId = participant.getSessionId();
                if (sessionId != null) {
                    WebSocketSession wsSession = sessionManager.getSession(sessionId);
                    if (wsSession != null && wsSession.isOpen()) {
                        try {
                            wsSession.sendMessage(textMessage);
                        } catch (IOException e) {
                            sessionManager.removeSession(sessionId);
                            System.out.println("Cleaned up disconnected session for: " + participant.getUserId());
                        }
                    }
                }
            }

            // Send to host
            String hostSessionId = event.getHostSessionId();

            if (hostSessionId != null) {
                WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                if (hostSession != null && hostSession.isOpen()) {
                    try {
                        hostSession.sendMessage(textMessage);
                    } catch (IOException e) {
                        sessionManager.removeSession(hostSessionId);
                        System.out.println("Failed to send to host: " + e.getMessage());
                    }
                }
            }

            // broadcast full updated list
            broadcastParticipantList(eventId);

        } catch (Exception e) {
            System.out.println("Error removing co-host: " + e.getMessage());
            e.printStackTrace();
        }
    }

    
    private void handleChatMessage(String eventId, String message, String participantId) {
        try {
            long userIdLong = Long.parseLong(participantId);
            String username = null;
       
            Optional<Participant> participant = participantRepository.findByEventIdAndUserId(eventId, participantId);
    
            if (participant.isPresent()) {
                username = participant.get().getUsername();

            }
            else if (!participant.isPresent()) {
                Optional<Event> eventHostUsername = eventRepository.findByRoomId(eventId);
                username = eventHostUsername.get().getHostUsername();
            }
            if(!participant.isPresent() && !participant.isPresent()){
                username ="User";
            }
        
            // 1. Save chat to DB
            ChatMessage chatMessage = new ChatMessage();
            chatMessage.setRoomId(eventId);
            chatMessage.setUserId(userIdLong);
            chatMessage.setUsername(username);
            chatMessage.setMessage(message);
            chatMessageRepository.save(chatMessage);

            // 2. Build payload
            Map<String, Object> chatPayload = new HashMap<>();
            chatPayload.put("type", "chat_message");
            chatPayload.put("message", message);
            chatPayload.put("username", username);
            chatPayload.put("user_id", participantId);
            chatPayload.put("timestamp", Instant.now().toString());

            TextMessage textMessage = new TextMessage(new ObjectMapper().writeValueAsString(chatPayload));

            // 3. Fetch all sessionIds (host + participants)
            Set<String> sessionIds = new HashSet<>();

            eventRepository.findByRoomId(eventId)
                    .map(Event::getHostSessionId)
                    .ifPresent(sessionIds::add);

            sessionIds.addAll(participantRepository.findSessionIdsByRoomId(eventId));

            // 4. Send message to each live WebSocket session
            for (String sessionId : sessionIds) {
                WebSocketSession session = sessionManager.getSession(sessionId); 
                if (session != null && session.isOpen()) {
                    try {
                        session.sendMessage(textMessage);
                    } catch (IOException e) {
                        sessionManager.removeSession(sessionId);
                        System.err.println("Failed to send message to session: " + sessionId);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error handling chat message: " + e.getMessage());
            e.printStackTrace();
        }
    }


    private void sendChatHistory(String eventId, String targetParticipantId) {
        try {
            // 1. Fetch latest 100 messages from DB
            List<ChatMessage> chatMessages = chatMessageRepository.findTop100ByRoomIdOrderByCreatedOnDesc(eventId);
            Collections.reverse(chatMessages); 

            // 2. Prepare payload
            List<Map<String, Object>> messages = chatMessages.stream().map(msg -> {
                Map<String, Object> map = new HashMap<>();
                map.put("message", msg.getMessage());
                map.put("username", msg.getUsername());
                map.put("user_id", msg.getUserId().toString());
                map.put("timestamp", msg.getCreatedOn().toString());
                return map;
            }).collect(Collectors.toList());

            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "chat_history");
            payload.put("chat_message", messages);
            String sessionId = null;
            Optional<Participant> participant = participantRepository.findByEventIdAndUserId(eventId,
                    targetParticipantId);
            if (participant.isPresent()) {
                sessionId = participant.get().getSessionId();
            }
            
            String jsonPayload = new ObjectMapper().writeValueAsString(payload);
            TextMessage textMessage = new TextMessage(jsonPayload);

            // 3. Send to target participant
            WebSocketSession session = sessionManager.getSession(sessionId);
            if (session != null && session.isOpen()) {
                session.sendMessage(textMessage);
            }

        } catch (Exception e) {
            System.out.println("Error sending chat history: " + e.getMessage());
            e.printStackTrace();
        }
    }
   

    private void handleCohostSpeakingStatus(String eventId, String participantId, boolean isSpeaking) {
        try {
            // 1. Get participants and host info from DB
            List<Participant> participants = participantRepository.findByEventRoomId(eventId);
            if (participants == null || participants.isEmpty())
                return;

            Participant cohost = participants.stream()
                    .filter(p -> participantId.equals(p.getUserId()))
                    .findFirst()
                    .orElse(null);

            if (cohost == null)
                return;

            cohost.setSpeaking(isSpeaking);
            participantRepository.save(cohost);

            // 2. Prepare messages
            ObjectMapper mapper = new ObjectMapper();

            Map<String, Object> participantMessage = Map.of(
                    "type", "cohost_speaking_update",
                    "participant_id", participantId,
                    "isSpeaking", isSpeaking);
            TextMessage participantTextMessage = new TextMessage(mapper.writeValueAsString(participantMessage));

            String username = cohost.getUsername() != null ? cohost.getUsername() : "Co-host";
            Map<String, Object> hostMessage = Map.of(
                    "type", "host_cohost_speaking",
                    "cohost_id", participantId,
                    "isSpeaking", isSpeaking,
                    "cohost_username", username);
            TextMessage hostTextMessage = new TextMessage(mapper.writeValueAsString(hostMessage));

            // 3. Broadcast messages
            for (Participant participant : participants) {
                String sessionId = participant.getSessionId();
                if (sessionId == null)
                    continue;

                WebSocketSession session = sessionManager.getSession(sessionId);
                if (session != null && session.isOpen()) {
                    try {
                        if (participant.isCohost()) {
                            session.sendMessage(participantTextMessage);
                        } else if (participant.getUserId().equals(participant.getEvent().getHostId())) {
                            session.sendMessage(hostTextMessage);
                        } else {
                            session.sendMessage(participantTextMessage);
                        }
                    } catch (IOException e) {
                        sessionManager.removeSession(sessionId);
                    }
                }
            }

            // 4. Optional: Send mute/unmute control to host
            Participant host = participants.stream()
                    .filter(p -> p.getUserId().equals(p.getEvent().getHostId()))
                    .findFirst()
                    .orElse(null);

            if (host != null) {
                String hostSessionId = host.getSessionId();
                if (hostSessionId != null) {
                    WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                    if (hostSession != null && hostSession.isOpen()) {
                        Map<String, Object> audioControl = Map.of(
                                "type", "audio_control",
                                "shouldMute", isSpeaking);
                        hostSession.sendMessage(new TextMessage(mapper.writeValueAsString(audioControl)));
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("Error handling cohost speaking status: " + e.getMessage());
        }
    }
    

    
}
