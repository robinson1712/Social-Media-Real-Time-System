# Social Media Backend (Facebook-like) — Java Spring Boot Microservices

Backend microservice cho một mạng xã hội kiểu Facebook: post, avatar, story, reels, comment,
reaction, block, group, fanpage, dating, nhắn tin real-time. Kiến trúc hướng đối tượng, phục vụ
chung cho cả web và mobile qua REST API + WebSocket.

## Kiến trúc

```
Client (Web/Mobile)
        │
        ▼
  API Gateway (8080)  ── xác thực JWT, forward X-User-Id/X-User-Roles xuống downstream
        │
        ▼
  Eureka Service Discovery (8761)
        │
        ├── auth-service (8081)          Postgres: auth_db
        ├── user-service (8082)          Postgres: user_db
        ├── media-service (8083)         Postgres: media_db + MinIO
        ├── post-service (8084)          Postgres: post_db
        ├── comment-service (8085)       Postgres: comment_db
        ├── reaction-service (8086)      Postgres: reaction_db
        ├── story-service (8087)         MongoDB: story_db
        ├── reels-service (8088)         MongoDB: reels_db
        ├── group-service (8089)         Postgres: group_db
        ├── fanpage-service (8090)       Postgres: fanpage_db
        ├── dating-service (8091)        Postgres: dating_db
        ├── chat-service (8092)          MongoDB: chat_db + Redis (presence) — WebSocket /ws
        ├── notification-service (8093)  MongoDB: notification_db — WebSocket /ws
        └── feed-service (8094)          Redis (sorted set fanout)

  Config Server (8888) — cấp config tập trung (native, đọc từ ./config-repo)
  Kafka — giao tiếp bất đồng bộ giữa các service (post/comment/reaction/group/page/match/message events)
```

Chi tiết đầy đủ: xem `docs/plan.md` hoặc lịch sử trò chuyện đã tạo ra hệ thống này.

## Công nghệ

- Java 17, Spring Boot 3.3.4, Spring Cloud 2023.0.3
- Spring Web MVC (business services) + Spring Cloud Gateway/WebFlux (api-gateway)
- Spring Data JPA (Postgres) / Spring Data MongoDB / Spring Data Redis
- Spring Kafka, Spring Cloud OpenFeign, Spring WebSocket (STOMP)
- JWT (jjwt) tự issue ở `auth-service`, xác thực tập trung ở `api-gateway`
- MinIO (S3-compatible) cho lưu trữ media
- Docker Compose cho toàn bộ hạ tầng + service

## Chạy toàn bộ hệ thống

### 1. Build tất cả module (bắt buộc trước khi build Docker image, vì Dockerfile chỉ copy jar đã build sẵn)

```bash
mvn -q -B clean package -DskipTests
```

### 2. Chạy bằng Docker Compose

```bash
docker compose up -d --build
```

Chờ khoảng 1-2 phút để Postgres/Mongo/Kafka/MinIO/Eureka khởi động xong trước khi các service
đăng ký thành công vào Eureka. Kiểm tra: http://localhost:8761 (Eureka dashboard).

### 3. Test nhanh luồng end-to-end

```bash
# Đăng ký + đăng nhập
curl -X POST http://localhost:8080/api/auth/register -H "Content-Type: application/json" \
  -d '{"email":"a@test.com","password":"123456","fullName":"Nguyen Van A"}'

# Lấy accessToken từ response trên, dùng cho các request tiếp theo
TOKEN=<accessToken>

# Tạo post
curl -X POST http://localhost:8080/api/posts -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"content":"Hello world","privacy":"PUBLIC"}'

# Xem feed
curl http://localhost:8080/api/feed/me -H "Authorization: Bearer $TOKEN"
```

WebSocket chat/notification: kết nối STOMP over SockJS tới `ws://localhost:8080/ws?token=$TOKEN`.

### Chạy từng service riêng lẻ khi phát triển (không qua Docker)

Cần Postgres/MongoDB/Redis/Kafka/MinIO chạy sẵn (có thể `docker compose up postgres mongodb redis kafka minio -d`
rồi chạy service bằng `mvn spring-boot:run` trong từng thư mục `services/<name>`), theo thứ tự:
`eureka-server` → `config-server` → các business service → `api-gateway`.

## Giới hạn hiện tại (đã thống nhất với người dùng khi lên kế hoạch)

Đây là bộ khung production-grade về mặt kiến trúc nhưng logic nghiệp vụ ở mức cơ bản. Chưa có:
feed ranking thông minh, thuật toán match dating nâng cao, kiểm duyệt nội dung, rate-limit chi
tiết/circuit breaker (Resilience4j), test coverage, CI/CD, observability/tracing (Zipkin/OTel).
Một vài đơn giản hoá có chủ đích (ghi rõ trong code bằng comment) để giữ phạm vi khả thi cho lượt
scaffold đầu tiên, ví dụ: `reaction-service` nhận `targetOwnerId` trực tiếp từ client thay vì tự
resolve qua Feign; `group-service`/`fanpage-service` list công khai chưa lọc private group mà user
đã là thành viên.
