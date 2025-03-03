package main

import (
	"event_service/config"
	"event_service/controller"
	"event_service/db"
	"event_service/internal/handler"
	"event_service/internal/repository"
	"event_service/internal/services"
	"event_service/migrations"
	"event_service/routers"
	"log"
	"os"
	"time"

	"github.com/gin-contrib/cors"
	"github.com/gin-gonic/gin"
	"github.com/joho/godotenv"
)


func main() {
    // Load .env file
    if err := godotenv.Load(); err != nil {
        log.Fatal("Error loading .env file")
    }

    // Load configuration
    cfg := config.LoadConfig()

    // Connect to the database
    database, err := db.ConnectDatabase(cfg)
    if err != nil {
        log.Fatalf("Failed to connect to database: %v", err)
    }

    // Migrate model
    if err := migrations.EventMigration(database); err != nil {
        log.Fatalf("Database migration failed: %v", err)
    }

    // Retrieve the JWT secret key from the environment variable
    jwtSecretKey := os.Getenv("JWT_SECRET_KEY")
    if jwtSecretKey == "" {
        log.Fatal("JWT_SECRET_KEY is not set in .env file")
    }

    // Create router
    router := gin.Default()

	// Custom CORS configuration
	router.Use(cors.New(cors.Config{
		AllowOrigins:     []string{"*"}, // Allow all origins, update as needed
		AllowMethods:     []string{"GET", "POST", "PUT", "DELETE", "OPTIONS"},
		AllowHeaders:     []string{"Authorization", "Content-Type"},
		ExposeHeaders:    []string{"Content-Length"},
		AllowCredentials: true,
		MaxAge:           12 * time.Hour,
	}))
	
    // Initialize dependencies
    eventRepo := repository.NewEventRepository(database) 
    eventService := services.NewEventService(eventRepo)
    eventHandler := handler.NewEventHandler(eventService)
	eventController := controller.NewEventController(eventHandler)

    // Register all routes by passing the router and dependencies
    routers.RegisterRoutes(router, eventController) 

    // Start the server
    if err := router.Run(":8084"); err != nil {
        log.Fatalf("Error starting server: %v", err)
    }
    gin.SetMode(gin.ReleaseMode)
}