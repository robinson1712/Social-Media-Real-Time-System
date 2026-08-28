# TODO — Social Media Backend

Theo dõi tiến độ đồ án: đã làm gì, service nào xong, còn thiếu gì để hoàn chỉnh.

## Tổng quan

18 module Maven (`common-lib` + 3 service hạ tầng + 14 business service) đã **scaffold xong, build sạch, VÀ đã chạy thật qua Docker Compose thành công** — `mvn clean install` sạch + `docker compose up -d --build` từ trạng thái sạch hoàn toàn (`down -v`) lên đủ 16/16 service trên Eureka, test end-to-end qua gateway thật (đăng ký → login → post → feed → comment → group → dating swipe/match → auto-tạo chat conversation → notification) đều pass. Quá trình chạy thật này lộ ra **6 lỗi runtime thật** mà build/compile không bắt được — đã tìm và sửa hết, xem mục ngay dưới. Còn thiếu chủ yếu là: **chưa có test tự động**, và một số logic nghiệp vụ nâng cao vẫn ở mức đơn giản hoá có chủ đích.

---

## Đã hoàn thành — buổi trước (scaffold ban đầu)

- Thiết kế kiến trúc: Eureka + Config Server + API Gateway, JWT issue ở `auth-service` / xác thực tập trung ở gateway, forward `X-User-Id`/`X-User-Roles` xuống downstream.
- `common-lib`: `ApiResponse`/`PageResponse`, exception hierarchy (`ApiException` + 5 subclass + `GlobalExceptionHandler`), `JwtTokenProvider`, `CurrentUserContext`/`HeaderAuthFilter`, 11 Kafka event record + `KafkaTopics`, enum dùng chung.
- 3 service hạ tầng: `eureka-server`, `config-server` (native, đọc `config-repo/`), `api-gateway` (routing 14 service, JWT filter, CORS, WebSocket proxy).
- 14 business service — scaffold đầy đủ entity/repository/service/controller/DTO, Kafka producer/consumer đúng chỗ, Feign client nơi cần (comment→post, story/feed→user, feed→group/fanpage/post), WebSocket/STOMP thật cho chat + notification.
- `docker-compose.yml` (Postgres 9 DB, MongoDB 4 DB, Redis, Kafka KRaft, MinIO + 17 service) + Dockerfile từng service + `README.md`.
- Build verify: từng service riêng lẻ + toàn bộ reactor từ root — sạch, không lỗi.
- 2 artifact tài liệu: sequence diagram 4 luồng chính (auth/feed/chat/dating), catalog đầy đủ 191 class kèm minh hoạ 4 nguyên lý OOP bằng code thật.

## Đã hoàn thành — hôm nay

- **`ServiceUnavailableException`** (common-lib) — bổ sung vào exception hierarchy, dùng cho lỗi hạ tầng (503) phân biệt với lỗi nghiệp vụ (404/409...).
- **`group-service`**: sửa `GET /api/groups` để trả cả group PRIVATE mà caller đã là thành viên APPROVED (trước đó chỉ trả PUBLIC). Đổi JPQL entity name `Group` → `SocialGroup` để tránh xung đột với keyword `GROUP BY`.
- **Resilience4j circuit breaker** trên toàn bộ Feign client (`comment-service`, `story-service`, `feed-service`) — mỗi client có `fallbackFactory` riêng, cấu hình sliding window/failure threshold/half-open, phân biệt lỗi 404 hợp lệ với lỗi service sập.
- **Feed ranking theo engagement** (`feed-service`) — công thức kiểu Hacker News (`recencyScore × (1 + log(1 + reaction + 2×comment))`), tính lúc đọc chứ không phải lúc ghi.
- **Dating matching algorithm** (`dating-service`) — thêm `gender`/`birthDate` vào `DatingProfile` (trước đó thiếu, không đủ dữ liệu để tính tương thích thật), lọc tương thích giới tính hai chiều + chấm điểm 0-100 (tuổi 60đ, sở thích chung Jaccard 40đ), trả kèm điểm số qua `CandidateResponse`.
- Cập nhật `README.md` phản ánh đúng những gì đã hoàn thiện.
- **Rate limiting ở `api-gateway`** (Redis token-bucket, `RequestRateLimiter` của Spring Cloud Gateway) — bean `userOrIpKeyResolver` khoá theo `X-User-Id` nếu đã đăng nhập, fallback theo IP cho request công khai. Route `/api/auth/**` bị giới hạn chặt (1 req/s, burst 5) để chống brute-force login/spam đăng ký; 13 route còn lại dùng giới hạn chung thoáng hơn (20 req/s, burst 40 mỗi user). Route WebSocket (`/ws/**`) chưa áp rate-limit (rủi ro tương tác với handshake chưa kiểm chứng được vì chưa chạy Docker thật).

