package com.example.streaming.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "participants") 
public class Participant { 

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) 
    @JoinColumn(name = "event_id", nullable = false) 
    private Event event; 

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "is_reconnect", nullable = false)
    private boolean isReconnect;

    @Column(name = "is_cohost", nullable = false)
    private boolean isCohost;

    @Column(name = "is_speaking")
    private boolean isSpeaking;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "username")
    private String username;
}