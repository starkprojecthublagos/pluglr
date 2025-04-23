package com.example.streaming.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import com.example.streaming.enums.EventStatus;
import com.example.streaming.enums.StreamType;
import com.example.streaming.enums.VisibilityChoices;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class Event {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id")
    private String roomId;

    @Column(name = "host_id")
    private String hostId;
    
    @Column(name = "host_username", nullable = false)
    private String hostUsername;

    @Column(name = "total_participants")
    private Integer totalParticipants = 0;

    
    @Column(name = "host_session_id", nullable = false)
    private String hostSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private EventStatus status = EventStatus.active;

    @Column(name = "end_timestamp")
    private Instant endTimestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "stream_type")
    private StreamType streamType;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility")
    private VisibilityChoices visibility = VisibilityChoices.PUBLIC;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<Participant> participants = new ArrayList<>();

   
    @CreationTimestamp
    private LocalDateTime createdOn;

    @UpdateTimestamp
    private LocalDateTime updatedOn;


}