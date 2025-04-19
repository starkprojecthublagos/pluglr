# Use an official OpenJDK runtime as a parent image
FROM eclipse-temurin:17-jdk-alpine
# Set the working directory
WORKDIR /app

# Copy the built JAR from the host machine into the container
COPY target/*.jar app.jar

# Expose port 
EXPOSE 8011

# Run the jar file
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
