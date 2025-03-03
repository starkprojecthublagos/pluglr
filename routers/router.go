package routers

import (
    "event_service/controller"
    "event_service/middleware"
    "github.com/gin-gonic/gin"
)

// RegisterRoutes initializes the routes for the application
func RegisterRoutes(router *gin.Engine, eventController *controller.EventController) {

    // Private Routes (requires JWT authentication)
    privateRoutes := router.Group("/")
    privateRoutes.Use(middleware.AuthenticationMiddleware())
    {
        privateRoutes.GET("/", eventController.DefaultHome)
        privateRoutes.POST("/api/v1/event/create", eventController.CreateEvent)
        privateRoutes.GET("/api/v1/event/all", eventController.GetAllEvents)
        privateRoutes.PUT("/api/v1/event/:id", eventController.UpdateEvent)
        privateRoutes.GET("/api/v1/event/:id", eventController.GetEventById)
        privateRoutes.GET("/api/v1/event/all/user/:user_id", eventController.FindAllEventByUserID)
		privateRoutes.DELETE("/api/v1/event/user", eventController.DeleteEventsByUserID)
        privateRoutes.DELETE("/api/v1/event/:id", eventController.DeleteEventByID)
    }
}