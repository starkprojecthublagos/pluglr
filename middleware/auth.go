package middleware

import (
	"encoding/json"
	"event_service/dto"
	"fmt"
	"net/http"
	"os"
	"strings"
	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v4"
)


type ErrorResponse struct {
	Code   string `json:"code"`
	Detail string `json:"detail"`
	Title  string `json:"title"`
	Status int    `json:"status"`
}

func AuthenticationMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			errorResponse := ErrorResponse{
				Code:   "Unauthorized Access",
				Detail: "FORBIDDEN ACCESS",
				Title:  "Authentication Error",
				Status: http.StatusUnauthorized,
			}
			c.JSON(http.StatusUnauthorized, gin.H{"error": errorResponse})
			c.Abort()
			return
		}

		// Retrieve the secret key as plaintext (not Base64 encoded)
		jwtSecretKey := os.Getenv("JWT_SECRET_KEY")
		if jwtSecretKey == "" {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "JWT_SECRET_KEY not set"})
			c.Abort()
			return
		}

		// Ensure token format is correct
		parts := strings.Split(authHeader, " ")
		if len(parts) != 2 || parts[0] != "Bearer" {
			errorResponse := ErrorResponse{
				Code:   "invalid_token_format",
				Detail: "Authorization header format must be 'Bearer <token>'",
				Title:  "Authentication Error",
				Status: http.StatusUnauthorized,
			}
			c.JSON(http.StatusUnauthorized, gin.H{"error": errorResponse})
			c.Abort()
			return
		}

		tokenString := parts[1]

		// Parse and validate the token
		token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
			if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
			}
			return []byte(jwtSecretKey), nil 
		})

		if err != nil || !token.Valid {
			errorResponse := ErrorResponse{
				Code:   "invalid_token",
				Detail: fmt.Sprintf("Token error: %v", err),
				Title:  "Authentication Error",
				Status: http.StatusUnauthorized,
			}
			c.JSON(http.StatusUnauthorized, gin.H{"error": errorResponse})
			c.Abort()
			return
		}

		// Extract user ID from claims
		claims, ok := token.Claims.(jwt.MapClaims)
		if !ok {
			errorResponse := ErrorResponse{
				Code:   "invalid_token_claims",
				Detail: "Invalid token claims",
				Title:  "Authentication Error",
				Status: http.StatusUnauthorized,
			}
			c.JSON(http.StatusUnauthorized, gin.H{"error": errorResponse})
			c.Abort()
			return
		}

		userID, ok := claims["user_id"].(string)
		if !ok {
			errorResponse := ErrorResponse{
				Code:   "invalid_token_claims",
				Detail: "User ID not found in token claims",
				Title:  "Authentication Error",
				Status: http.StatusUnauthorized,
			}
			c.JSON(http.StatusUnauthorized, gin.H{"error": errorResponse})
			c.Abort()
			return
		}

		// Fetch user details from authentication service
		authUrl := fmt.Sprintf("http://localhost:8000/api/v1/user/id/%s", userID)
		resp, err := http.Get(authUrl)
		if err != nil || resp.StatusCode != http.StatusOK {
			errorResponse := ErrorResponse{
				Code:   "user_not_found",
				Detail: "User not found in Authentication Service",
				Title:  "Authentication Error",
				Status: http.StatusUnauthorized,
			}
			c.JSON(http.StatusUnauthorized, gin.H{"error": errorResponse})
			c.Abort()
			return
		}
		defer resp.Body.Close()

		var user dto.UserDTO
		if err := json.NewDecoder(resp.Body).Decode(&user); err != nil {
			errorResponse := ErrorResponse{
				Code:   "unmarshal_error",
				Detail: "Failed to parse user data",
				Title:  "Authentication Error",
				Status: http.StatusInternalServerError,
			}
			c.JSON(http.StatusInternalServerError, gin.H{"error": errorResponse})
			c.Abort()
			return
		}

		// Store user in context
		c.Set("user", user)
		c.Next()
	}
}