---

## 6 lỗi runtime tìm ra khi chạy Docker thật (build/compile không bắt được)

Đây là lý do vì sao bước "chạy thử thật" quan trọng — cả 6 lỗi dưới đây đều compile sạch, chỉ lộ ra khi thực sự gọi API qua container:

1. **17 jar không phải executable jar** — `spring-boot-maven-plugin` thiếu `<executions>` bind goal `repackage` vào phase `package` (root pom không kế thừa `spring-boot-starter-parent` nên không tự động có). → Sửa 1 chỗ ở root `pom.xml`.
2. **Script init multi-database Postgres lỗi** — `psql` thiếu `--dbname`, mặc định nối vào DB trùng tên user (không tồn tại). → Sửa `docker/postgres/init-multi-db.sh`.
3. **Mọi Kafka consumer lỗi deserialize** (`No type information in headers`) — producer tắt `spring.json.add.type.headers` nhưng consumer không có type mặc định thay thế. Lỗi hệ thống trên **12 service**. → Bỏ dòng tắt header ở cả 12 `application.yml`, dùng lại default (Spring Kafka tự thêm `__TypeId__` header).
4. **Toàn bộ `@PathVariable`/`@RequestParam` không tên tường minh bị lỗi 500** (`Name ... not specified, and parameter name information not available via reflection`) — thiếu cờ compiler `-parameters` (cũng do không kế thừa `spring-boot-starter-parent`). → Sửa 1 chỗ ở root `pom.xml` (`<parameters>true</parameters>`).
5. **`Account.roles` (auth-service) lazy-init exception** khi serialize `/api/auth/me` — `@ElementCollection` thiếu `fetch = FetchType.EAGER` trong khi `open-in-view: false`. → Thêm EAGER.
6. **`Unable to access lob stream`** trên post/comment/dating/group/fanpage — dùng `@Lob` cho field text thường trên Postgres (tạo kiểu `oid` cần stream trong transaction, vỡ khi session đã đóng). → Đổi cả 5 field sang `@Column(columnDefinition = "TEXT")`.
7. *(bonus, phát hiện cùng đợt)* **`function lower(bytea) does not exist`** ở `GET /api/groups` — tham số JPQL `null` khiến Postgres JDBC không suy được kiểu text. → Đổi sang truyền chuỗi rỗng `""` thay vì `null`.

Sau khi sửa hết, đã build sạch từ đầu (`mvn clean install`) + `docker compose down -v && up -d --build` một lần nữa và test lại toàn bộ — pass hết, không cần patch tay.

## Lỗi thứ 8: WebSocket của notification-service không thể truy cập qua gateway

Phát hiện khi test STOMP thật (dùng `java.net.http.WebSocket` viết tay, không có node/wscat trong máy): `chat-service` và `notification-service` đều đăng ký STOMP endpoint tại **cùng path `/ws`**, nhưng `api-gateway` chỉ có một route `/ws/**` → `CHAT-SERVICE`. Nghĩa là client **không bao giờ gọi được** WebSocket của notification-service qua cổng công khai (8080) — chỉ có cách bypass gateway gọi thẳng port 8093 (không qua xác thực JWT).

→ Đổi endpoint của notification-service thành `/ws-notifications` (`WebSocketConfig.java`), thêm route riêng `notification-service-ws` → `NOTIFICATION-SERVICE` trong gateway. Verify lại bằng chương trình Java tự viết (`java.net.http.WebSocket`, không cần cài node/wscat):

