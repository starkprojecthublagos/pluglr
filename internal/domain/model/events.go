package model

import "time"

type Event struct {
	ID          uint      `json:"id" gorm:"primaryKey;autoIncrement"`
	UserId      uint      `json:"user_id" gorm:"not null"` 
	Title       string    `json:"title" gorm:"type:text;not null"`
	StartTime   time.Time `json:"start_time" gorm:"not null"`
	EndTime     time.Time `json:"end_time" gorm:"not null"`
	Category    string    `json:"category" gorm:"type:varchar(100);not null"`
	Description string    `json:"description" gorm:"type:text"`
	Theme       string    `json:"theme" gorm:"type:text;not null"`
	CreatedAt   time.Time `json:"created_at" gorm:"autoCreateTime"`
	UpdatedAt   time.Time `json:"updated_at" gorm:"autoUpdateTime"`
}

