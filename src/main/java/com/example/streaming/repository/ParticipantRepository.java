package com.example.streaming.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.example.streaming.model.Participant;

@Repository
public interface ParticipantRepository extends JpaRepository<Participant, Long> {

    // Query by event's roomId
    @Query("SELECT p FROM Participant p WHERE p.event.roomId = :roomId")
    List<Participant> findByEventRoomId(@Param("roomId") String roomId);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END " +
                    "FROM Participant p WHERE p.event.roomId = :roomId AND p.userId = :userId")
    boolean existsByEventRoomIdAndUserId(@Param("roomId") String roomId,
                    @Param("userId") String userId);

    @Query("SELECT COUNT(p) FROM Participant p WHERE p.event.roomId = :roomId")
    int countByEventRoomId(@Param("roomId") String roomId);

    @Modifying
    @Query("DELETE FROM Participant p WHERE p.event.roomId = :roomId AND p.userId = :participantId")
    void deleteByEventRoomIdAndUserId(@Param("roomId") String roomId,
                    @Param("participantId") String participantId);

    @Query("SELECT p FROM Participant p JOIN FETCH p.event WHERE p.sessionId = :sessionId")
    Optional<Participant> findBySessionId(@Param("sessionId") String sessionId);

    @Query("SELECT p FROM Participant p JOIN FETCH p.event WHERE p.sessionId = :sessionId AND p.isCohost = true")
    Optional<Participant> findBySessionIdAndIsCohostTrue(@Param("sessionId") String sessionId);

    @Query("SELECT p.sessionId FROM Participant p WHERE p.event.roomId = :roomId")
    List<String> findSessionIdsByRoomId(@Param("roomId") String roomId);

    @Query("SELECT p FROM Participant p WHERE p.event.roomId = :eventId AND p.userId = :participantId")  
    List<Participant> findByRoomIdAndParticipantId(@Param("eventId") String eventId, @Param("participantId") String participantId);  

    @Query("SELECT p FROM Participant p JOIN FETCH p.event WHERE p.event.roomId = :eventId AND p.userId = :participantId")
    Optional<Participant> findByEventIdAndUserId(@Param("eventId") String eventId,
            @Param("participantId") String participantId);

    @Query("SELECT p.sessionId FROM Participant p WHERE p.userId = :participantId")
    String findSessionIdForParticipant(@Param("participantId") String participantId);

    @Query("SELECT p.sessionId FROM Participant p WHERE p.event.roomId = :roomId")
    List<Participant> findByRoomIdList(@Param("roomId") String roomId);

}