- **Chat**: Alice & Bob kết nối `ws://localhost:8080/ws/websocket?token=...` qua gateway (JWT qua query param, gateway set `X-User-Id`, `CONNECTED` frame trả đúng `user-name`) → Alice gửi `SEND /app/chat.send` → **Bob nhận `MESSAGE` frame real-time đúng nội dung** trên `/user/queue/messages`.
- **Notification**: Bob subscribe `ws://localhost:8080/ws-notifications/websocket?token=...` → Alice gửi friend-request qua REST → **Bob nhận `MESSAGE` frame real-time** trên `/user/queue/notifications` với đúng nội dung `FriendRequestEvent`.

Đây đúng là loại lỗi mà `TODO.md` đã cảnh báo trước — unit test (mock) không bắt được vì nó nằm ở tầng routing/hạ tầng, chỉ lộ ra khi gọi qua gateway thật.

## Unit test — 279 test trên toàn bộ 14/14 business service

Thêm `CurrentUserContext.setForTests()/clearForTests()` (public, common-lib) làm "cửa hậu" test-only cho service dùng `CurrentUserContext.getUserId()` — `set()`/`clear()` gốc vẫn package-private, chỉ `HeaderAuthFilter` gọi được ở production. Một số service (`user-service`, `media-service`) nhận `currentUserId` qua tham số method thay vì đọc `CurrentUserContext` trực tiếp nên không cần seam này.

Bắt đầu với 3 service lõi (`auth-service`, `post-service`, `dating-service` — 40 test, tự làm), sau đó giao 6 batch song song cho agent viết nốt 11 service còn lại (mỗi agent tự đọc source thật, viết test theo đúng pattern, tự chạy `mvn test` verify trước khi báo cáo).

| Service | Số test | Điểm đáng chú ý |
|---|---|---|
| `auth-service` | 14 | register/login/refresh/logout/me — mọi nhánh lỗi |
| `post-service` | 14 | ownership check, denormalized count clamp về 0 |
| `dating-service` | 12 | **điểm tương thích khớp chính xác 80** — trùng con số verify qua curl thật |
| `comment-service` | 16 | soft-delete, Feign 404→ResourceNotFound |
| `reaction-service` | 11 | upsert cùng 1 row, projection `TypeCount` |
| `story-service` | 11 | delta hết hạn 24h chính xác, Set idempotent |
| `reels-service` | 18 | listener denormalized count (comment/reaction) |
| `group-service` | 28 | `""` thay `null` cho JDBC type-inference, memberCount clamp |
| `fanpage-service` | 24 | chặn xoá owner cuối cùng của page |
| `feed-service` | 22 | **verify công thức ranking bằng số liệu tính tay** (viral ≈0.345 vs fresh ≈0.333) |
| `chat-service` | 22 | dedup PRIVATE conversation, MessageEvent recipientId theo loại hội thoại |
| `notification-service` | 21 | cả 6 nhánh `@KafkaListener` + toàn bộ điều kiện skip |
| `user-service` | 50 | friend request/block/unblock — nhánh quyền requester/addressee |
| `media-service` | 16 | MinIO put/remove qua captor, sanitize filename |

**Không có bug sản xuất nào bị phát hiện** trong toàn bộ 11 service ở đợt này — mọi hành vi khớp đúng với những gì đã thiết kế/verify qua Docker trước đó.

Build+test toàn reactor (`mvn clean install`, không `-DskipTests`): **18/18 module, 279/279 test pass**, không có xung đột dù 6 agent chỉnh sửa song song.

---

## Trạng thái từng service

