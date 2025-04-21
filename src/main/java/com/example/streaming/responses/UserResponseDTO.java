package com.example.streaming.responses;

import com.example.streaming.dtos.UserDataDTO;

public class UserResponseDTO {
    private UserDataDTO data;

    public UserDataDTO getData() {
        return data;
    }

    public void setData(UserDataDTO data) {
        this.data = data;
    }
}
