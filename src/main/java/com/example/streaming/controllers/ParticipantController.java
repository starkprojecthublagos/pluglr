package com.example.streaming.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ParticipantController {
    
    @GetMapping("/participant") 
    public String participant() {
        return "participants"; 
    }

    @GetMapping("/host") 
    public String host() {
        return "audio"; 
    }
}