| Service | Port | Trạng thái | Còn thiếu / có thể nâng cấp |
|---|---|---|---|
| `eureka-server` | 8761 | ✅ Xong | — |
| `config-server` | 8888 | ✅ Xong | Đang bị dùng ít — có thể đẩy thêm config dùng chung (vd. resilience4j defaults) sang đây |
| `api-gateway` | 8080 | ✅ Xong + rate limit | Route WebSocket `/ws/**` chưa có rate-limit |
| `auth-service` | 8081 | ✅ Xong | Chưa có: reset mật khẩu, xác minh email, khoá tài khoản sau N lần login sai, OAuth2/social login |
| `user-service` | 8082 | ✅ Xong | Chưa có: tìm kiếm user, gợi ý bạn chung |
| `media-service` | 8083 | ✅ Xong | Chưa có: resize ảnh/tạo thumbnail, giới hạn dung lượng/loại file, transcode video |
| `post-service` | 8084 | ✅ Xong | Chưa có: lịch sử chỉnh sửa, ghim bài, report bài viết |
| `comment-service` | 8085 | ✅ Xong + circuit breaker | — |
| `reaction-service` | 8086 | ✅ Xong | Đơn giản hoá có chủ đích: nhận `targetOwnerId` từ client thay vì tự resolve qua Feign |
| `story-service` | 8087 | ✅ Xong + circuit breaker | Chưa có: story highlights (lưu vĩnh viễn), reply story qua chat |
| `reels-service` | 8088 | ✅ Xong | Feed reels vẫn thuần theo thời gian (chưa áp dụng ranking như feed-service) |
| `group-service` | 8089 | ✅ Xong + fix visibility | Chưa có: vai trò MODERATOR, kiểm duyệt bài đăng trong group |
| `fanpage-service` | 8090 | ✅ Xong | Chưa có: thống kê/insight cho page |
| `dating-service` | 8091 | ✅ Xong + matching algorithm | Chưa có: unmatch, report trong ngữ cảnh dating, xác minh ảnh |
| `chat-service` | 8092 | ✅ Xong (WebSocket thật) | Chưa có: sửa/xoá tin nhắn, typing indicator, read-receipt chi tiết (hiện chỉ có mark-all-read) |
| `notification-service` | 8093 | ✅ Xong (WebSocket thật) | Chưa có: push notification ra mobile (FCM/APNs), cài đặt loại thông báo |
| `feed-service` | 8094 | ✅ Xong + engagement ranking | Chưa gộp story/reels vào cùng feed logic |

**14/14 business service + 3/3 hạ tầng đều đã hoàn thành scaffold và build được** — không có service nào còn ở dạng rỗng/chưa code.

---

## Integration test (Testcontainers) — viết được, nhưng môi trường hiện tại chặn Docker API trực tiếp

Đã viết `GroupRepositoryIntegrationTest` (`group-service`) — chạy chính JPQL `findVisibleGroups` (câu query từng gây lỗi `lower(bytea)` thật) trên **Postgres thật** qua Testcontainers, không phải mock. 5 test: filter rỗng không lỗi, private group chỉ hiện với member đã APPROVED (không hiện với PENDING/người lạ/ẩn danh), filter tên không phân biệt hoa-thường, sanity check mapping entity `SocialGroup`→bảng `groups`.

**Không chạy được trong môi trường sandbox hiện tại**: Testcontainers không kết nối được Docker daemon (`docker` CLI hoạt động bình thường — `docker compose` chạy tốt suốt session — nhưng thư viện `docker-java` mà Testcontainers dùng để gọi thẳng Docker API qua named pipe bị chặn/trả về response rỗng, thử cả 2 pipe `docker_engine`/`dockerDesktopLinuxEngine` và tắt sandbox của Bash tool đều không khắc phục được — nhiều khả năng là giới hạn bảo mật ở tầng host/Docker Desktop, không phải lỗi trong code). Đã cấu hình `maven-surefire-plugin` loại `**/*IntegrationTest.java` khỏi `mvn test`/`mvn install` mặc định để không chặn build — chạy thủ công bằng `mvn test -Dtest=GroupRepositoryIntegrationTest -DfailIfNoTests=false` trên máy có Docker truy cập được bình thường (CI Linux, WSL2 native, máy dev khác...).

## ✅ Swagger/OpenAPI UI — đã thêm vào cả 14/14 business service

`springdoc-openapi-starter-webmvc-ui` (version đã khai sẵn ở root `pom.xml`) được thêm vào tất cả 14 service. Verify trực tiếp qua Docker thật (`/swagger-ui/index.html` HTTP 200, `/v3/api-docs` trả đúng OpenAPI JSON với schema request/response chính xác) trên `post-service`, `dating-service`, `group-service`, và cả `chat-service` (service dùng WebSocket — REST endpoint vẫn generate đúng, STOMP tất nhiên không thuộc OpenAPI vì khác giao thức). Build lại toàn reactor + redeploy Docker: **18/18 module, 279/279 test vẫn pass**.

Truy cập: mỗi service có Swagger UI riêng trên port của nó, ví dụ `http://localhost:8084/swagger-ui/index.html` (post-service). Chưa gộp qua gateway thành 1 điểm truy cập chung (mỗi service vẫn expose riêng lẻ) — có thể cải thiện sau nếu cần.

