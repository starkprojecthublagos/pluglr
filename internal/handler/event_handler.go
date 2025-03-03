package handler

import (
	"event_service/exceptions"
	"event_service/internal/domain/model"
	"event_service/internal/services"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
)

type EventHandler struct {
	service services.EventService
}

func NewEventHandler(service services.EventService) *EventHandler {
	return &EventHandler{service: service}
}

// CreateEvent handles creating a new event
func (h *EventHandler) CreateEvent(c *gin.Context) {
	// Parse form data
	var event model.Event

	// Parse user_id
	userID, err := strconv.ParseUint(c.PostForm("user_id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid user_id"})
		return
	}
	event.UserId = uint(userID)

	// Parse time fields (ISO 8601 format)
	if event.StartTime, err = time.Parse(time.RFC3339, c.PostForm("start_time")+":00Z"); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid start_time format"})
		return
	}

	if event.EndTime, err = time.Parse(time.RFC3339, c.PostForm("end_time")+":00Z"); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid end_time format"})
		return
	}

	// Bind other fields
	event.Title = c.PostForm("title")
	event.Category = c.PostForm("category")
	event.Description = c.PostForm("description")

	// Handle file upload
	file, err := c.FormFile("image")
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Image file is required"})
		return
	}

	// Call service to create event
	imagePath, err := h.service.CreateEvent(&event, file)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to create event"})
		return
	}

	c.JSON(http.StatusCreated, gin.H{
		"message": "Event created successfully",
		"image":   imagePath,
		"event":   event,
	})
}


// GetEventByID retrieves an event by ID
func (h *EventHandler) GetEventByID(c *gin.Context) {
	id, err := strconv.ParseUint(c.Param("id"), 10, 32)
	if err != nil {
		c.JSON(http.StatusBadRequest, exceptions.ErrorResponse{
			Message: "Invalid event ID",
			Details: err.Error(),
		})
		return
	}

	event, err := h.service.GetEventByID(uint(id))
	if err != nil {
		c.JSON(http.StatusNotFound, exceptions.ErrorResponse{
			Message: "Event not found",
			Details: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, event)
}

// GetAllEvents retrieves all events
func (h *EventHandler) GetAllEvents(c *gin.Context) {
	events, err := h.service.GetAllEvents()
	if err != nil {
		c.JSON(http.StatusInternalServerError, exceptions.ErrorResponse{
			Message: "Failed to retrieve events",
			Details: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, events)
}

// UpdateEvent updates an existing event by ID
func (h *EventHandler) UpdateEvent(c *gin.Context) {
	id, err := strconv.ParseUint(c.Param("id"), 10, 32)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid event ID"})
		return
	}

	// Fetch existing event
	event, err := h.service.GetEventByID(uint(id))
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Event not found"})
		return
	}

	// Parse form data
	userID, err := strconv.ParseUint(c.PostForm("user_id"), 10, 64)
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid user_id"})
		return
	}
	event.UserId = uint(userID)
	event.Title = c.PostForm("title")
	event.Category = c.PostForm("category")
	event.Description = c.PostForm("description")

	// Parse time fields
	if event.StartTime, err = time.Parse(time.RFC3339, c.PostForm("start_time")+":00Z"); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid start_time format"})
		return
	}

	if event.EndTime, err = time.Parse(time.RFC3339, c.PostForm("end_time")+":00Z"); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid end_time format"})
		return
	}

	// Check if a new image is uploaded
	file, err := c.FormFile("image")
	if err == nil {
		// Replace old image
		imagePath, err := h.service.ReplaceEventImage(uint(id), file)
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to update image"})
			return
		}
		event.Theme = imagePath
	}

	// Update the event
	if err := h.service.UpdateEvent(event); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "Failed to update event"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Event updated successfully", "event": event})
}

// DeleteEventByID handles deleting an event by its ID
func (h *EventHandler) DeleteEventByID(c *gin.Context) {
	id, err := strconv.ParseUint(c.Param("id"), 10, 32)
	if err != nil {
		c.JSON(http.StatusBadRequest, exceptions.ErrorResponse{
			Message: "Invalid event ID",
			Details: err.Error(),
		})
		return
	}

	err = h.service.DeleteEventByID(uint(id)) 
	if err != nil {
		c.JSON(http.StatusInternalServerError, exceptions.ErrorResponse{
			Message: "Failed to delete event",
			Details: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Event deleted successfully"})
}

// FindAllEventByUserID retrieves all events for a user
func (h *EventHandler) FindAllEventByUserID(c *gin.Context) {
	userID, err := strconv.ParseUint(c.Param("user_id"), 10, 32)
	if err != nil {
		c.JSON(http.StatusBadRequest, exceptions.ErrorResponse{
			Message: "Invalid user ID",
			Details: err.Error(),
		})
		return
	}

	events, err := h.service.FindAllEventsByUserID(uint(userID))
	if err != nil {
		c.JSON(http.StatusInternalServerError, exceptions.ErrorResponse{
			Message: "Failed to retrieve events",
			Details: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, events)
}

// DeleteEventsByUserId deletes multiple events by user ID
func (h *EventHandler) DeleteEventsByUserId(c *gin.Context) {
	var request struct {
		IDs []uint `json:"ids"`
	}

	if err := c.ShouldBindJSON(&request); err != nil {
		c.JSON(http.StatusBadRequest, exceptions.ErrorResponse{
			Message: "Invalid request payload",
			Details: err.Error(),
		})
		return
	}

	err := h.service.DeleteEventsByUserID(request.IDs)
	if err != nil {
		c.JSON(http.StatusInternalServerError, exceptions.ErrorResponse{
			Message: "Failed to delete events",
			Details: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "Events deleted successfully"})
}

