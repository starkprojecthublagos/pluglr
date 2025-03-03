package controller

import (
	"event_service/internal/handler"
	"net/http"

	"github.com/gin-gonic/gin"
)

// EventController handles event-related API requests
type EventController struct {
	handler *handler.EventHandler
}

// NewEventController initializes a new EventController
func NewEventController(handler *handler.EventHandler) *EventController {
	return &EventController{handler: handler}
}

// CreateEvent handles creating a new event
func (ec *EventController) CreateEvent(c *gin.Context) {
	ec.handler.CreateEvent(c) 
}


// GetAllEvents retrieves all events
func (ec *EventController) GetAllEvents(c *gin.Context) {
	ec.handler.GetAllEvents(c)
}
// Get Event By EventId
func (ec *EventController) GetEventById(c *gin.Context) {
	ec.handler.GetEventByID(c)
}

// FindAllEventByUserID retrieves all events for a specific user
func (ec *EventController) FindAllEventByUserID(c *gin.Context) {
	ec.handler.FindAllEventByUserID(c)
}

// DeleteEventByID handles deleting an event by its ID
func (ec *EventController) DeleteEventByID(c *gin.Context) {
	ec.handler.DeleteEventByID(c)
}

// DeleteEventsByUserID handles deleting multiple events by user ID
func (ec *EventController) DeleteEventsByUserID(c *gin.Context) {
	ec.handler.DeleteEventsByUserId(c)
}

// UpdateEvent  by id
func (ec *EventController) UpdateEvent(c *gin.Context) {
	ec.handler.UpdateEvent(c)
}

// defaultHome handles the default home route
func (bc *EventController) DefaultHome(c *gin.Context) {
    // Create a simple response
    response := gin.H{
        "message": "Welcome to the Event Service!",
        "status":  "success",
    }
    // Return JSON response
    c.JSON(http.StatusOK, response)
}