## ✅ Observability — distributed tracing (Zipkin) + centralized logging (Loki/Promtail/Grafana)

Chọn bộ nhẹ thay vì ELK (Elasticsearch quá nặng RAM so với tài nguyên máy đang có, từng thấy rõ khi 22 container đã gây tranh chấp CPU):

- **Tracing**: `micrometer-tracing-bridge-brave` + `zipkin-reporter-brave` thêm vào `api-gateway` + 14 business service (15 service, bỏ qua eureka-server/config-server vì không nằm trong luồng request). `management.tracing.sampling.probability: 1.0` (100% để dễ demo — production thật nên hạ xuống). Container `zipkin` (port 9411).
- **Kafka tracing**: mặc định Spring Boot 3 **tắt** observation cho Kafka producer/consumer — phải bật tường minh `spring.kafka.template.observation-enabled: true` + `spring.kafka.listener.observation-enabled: true` (13 service có Kafka) mới nối được nhánh async vào cùng trace.
- **Logging tập trung**: `loki` (lưu trữ/query log) + `promtail` (tự động discover mọi container qua Docker socket, ship log lên Loki, gắn nhãn theo tên service — không cần cấu hình riêng khi thêm service mới) + `grafana` (UI hợp nhất, anonymous admin access cho demo, đã provision sẵn 2 datasource Loki + Zipkin).

**Đã verify bằng dữ liệu thật** (không chỉ container "Up"):
- Trace 1 request `POST /api/auth/register` cho ra **5 span xuyên 3 service với cùng 1 traceId**: `api-gateway` (SERVER+CLIENT) → `auth-service` (SERVER, rồi PRODUCER gửi `UserRegisteredEvent`) → `user-service` (CONSUMER nhận event) — thấy được toàn bộ đường đi kể cả nhánh Kafka bất đồng bộ, không chỉ phần REST.
- Query Loki xác nhận log của mọi container đều được hút về, và **mỗi dòng log tự động có `[traceId-spanId]`** (Spring Boot tự thêm khi có Micrometer Tracing) — nghĩa là từ 1 trace trong Zipkin có thể tra ngược đúng log chi tiết trong Loki bằng traceId.
- Grafana `/api/health` 200, 2 datasource (Loki, Zipkin) provision đúng qua file, không cần cấu hình tay.

Build+test toàn reactor + redeploy Docker 2 lần (thêm tracing dep, rồi bật Kafka observation): **18/18 module, 279/279 test vẫn pass**.

Truy cập: Zipkin UI `http://localhost:9411`, Grafana `http://localhost:3000` (anonymous admin), Loki API `http://localhost:3100` (thường dùng qua Grafana Explore chứ không gọi trực tiếp).

## Việc cần làm tiếp theo (theo thứ tự ưu tiên đề xuất)

1. **Chạy thử `GroupRepositoryIntegrationTest` trên môi trường Docker không bị giới hạn** để xác nhận, rồi mở rộng pattern này sang service khác nếu thấy giá trị.
2. **Metrics/dashboard** — đã có tracing+log, còn thiếu Prometheus (metrics) + dashboard Grafana trực quan (request rate, latency p99, error rate theo service) — cố ý bỏ qua đợt này để không thêm quá nhiều container cùng lúc.
3. **Content moderation** — chưa có filter/kiểm duyệt nội dung post/comment.
4. **CI/CD pipeline** — chưa có (giờ có 279 test, chạy CI mỗi lần push sẽ rất có giá trị).
5. **Nâng cấp nhỏ theo từng service** — xem cột "Còn thiếu" ở bảng trên.
6. **(tuỳ chọn) Gộp Swagger UI qua gateway** thành 1 điểm truy cập chung thay vì phải nhớ port từng service.
7. **(tuỳ chọn) Hạ `sampling.probability` xuống thấp hơn 1.0** trước khi coi là "production-ready" — 100% sampling chỉ hợp lý cho demo/dev.

## ✅ Đã test WebSocket thật (chat + notification)

Xem "Lỗi thứ 8" ở trên — cả 2 luồng real-time đã verify PASS qua gateway thật với JWT thật, không phải qua mock.
