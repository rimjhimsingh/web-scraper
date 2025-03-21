FROM maven:3.8-openjdk-11

WORKDIR /app

# Copy the project files
COPY . .

# Build the application
RUN mvn clean package

# Expose the port your app runs on
EXPOSE 8080

# Command to run the application
CMD ["mvn", "jetty:run", "-Djetty.http.port=$PORT"]
