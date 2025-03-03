package services

import (
	"errors"
	"event_service/internal/domain/model"
	"event_service/internal/repository"
	"fmt"
	"io"
	"mime/multipart"
	"os"
	"os/exec"
	"path/filepath"
	"github.com/google/uuid"
)

// EventService defines methods for managing events
type EventService interface {
	CreateEvent(event *model.Event, file *multipart.FileHeader) (string, error)
	GetAllEvents() ([]model.Event, error)
	GetEventByID(id uint) (*model.Event, error)
	UpdateEvent(event *model.Event) error
	ReplaceEventImage(id uint, file *multipart.FileHeader) (string, error)
	DeleteEventByID(id uint) error
	FindAllEventsByUserID(userID uint) ([]model.Event, error)
	DeleteEventsByUserID(ids []uint) error
}

type eventService struct {
	repo repository.EventRepository
}

// NewEventService initializes a new EventService
func NewEventService(repo repository.EventRepository) EventService {
	return &eventService{repo: repo}
}

// CreateEvent handles event creation and image file saving
func (s *eventService) CreateEvent(event *model.Event, file *multipart.FileHeader) (string, error) {

	// Ensure theme directory exists
	uploadDir := "theme"
	if _, err := os.Stat(uploadDir); os.IsNotExist(err) {
		err = os.MkdirAll(uploadDir, os.ModePerm)
		if err != nil {
			return "", errors.New("failed to create theme directory")
		}
	}

	// Generate a unique filename
	uniqueID := uuid.New().String()
	ext := filepath.Ext(file.Filename)
	newFilename := fmt.Sprintf("%s%s", uniqueID, ext)
	filePath := filepath.Join(uploadDir, newFilename)

	// Open the uploaded file
	srcFile, err := file.Open()
	if err != nil {
		return "", errors.New("failed to open uploaded file")
	}
	defer srcFile.Close()

	// Create a new file in the theme directory
	destFile, err := os.Create(filePath)
	if err != nil {
		return "", errors.New("failed to create destination file")
	}
	defer destFile.Close()

	// Copy the uploaded file content to the destination file
	if _, err := io.Copy(destFile, srcFile); err != nil {
		return "", errors.New("failed to save image file")
	}

	// Save file path in database
	event.Theme = filePath
	err = s.repo.Create(event)
	if err != nil {
		return "", errors.New("failed to create event")
	}

	return filePath, nil
}

// GetAllEvents retrieves all events
func (s *eventService) GetAllEvents() ([]model.Event, error) {
	return s.repo.FindAllEvents()
}

// GetEventByID retrieves an event by its ID
func (s *eventService) GetEventByID(id uint) (*model.Event, error) {
	event, err := s.repo.FindEventByID(id)
	if err != nil {
		return nil, errors.New("event not found")
	}
	return event, nil
}

func (s *eventService) ReplaceEventImage(id uint, file *multipart.FileHeader) (string, error) {
	// Fetch the latest event data from the database
	updatedEvent, err := s.GetEventByID(id)
	if err != nil {
		return "", errors.New("failed to fetch event from database")
	}

	// Extract old image path
	oldImagePath := updatedEvent.Theme

	// Delete the old image using exec.Command if it exists
	if oldImagePath != "" {
		_, fileName := filepath.Split(oldImagePath)
		oldImageFullPath := filepath.Join("theme", fileName)

		// Check if file exists before attempting to delete
		if _, err := os.Stat(oldImageFullPath); err == nil {
			// Use exec.Command to remove the file
			cmd := exec.Command("rm", "-f", oldImageFullPath)
			_ = cmd.Run() 
		}
	}

	// Generate new file path
	uniqueID := uuid.New().String()
	ext := filepath.Ext(file.Filename)
	newFilename := uniqueID + ext
	filePath := filepath.Join("theme", newFilename)

	// Ensure the folder exists
	if err := os.MkdirAll("theme", os.ModePerm); err != nil {
		return "", err
	}

	// Open and save the new file
	srcFile, err := file.Open()
	if err != nil {
		return "", err
	}
	defer srcFile.Close()

	destFile, err := os.Create(filePath)
	if err != nil {
		return "", err
	}
	defer destFile.Close()

	if _, err := io.Copy(destFile, srcFile); err != nil {
		return "", err
	}

	return filePath, nil
}

// DeleteEventByID deletes an event by its ID
func (s *eventService) DeleteEventByIDIn(id uint) error {
	return s.repo.DeleteEventByIDIn([]uint{id})
}

// FindAllEventsByUserID retrieves all events created by a specific user
func (s *eventService) FindAllEventsByUserID(userID uint) ([]model.Event, error) {
	return s.repo.FindAllEventByUserId(userID)
}

// DeleteEventsByUserID deletes multiple events by user ID and removes associated images
func (s *eventService) DeleteEventsByUserID(ids []uint) error {
	// Fetch events to get associated image paths
	events, err := s.repo.GetEventsByIDs(ids)
	if err != nil {
		return errors.New("failed to fetch events")
	}

	// Delete associated images
	for _, event := range events {
		if event.Theme != "" {
			_, fileName := filepath.Split(event.Theme)
			imagePath := filepath.Join("theme", fileName)

			if _, err := os.Stat(imagePath); err == nil {
				cmd := exec.Command("rm", "-f", imagePath)
				_ = cmd.Run()
			}
		}
	}

	// Delete events from the database
	return s.repo.DeleteEventByIDIn(ids)
}

// UpdateEvent updates an existing event
func (s *eventService) UpdateEvent(event *model.Event) error {
	return s.repo.UpdateEvent(event.ID, event)
}

// DeleteEventByID deletes a single event by ID
func (s *eventService) DeleteEventByID(id uint) error {
	// Fetch event to get the associated image path
	event, err := s.GetEventByID(id)
	if err != nil {
		return errors.New("event not found")
	}

	// Delete associated image if it exists
	if event.Theme != "" {
		_, fileName := filepath.Split(event.Theme)
		imagePath := filepath.Join("theme", fileName)

		if _, err := os.Stat(imagePath); err == nil {
			cmd := exec.Command("rm", "-f", imagePath)
			_ = cmd.Run() // Ignore errors to prevent function failure
		}
	}

	// Delete the event record from the database
	return s.repo.DeleteEventByID(id)
}
