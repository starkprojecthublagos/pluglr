package com.example.streaming.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

@RestController
public class HomeController {

    @GetMapping("/index")
    public ResponseEntity<?> home() {
        return ResponseEntity.ok("Welcome Back.!");
    }


}
