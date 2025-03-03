package migrations

import (
	"log"
	"event_service/internal/domain/model"
	"gorm.io/gorm"
)

// EventMigration is an exported function to handle database migrations
func EventMigration(db *gorm.DB) error {
	log.Println("Starting database migration...")
	err := db.AutoMigrate(
		&model.Event{}, 
	)
	if err == nil {
		log.Println("Database migrated successfully")
	}
	return err
}
