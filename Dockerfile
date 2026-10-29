FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY . .

RUN chmod +x mvnw 2>/dev/null || true

EXPOSE 8080

CMD ["sh", "-c", "java -jar target/*.jar"]
