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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import com.example.streaming.clients.UserServiceClient;
import com.example.streaming.dtos.UserDataDTO;
import com.example.streaming.enums.EventStatus;
import com.example.streaming.enums.StreamType;
import com.example.streaming.model.ChatMessage;
import com.example.streaming.model.Event;
import com.example.streaming.model.Participant;
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
    
    @Autowired
    private RedisTemplate<String, String> stringRedisTemplate;

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
        } else if (segments.length >= 7 && "join".equals(segments[4]) && segments[8].matches("\\d+")) {
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
        byte[] audioData = message.getPayload().array();

    
        int MAX_FRAME_SIZE = 4096 * 2; 
        if (audioData.length > MAX_FRAME_SIZE) {
            System.out.println("❌ Frame too large. Skipping...");
            return;
        }

        // Silence detection - only if you want server-side filtering
        boolean isSilent = true;
        for (int i = 0; i < audioData.length; i += 2) {
            short sample = (short) ((audioData[i + 1] << 8) | (audioData[i] & 0xFF));
            // Adjust threshold as needed
            if (Math.abs(sample) > 500) { 
                isSilent = false;
                break;
            }
        }
        if (isSilent) {
            System.out.println("🔇 Silent audio skipped.");
            return;
        }
       
        // Find which room the sender belongs to
        String roomId = getRoomIdBySessionId(senderSessionId);
        if (roomId == null) {
            System.out.println("❌ No room found for session: " + senderSessionId);
            return;
        }

        // Get all active participants in the room
        List<WebSocketSession> recipients = getRoomParticipants(roomId, senderSessionId);
        if (recipients.isEmpty()) {
            System.out.println("⚠️ No active participants in room: " + roomId);
            return;
        }

        //  Broadcast audio to all participants 
        BinaryMessage audioMessage = new BinaryMessage(audioData);
        for (WebSocketSession recipient : recipients) {
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

    private String getRoomIdBySessionId(String sessionId) {
        // 1. Check if session is the main host session
        Optional<Event> mainHostEvent = eventRepository.findByHostSessionId(sessionId);
        if (mainHostEvent.isPresent()) {
            return mainHostEvent.get().getRoomId().toString();
        }
        return null;
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
                String redisKey = "event_room:" + eventId;
                String eventJson = stringRedisTemplate.opsForValue().get(redisKey);
                if (eventJson == null || eventJson.isEmpty()) {
                    sendErrorAndClose(session, "Room not found", "This event room doesn't exist or has no host details.");
                    return;
                }
                
                Event eventOpt = event.get();
                endEvent(eventOpt);

                stringRedisTemplate.delete(redisKey);
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
                String participantId = (String) payload.get("participant_id");
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
            if (p.getParticipantId().equals(participantId)) {
                participant = p;
                break;
            }
        }

        boolean isReconnecting = (participant != null);

        if (isReconnecting) {
            // Update existing participant
            participant.setReconnect(true);
            participant.setSessionId(session.getId());
        } else {
            // Create and add new participant
            participant = new Participant();
            participant.setEvent(eventOpt);
            participant.setParticipantId(participantId);
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
        // Create the message payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "broadcast_message");
        payload.put("message", message);
        payload.put("username", username);
        payload.put("user_id", userId);
        payload.put("timestamp", Instant.now().toString());
        try {
            // 1. Get room data from Redis
            String redisKey = "event_room:" + roomId;
            String redisData = stringRedisTemplate.opsForValue().get(redisKey);

            if (redisData == null || redisData.isEmpty()) {
                System.out.println("❌ No data found in Redis for roomId: " + roomId);
                return;
            }

            // 2. Parse the room data
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> roomData = mapper.readValue(redisData, new TypeReference<Map<String, Object>>() {
            });
            Map<String, Object> participants = (Map<String, Object>) roomData.get("participants");

            if (participants == null || participants.isEmpty()) {
                System.out.println("⚠️ No participants found in room: " + roomId);
                return;
            }

            // 3. Convert payload to JSON
            String jsonMessage = mapper.writeValueAsString(payload);
            TextMessage textMessage = new TextMessage(jsonMessage);
    
            // 4. Broadcast to all participants
            for (Object participantObj : participants.values()) {
                Map<String, Object> participant = (Map<String, Object>) participantObj;
                String participantId = (String) participant.get("participantId");

                // Get the WebSocket session for this participant
                String sessionKey = "event_participant_session:" + participantId;
                String sessionId = stringRedisTemplate.opsForValue().get(sessionKey);
                // 2. Get the actual WebSocket session
                if (sessionId != null) {
                    WebSocketSession session = sessionManager.getSession(sessionId);
                    session.sendMessage(textMessage);
                   
                }
            }
        } catch (JsonProcessingException e) {
            System.out.println("❌ JSON processing error: " + e.getMessage());
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
                        simplified.put("id", participant.getParticipantId());
                        simplified.put("username", participant.getUsername());
                        simplified.put("is_cohost", participant.isCohost());
                        return simplified;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "participant_list");
            payload.put("participants", participantList);
            // ObjectMapper objectMapper = new ObjectMapper();
            // String redisPayload =
            // objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);

            // System.out.println("==== Redis Data to be Stored ====");
            // System.out.println(redisPayload);
            // System.out.println("=================================");        
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
    public void broadcastToAllSessions(String eventId, Object payload) {
        try {
            // Fetch the Event by roomId
            Event event = eventRepository.findAllParticipantsByRoomId(eventId);
            if (event ==null) {
                System.out.println("⚠️ No event found for roomId: " + eventId);
                return;
            }

            List<Participant> participants = event.getParticipants();
            if (participants == null || participants.isEmpty()) {
                System.out.println("⚠️ No participants in room: " + eventId);
                return;
            }

            String jsonMessage = objectMapper.writeValueAsString(payload);
            TextMessage textMessage = new TextMessage(jsonMessage);

            for (Participant participant : participants) {
                String sessionId = participant.getSessionId();
                WebSocketSession session = sessionManager.getSession(sessionId);

                if (session != null && session.isOpen()) {
                    try {
                        session.sendMessage(textMessage);
                    } catch (IOException e) {
                        System.out.println("❌ Failed to send to session " + sessionId + ": " + e.getMessage());
                        sessionManager.removeSession(sessionId);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("❌ Error in broadcastToAllSessions: " + e.getMessage());
        }
    }

    
    private void handleUserInviteCohost(String eventId, String participantId) {
        try {
            // 1. Fetch event by roomId
            Optional<Event> event = eventRepository.findByRoomId(eventId);
            if (!event.isPresent()) {
                System.out.println("Room not found for eventId: " + eventId);
                return;
            }

            // 2. Find participant by event and participantId
            Optional<Participant> participant = participantRepository.findByEventIdAndUserId(event.get().getRoomId(), participantId);

            if (participant == null) {
                System.out.println("Participant not found: " + participantId);
                return;
            }

            String sessionId = participant.get().getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                System.out.println("Session ID not found for participant: " + participantId);
                return;
            }

            // 3. Send co-host invite message
            WebSocketSession participantSession = sessionManager.getSession(sessionId);
            if (participantSession != null && participantSession.isOpen()) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "cohost_invite");
                payload.put("message", "You've been invited to be a co-host on this event.");
                payload.put("timestamp", Instant.now().toString());

                String json = objectMapper.writeValueAsString(payload);
                participantSession.sendMessage(new TextMessage(json));
            } else {
                System.out.println("❌ WebSocket session is closed or missing for " + participantId);
            }
        } catch (Exception e) {
            System.out.println("Error inviting co-host: " + e.getMessage());
        }
    }


    private void handleUserAcceptCohost(WebSocketSession session, String eventId, String participantId) {
        try {
            // 1. Fetch room data from Redis
            String redisKey = "event_room:" + eventId;
            String eventJson = stringRedisTemplate.opsForValue().get(redisKey);

            if (eventJson == null || eventJson.isEmpty()) {
                System.out.println("Room not found for eventId: " + eventId);
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> roomData = mapper.readValue(eventJson, new TypeReference<>() {
            });
            Map<Integer, Map<String, Object>> participantsMap = (Map<Integer, Map<String, Object>>) roomData
                    .get("participants");

            if (participantsMap == null || participantsMap.isEmpty()) {
                System.out.println("No participants found in room");
                return;
            }

            // 2. Find and update the participant's co-host status
            boolean participantFound = false;
            for (Map.Entry<Integer, Map<String, Object>> entry : participantsMap.entrySet()) {
                Map<String, Object> participant = entry.getValue();
                if (participantId.equals(participant.get("participantId"))) {
                    participant.put("is_cohost", true);
                    participantFound = true;
                    break;
                }
            }

            if (!participantFound) {
                System.out.println("Participant not found in room: " + participantId);
                return;
            }

            // 3. Update Redis with the modified participant data
            roomData.put("participants", participantsMap);
            stringRedisTemplate.opsForValue().set(redisKey, mapper.writeValueAsString(roomData));

            // Store co-host session separately (similar to host session)
            stringRedisTemplate.opsForValue().set("event_cohost_session:" + participantId, session.getId());
            
            // Update hostSessions in Redis
            Map<String, Object> hostSessions = (Map<String, Object>) roomData.get("hostSessions");
            if (hostSessions != null) {
                Map<String, String> cohostSessions = (Map<String, String>) hostSessions.get("cohosts");
                cohostSessions.put(participantId, session.getId());

                roomData.put("hostSessions", hostSessions);
                stringRedisTemplate.opsForValue().set(redisKey, mapper.writeValueAsString(roomData));
            }
            // 4. Get the participant's username for notification
            String username = (String) participantsMap.values().stream()
                    .filter(p -> participantId.equals(p.get("participantId")))
                    .findFirst()
                    .map(p -> p.get("username"))
                    .orElse("A participant");

            // 5. Prepare notification message
            Map<String, Object> notificationPayload = new HashMap<>();
            notificationPayload.put("type", "cohost_joined");
            notificationPayload.put("message", username + " is now a co-host");
            notificationPayload.put("participant_id", participantId);
            notificationPayload.put("isCoHost", true);
            notificationPayload.put("username", username);
            notificationPayload.put("timestamp", Instant.now().toString());

            String notificationMessage = mapper.writeValueAsString(notificationPayload);
            TextMessage textMessage = new TextMessage(notificationMessage);
            
            // 6. Broadcast notification to all participants and host
            // Send to participants
            for (Map<String, Object> participant : participantsMap.values()) {
                String pid = (String) participant.get("participantId");
                String sessionKey = "event_participant_session:" + pid;
                String sessionId = stringRedisTemplate.opsForValue().get(sessionKey);

                if (sessionId != null) {
                    WebSocketSession oiSession = sessionManager.getSession(sessionId);
                    if (oiSession != null && oiSession.isOpen()) {
                        try {
                            oiSession.sendMessage(textMessage);
                        } catch (IOException e) {
                            stringRedisTemplate.delete(sessionKey);
                            sessionManager.removeSession(sessionId);
                            System.out.println("Cleaned up disconnected participant session: " + pid);
                        }
                    }
                }
            }

            // Send to host
            Map<String, Object> hostDetails = (Map<String, Object>) roomData.get("hostDetails");
            if (hostDetails != null) {
                String hostId = (String) roomData.get("hostId");
                String hostSessionKey = "event_host_session:" + hostId;
                String hostSessionId = stringRedisTemplate.opsForValue().get(hostSessionKey);

                if (hostSessionId != null) {
                    WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                    if (hostSession != null && hostSession.isOpen()) {
                        try {
                            hostSession.sendMessage(textMessage);
                        } catch (IOException e) {
                            System.out.println("Failed to send to host: " + e.getMessage());
                            stringRedisTemplate.delete(hostSessionKey);
                            sessionManager.removeSession(hostSessionId);
                        }
                    }
                }
            }
            
            // // 7. Broadcast updated participant list to everyone
            // broadcastParticipantList(eventId);

        } catch (Exception e) {
            System.out.println("Error handling co-host acceptance: " + e.getMessage());
            e.printStackTrace();
        }
    }


    private void handleUserExistRoom(String eventId, String participantId) {
        try {
            // 1. Fetch room data from Redis
            String redisKey = "event_room:" + eventId;
            String eventJson = stringRedisTemplate.opsForValue().get(redisKey);

            if (eventJson == null || eventJson.isEmpty()) {
                System.out.println("Room not found for eventId: " + eventId);
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> roomData = mapper.readValue(eventJson, new TypeReference<>() {
            });
            Map<Integer, Map<String, Object>> participantsMap = (Map<Integer, Map<String, Object>>) roomData
                    .get("participants");

            if (participantsMap == null || participantsMap.isEmpty()) {
                System.out.println("No participants found in room");
                return;
            }

            // 2. Find and remove the participant
            String username = null;
            Iterator<Map.Entry<Integer, Map<String, Object>>> iterator = participantsMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, Map<String, Object>> entry = iterator.next();
                Map<String, Object> participant = entry.getValue();
                if (participantId.equals(participant.get("participantId"))) {
                    username = (String) participant.get("username");
                    iterator.remove();
                    break;
                }
            }

            if (username == null) {
                System.out.println("Participant not found in room: " + participantId);
                return;
            }

            // 3. Update participant count
            int currentCount = ((Number) roomData.getOrDefault("total_participants", 0)).intValue();
            roomData.put("total_participants", Math.max(0, currentCount - 1));

            // 4. Update Redis
            roomData.put("participants", participantsMap);
            stringRedisTemplate.opsForValue().set(redisKey, mapper.writeValueAsString(roomData));

            // 5. Clean up participant session
            String sessionKey = "event_participant_session:" + participantId;
            stringRedisTemplate.delete(sessionKey);

            // 6. Prepare leave notification
            Map<String, Object> leavePayload = new HashMap<>();
            leavePayload.put("type", "participant_left");
            leavePayload.put("message", username + " left the room");
            leavePayload.put("participantId", participantId);
            leavePayload.put("timestamp", Instant.now().toString());

            String leaveMessage = mapper.writeValueAsString(leavePayload);
            TextMessage leaveTextMessage = new TextMessage(leaveMessage);

            // 7. Broadcast leave notification to all participants and host
            // Send to participants
            for (Map<String, Object> participant : participantsMap.values()) {
                String pid = (String) participant.get("participantId");
                String pSessionKey = "event_participant_session:" + pid;
                String pSessionId = stringRedisTemplate.opsForValue().get(pSessionKey);

                if (pSessionId != null) {
                    WebSocketSession session = sessionManager.getSession(pSessionId);
                    if (session != null && session.isOpen()) {
                        try {
                            session.sendMessage(leaveTextMessage);
                        } catch (IOException e) {
                            stringRedisTemplate.delete(pSessionKey);
                            sessionManager.removeSession(pSessionId);
                        }
                    }
                }
            }

            // 8. Broadcast updated participant list and count
            broadcastParticipantList(eventId);
            broadcastParticipantCount(eventId);

            // Send to host
            Map<String, Object> hostDetails = (Map<String, Object>) roomData.get("hostDetails");
            if (hostDetails != null) {
                String hostId = (String) roomData.get("hostId");
                String hostSessionKey = "event_host_session:" + hostId;
                String hostSessionId = stringRedisTemplate.opsForValue().get(hostSessionKey);

                if (hostSessionId != null) {
                    WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                    if (hostSession != null && hostSession.isOpen()) {
                        try {
                            hostSession.sendMessage(leaveTextMessage);
                        } catch (IOException e) {
                            System.out.println("Failed to send to host: " + e.getMessage());
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("Error handling user exit: " + e.getMessage());
            e.printStackTrace();
        }
    }


    private void handleRemoveUserFromRoomByHost(String eventId, String participantId) {
        try {
            // 1. Fetch room data from Redis
            String redisKey = "event_room:" + eventId;
            String eventJson = stringRedisTemplate.opsForValue().get(redisKey);

            if (eventJson == null || eventJson.isEmpty()) {
                System.out.println("Room not found for eventId: " + eventId);
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> roomData = mapper.readValue(eventJson, new TypeReference<>() {
            });
            Map<Integer, Map<String, Object>> participantsMap = (Map<Integer, Map<String, Object>>) roomData
                    .get("participants");

            if (participantsMap == null || participantsMap.isEmpty()) {
                System.out.println("No participants found in room");
                return;
            }

            // 2. Find and remove the participant
            String username = null;
            Iterator<Map.Entry<Integer, Map<String, Object>>> iterator = participantsMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, Map<String, Object>> entry = iterator.next();
                Map<String, Object> participant = entry.getValue();
                if (participantId.equals(participant.get("participantId"))) {
                    username = (String) participant.get("username");
                    iterator.remove();
                    break;
                }
            }

            if (username == null) {
                System.out.println("Participant not found in room: " + participantId);
                return;
            }

            // 3. Update participant count
            int currentCount = ((Number) roomData.getOrDefault("total_participants", 0)).intValue();
            roomData.put("total_participants", Math.max(0, currentCount - 1));

            // 4. Update Redis
            roomData.put("participants", participantsMap);
            stringRedisTemplate.opsForValue().set(redisKey, mapper.writeValueAsString(roomData));

            // 5. Clean up participant session
            String sessionKey = "event_participant_session:" + participantId;
            stringRedisTemplate.delete(sessionKey);

            // 6. Prepare leave notification
            Map<String, Object> leavePayload = new HashMap<>();
            leavePayload.put("type", "participant_removed");
            leavePayload.put("message", username + " has been removed from the room.");
            leavePayload.put("participantId", participantId);
            leavePayload.put("timestamp", Instant.now().toString());

            String leaveMessage = mapper.writeValueAsString(leavePayload);
            TextMessage leaveTextMessage = new TextMessage(leaveMessage);

            // 7. Broadcast leave notification to all participants and host
            // Send to participants
            for (Map<String, Object> participant : participantsMap.values()) {
                String pid = (String) participant.get("participantId");
                String pSessionKey = "event_participant_session:" + pid;
                String pSessionId = stringRedisTemplate.opsForValue().get(pSessionKey);

                if (pSessionId != null) {
                    WebSocketSession session = sessionManager.getSession(pSessionId);
                    if (session != null && session.isOpen()) {
                        try {
                            session.sendMessage(leaveTextMessage);
                        } catch (IOException e) {
                            stringRedisTemplate.delete(pSessionKey);
                            sessionManager.removeSession(pSessionId);
                        }
                    }
                }
            }

            // 8. Broadcast updated participant list and count
            broadcastParticipantList(eventId);
            broadcastParticipantCount(eventId);

            // Send to host
            Map<String, Object> hostDetails = (Map<String, Object>) roomData.get("hostDetails");
            if (hostDetails != null) {
                String hostId = (String) roomData.get("hostId");
                String hostSessionKey = "event_host_session:" + hostId;
                String hostSessionId = stringRedisTemplate.opsForValue().get(hostSessionKey);

                if (hostSessionId != null) {
                    WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                    if (hostSession != null && hostSession.isOpen()) {
                        try {
                            hostSession.sendMessage(leaveTextMessage);
                        } catch (IOException e) {
                            System.out.println("Failed to send to host: " + e.getMessage());
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("Error handling user exit: " + e.getMessage());
            e.printStackTrace();
        }
    }

    
    private void handleRemoveCohost(String eventId, String participantId) {
        try {
            // 1. Fetch room data from Redis
            String redisKey = "event_room:" + eventId;
            String eventJson = stringRedisTemplate.opsForValue().get(redisKey);

            if (eventJson == null || eventJson.isEmpty()) {
                System.out.println("Room not found for eventId: " + eventId);
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> roomData = mapper.readValue(eventJson, new TypeReference<>() {
            });
            Map<Integer, Map<String, Object>> participantsMap = (Map<Integer, Map<String, Object>>) roomData
                    .get("participants");

            if (participantsMap == null || participantsMap.isEmpty()) {
                System.out.println("No participants found in room");
                return;
            }

            // 2. Find and update the participant's co-host status to false
            boolean participantFound = false;
            String username = null;
            for (Map.Entry<Integer, Map<String, Object>> entry : participantsMap.entrySet()) {
                Map<String, Object> participant = entry.getValue();
                if (participantId.equals(participant.get("participantId"))) {
                    participant.put("is_cohost", false);
                    username = (String) participant.get("username");
                    participantFound = true;
                    break;
                }
            }

            if (!participantFound) {
                System.out.println("Participant not found in room: " + participantId);
                return;
            }

            // 3. Update Redis with the modified participant data
            roomData.put("participants", participantsMap);
            stringRedisTemplate.opsForValue().set(redisKey, mapper.writeValueAsString(roomData));

            // 4. Prepare removal notification
            Map<String, Object> notificationPayload = new HashMap<>();
            notificationPayload.put("type", "cohost_removed");
            notificationPayload.put("message", username + " has been removed as co-host");
            notificationPayload.put("participant_id", participantId);
            notificationPayload.put("isCoHost", false);
            notificationPayload.put("username", username);
            notificationPayload.put("timestamp", Instant.now().toString());

            String notificationMessage = mapper.writeValueAsString(notificationPayload);
            TextMessage textMessage = new TextMessage(notificationMessage);

            // 5. Broadcast notification to all participants and host
            // Send to participants
            for (Map<String, Object> participant : participantsMap.values()) {
                String pid = (String) participant.get("participantId");
                String sessionKey = "event_participant_session:" + pid;
                String sessionId = stringRedisTemplate.opsForValue().get(sessionKey);

                if (sessionId != null) {
                    WebSocketSession session = sessionManager.getSession(sessionId);
                    if (session != null && session.isOpen()) {
                        try {
                            session.sendMessage(textMessage);
                        } catch (IOException e) {
                            stringRedisTemplate.delete(sessionKey);
                            sessionManager.removeSession(sessionId);
                            System.out.println("Cleaned up disconnected participant session: " + pid);
                        }
                    }
                }
            }

            // Send to host
            Map<String, Object> hostDetails = (Map<String, Object>) roomData.get("hostDetails");
            if (hostDetails != null) {
                String hostId = (String) roomData.get("hostId");
                String hostSessionKey = "event_host_session:" + hostId;
                String hostSessionId = stringRedisTemplate.opsForValue().get(hostSessionKey);

                if (hostSessionId != null) {
                    WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                    if (hostSession != null && hostSession.isOpen()) {
                        try {
                            hostSession.sendMessage(textMessage);
                        } catch (IOException e) {
                            System.out.println("Failed to send to host: " + e.getMessage());
                            stringRedisTemplate.delete(hostSessionKey);
                            sessionManager.removeSession(hostSessionId);
                        }
                    }
                }
            }

            // 6. Broadcast updated participant list to everyone
            broadcastParticipantList(eventId);

        } catch (Exception e) {
            System.out.println("Error removing co-host: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleUserCohost(String eventId, String participantId) {
        try {
            // 1. Fetch room data from Redis
            String redisKey = "event_room:" + eventId;
            String eventJson = stringRedisTemplate.opsForValue().get(redisKey);

            if (eventJson == null || eventJson.isEmpty()) {
                System.out.println("Room not found for eventId: " + eventId);
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> roomData = mapper.readValue(eventJson, new TypeReference<>() {
            });
            Map<Integer, Map<String, Object>> participantsMap = (Map<Integer, Map<String, Object>>) roomData
                    .get("participants");

            if (participantsMap == null || participantsMap.isEmpty()) {
                System.out.println("No participants found in room");
                return;
            }

            // 2. Find and update the participant's co-host status to false
            boolean participantFound = false;
            String username = null;
            for (Map.Entry<Integer, Map<String, Object>> entry : participantsMap.entrySet()) {
                Map<String, Object> participant = entry.getValue();
                if (participantId.equals(participant.get("participantId"))) {
                    participant.put("is_cohost", false);
                    username = (String) participant.get("username");
                    participantFound = true;
                    break;
                }
            }

            if (!participantFound) {
                System.out.println("Participant not found in room: " + participantId);
                return;
            }

            // 3. Update Redis with the modified participant data
            roomData.put("participants", participantsMap);
            stringRedisTemplate.opsForValue().set(redisKey, mapper.writeValueAsString(roomData));

            // 4. Prepare removal notification
            Map<String, Object> notificationPayload = new HashMap<>();
            notificationPayload.put("type", "cohost_removed");
            notificationPayload.put("message", username + " is not longer co-host");
            notificationPayload.put("participant_id", participantId);
            notificationPayload.put("isCoHost", false);
            notificationPayload.put("username", username);
            notificationPayload.put("timestamp", Instant.now().toString());

            String notificationMessage = mapper.writeValueAsString(notificationPayload);
            TextMessage textMessage = new TextMessage(notificationMessage);

            // 5. Broadcast notification to all participants and host
            // Send to participants
            for (Map<String, Object> participant : participantsMap.values()) {
                String pid = (String) participant.get("participantId");
                String sessionKey = "event_participant_session:" + pid;
                String sessionId = stringRedisTemplate.opsForValue().get(sessionKey);

                if (sessionId != null) {
                    WebSocketSession session = sessionManager.getSession(sessionId);
                    if (session != null && session.isOpen()) {
                        try {
                            session.sendMessage(textMessage);
                        } catch (IOException e) {
                            stringRedisTemplate.delete(sessionKey);
                            sessionManager.removeSession(sessionId);
                            System.out.println("Cleaned up disconnected participant session: " + pid);
                        }
                    }
                }
            }

            // Send to host
            Map<String, Object> hostDetails = (Map<String, Object>) roomData.get("hostDetails");
            if (hostDetails != null) {
                String hostId = (String) roomData.get("hostId");
                String hostSessionKey = "event_host_session:" + hostId;
                String hostSessionId = stringRedisTemplate.opsForValue().get(hostSessionKey);

                if (hostSessionId != null) {
                    WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                    if (hostSession != null && hostSession.isOpen()) {
                        try {
                            hostSession.sendMessage(textMessage);
                        } catch (IOException e) {
                            System.out.println("Failed to send to host: " + e.getMessage());
                            stringRedisTemplate.delete(hostSessionKey);
                            sessionManager.removeSession(hostSessionId);
                        }
                    }
                }
            }

            // 6. Broadcast updated participant list to everyone
            broadcastParticipantList(eventId);

        } catch (Exception e) {
            System.out.println("Error removing co-host: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleChatMessage(String eventId, String message, String participantId) {
        try {
            long userIdLong = Long.parseLong(participantId);
            UserDataDTO userdetails = userServiceClient.getUserById(userIdLong);

            // 1. Save to DB
            ChatMessage chatMessage = new ChatMessage();
            chatMessage.setRoomId(eventId);
            chatMessage.setUserId(userIdLong);
            chatMessage.setUsername(userdetails.getData().getUsername());
            chatMessage.setMessage(message);
            chatMessageRepository.save(chatMessage);

            // 2. Prepare payload
            Map<String, Object> chatPayload = new HashMap<>();
            chatPayload.put("type", "chat_message");
            chatPayload.put("message", message);
            chatPayload.put("username", userdetails.getData().getUsername());
            chatPayload.put("user_id", participantId);
            chatPayload.put("timestamp", Instant.now().toString());

            TextMessage textMessage = new TextMessage(new ObjectMapper().writeValueAsString(chatPayload));

            // 3. Send to all participants and host using sessionManager
            for (WebSocketSession session : sessionManager.getAllSessionsByEvent(eventId)) {
                if (session != null && session.isOpen()) {
                    try {
                        session.sendMessage(textMessage);
                    } catch (IOException e) {
                        sessionManager.removeSession(session.getId());
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Error handling chat message: " + e.getMessage());
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
            payload.put("messages", messages);

            String jsonPayload = new ObjectMapper().writeValueAsString(payload);
            TextMessage textMessage = new TextMessage(jsonPayload);

            // 3. Send to target participant
            WebSocketSession session = sessionManager.getSessionByParticipantId(targetParticipantId);
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
            // 1. Get room data
            String redisKey = "event_room:" + eventId;
            String eventJson = stringRedisTemplate.opsForValue().get(redisKey);

            if (eventJson == null)
                return;

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> roomData = mapper.readValue(eventJson, new TypeReference<>() {
            });
            Map<Integer, Map<String, Object>> participants = (Map<Integer, Map<String, Object>>) roomData
                    .get("participants");
            String hostId = (String) roomData.get("hostId");

            // 2. Update speaking status in Redis
            if (participants != null) {
                for (Map.Entry<Integer, Map<String, Object>> entry : participants.entrySet()) {
                    Map<String, Object> participant = entry.getValue();
                    if (participantId.equals(participant.get("participantId"))) {
                        participant.put("isSpeaking", isSpeaking);
                        break;
                    }
                }
                roomData.put("participants", participants);
                stringRedisTemplate.opsForValue().set(redisKey, mapper.writeValueAsString(roomData));
            }

            // 3. Prepare different messages for participants and host Message for participants
            Map<String, Object> participantMessage = new HashMap<>();
            participantMessage.put("type", "cohost_speaking_update");
            participantMessage.put("participant_id", participantId);
            participantMessage.put("isSpeaking", isSpeaking);
            String participantJson = mapper.writeValueAsString(participantMessage);
            TextMessage participantTextMessage = new TextMessage(participantJson);

            // Special message for host
            Map<String, Object> hostMessage = new HashMap<>();
            hostMessage.put("type", "host_cohost_speaking");
            hostMessage.put("cohost_id", participantId);
            hostMessage.put("isSpeaking", isSpeaking);
            // Include cohost username for better UX
            @SuppressWarnings("null")
            String cohostUsername = participants.values().stream()
                    .filter(p -> participantId.equals(p.get("participantId")))
                    .findFirst()
                    .map(p -> (String) p.get("username"))
                    .orElse("Co-host");
            hostMessage.put("cohost_username", cohostUsername);
            String hostJson = mapper.writeValueAsString(hostMessage);
            TextMessage hostTextMessage = new TextMessage(hostJson);

            // 4. Broadcast messages
            if (participants != null) {
                // Send to all participants (including host)
                for (Map<String, Object> participant : participants.values()) {
                    String pid = (String) participant.get("participantId");
                    String sessionKey = "event_participant_session:" + pid;
                    String sessionId = stringRedisTemplate.opsForValue().get(sessionKey);

                    if (sessionId != null) {
                        WebSocketSession session = sessionManager.getSession(sessionId);
                        if (session != null && session.isOpen()) {
                            try {
                                // Send special message to host, regular message to others
                                if (pid.equals(hostId)) {
                                    session.sendMessage(hostTextMessage);
                                } else {
                                    session.sendMessage(participantTextMessage);
                                }
                            } catch (IOException e) {
                                stringRedisTemplate.delete(sessionKey);
                                sessionManager.removeSession(sessionId);
                            }
                        }
                    }
                }
            }

            // 5. Handle host mute/unmute instructions
            if (hostId != null) {
                String hostSessionKey = "event_host_session:" + hostId;
                String hostSessionId = stringRedisTemplate.opsForValue().get(hostSessionKey);

                if (hostSessionId != null) {
                    WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                    if (hostSession != null && hostSession.isOpen()) {
                        try {
                            // Send mute/unmute instruction
                            Map<String, Object> audioControl = new HashMap<>();
                            audioControl.put("type", "audio_control");
                            audioControl.put("shouldMute", isSpeaking);
                            hostSession.sendMessage(new TextMessage(mapper.writeValueAsString(audioControl)));
                        } catch (IOException e) {
                            System.out.println("Failed to send audio control to host: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Error handling cohost speaking status: " + e);
        }
    }


    
}
