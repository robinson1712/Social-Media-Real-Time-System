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
| `auth-service` | 8081 | ✅ Xong + admin allowlist | Chưa có: reset mật khẩu, xác minh email, khoá tài khoản sau N lần login sai, OAuth2/social login |
| `user-service` | 8082 | ✅ Xong | Chưa có: tìm kiếm user, gợi ý bạn chung |
| `media-service` | 8083 | ✅ Xong | Chưa có: resize ảnh/tạo thumbnail, giới hạn dung lượng/loại file, transcode video |
| `post-service` | 8084 | ✅ Xong + profanity filter + xoá theo report | Chưa có: lịch sử chỉnh sửa, ghim bài |
| `comment-service` | 8085 | ✅ Xong + circuit breaker + profanity filter + xoá theo report | — |
| `reaction-service` | 8086 | ✅ Xong | Đơn giản hoá có chủ đích: nhận `targetOwnerId` từ client thay vì tự resolve qua Feign |
| `story-service` | 8087 | ✅ Xong + circuit breaker | Chưa có: story highlights (lưu vĩnh viễn), reply story qua chat |
| `reels-service` | 8088 | ✅ Xong | Feed reels vẫn thuần theo thời gian (chưa áp dụng ranking như feed-service) |
| `group-service` | 8089 | ✅ Xong + fix visibility | Chưa có: vai trò MODERATOR, kiểm duyệt bài đăng trong group |
| `fanpage-service` | 8090 | ✅ Xong | Chưa có: thống kê/insight cho page |
| `dating-service` | 8091 | ✅ Xong + matching algorithm | Chưa có: unmatch, report trong ngữ cảnh dating, xác minh ảnh |
| `chat-service` | 8092 | ✅ Xong (WebSocket thật) | Chưa có: sửa/xoá tin nhắn, typing indicator, read-receipt chi tiết (hiện chỉ có mark-all-read) |
| `notification-service` | 8093 | ✅ Xong (WebSocket thật) | Chưa có: push notification ra mobile (FCM/APNs), cài đặt loại thông báo |
| `feed-service` | 8094 | ✅ Xong + engagement ranking | Chưa gộp story/reels vào cùng feed logic |
| `moderation-service` | 8095 | ✅ Xong (report + admin review queue) | Đã mở rộng đủ 6 loại: post/comment/reels/story/group/fanpage đều nghe `ContentRemovedEvent` |

**15/15 business service + 3/3 hạ tầng đều đã hoàn thành scaffold và build được** — không có service nào còn ở dạng rỗng/chưa code.

---

## ✅ Integration test (Testcontainers) — đã verify PASS thật trên GitHub Actions

Đã viết `GroupRepositoryIntegrationTest` (`group-service`) — chạy chính JPQL `findVisibleGroups` (câu query từng gây lỗi `lower(bytea)` thật) trên **Postgres thật** qua Testcontainers, không phải mock. 5 test: filter rỗng không lỗi, private group chỉ hiện với member đã APPROVED (không hiện với PENDING/người lạ/ẩn danh), filter tên không phân biệt hoa-thường, sanity check mapping entity `SocialGroup`→bảng `groups`.

**Không chạy được trong môi trường sandbox local**: Testcontainers không kết nối được Docker daemon (`docker` CLI hoạt động bình thường nhưng `docker-java` gọi thẳng Docker API qua named pipe bị chặn/trả response rỗng — giới hạn bảo mật tầng host, không phải lỗi code). Đã cấu hình `maven-surefire-plugin` loại `**/*IntegrationTest.java` khỏi `mvn test` mặc định để không chặn build local.

**Đã xác nhận đúng dự đoán khi CI/CD chạy trên GitHub Actions** (xem mục CI/CD dưới) — bước "Run Testcontainers integration test (group-service)" **pass thật** trên runner Ubuntu có Docker không giới hạn, đúng 20 giây, không cần patch gì thêm. Đây là bằng chứng trực tiếp: code hoàn toàn đúng, chỉ là môi trường dev local đặc thù mới bị chặn.

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

## ✅ CI/CD pipeline (GitHub Actions)

Repo đã có remote GitHub thật (`robinson1712/SE347-TSON-social-media-real-time-system`) nên dùng GitHub Actions, file `.github/workflows/ci-cd.yml`, 2 job:

1. **`build-and-test`** (mọi push/PR vào `main`): `mvn clean install` (build 18 module + chạy 279 unit test) → chạy riêng `GroupRepositoryIntegrationTest` (Testcontainers) — runner Ubuntu của GitHub có Docker thật, không bị giới hạn như môi trường sandbox local, nên đây chính là nơi verify được test này thay vì máy dev. Upload surefire report làm artifact để debug khi fail.
2. **`docker-images`** (cần job 1 xanh): build lại jar (`-DskipTests`, đã test ở job 1) → build Docker image cho cả 17 service (validate Dockerfile không vỡ trên **mọi** PR) → **chỉ push lên GHCR** (`ghcr.io/<owner>/<repo>-<service>:latest` + `:<sha>`) khi là push thẳng vào `main`, dùng `GITHUB_TOKEN` có sẵn, không cần secret riêng.

**Sửa kèm 1 lỗi phát hiện lúc làm CI**: `.gitignore` thiếu `target/` — **158 file build artifact** (jar, class, log Maven) đã bị lỡ track vào git từ trước. Đã cập nhật `.gitignore` (`target/`, `.idea/`, IDE noise) và `git rm --cached` gỡ tracking (file vẫn còn trên đĩa, chỉ không theo dõi nữa).

**Đã commit + push (`adc4245`) và verify PASS 100% thật trên GitHub Actions** (run #1, ~5.5 phút, [xem log](https://github.com/robinson1712/SE347-TSON-social-media-real-time-system/actions/runs/33243483057)):
- `build-and-test`: `mvn clean install` xanh + **`GroupRepositoryIntegrationTest` (Testcontainers) pass thật** trong 20s trên runner có Docker không giới hạn — xác nhận đúng dự đoán, không phải lỗi code.
- `docker-images`: build + push cả 17 image lên GHCR thành công, dùng `GITHUB_TOKEN` có sẵn không cần secret riêng.

## ✅ Content moderation

Ba lớp bổ sung nhau, không thay thế nhau:

- **Profanity filter tự động** (`common-lib` → `ProfanityFilter`): danh sách từ cấm tiếng Anh + tiếng
  Việt, so khớp substring sau khi normalize Unicode **NFC** (compose, không decompose) và chỉ bỏ
  ký tự không phải chữ/số (để chặn né tránh kiểu chèn dấu câu/khoảng trắng). **Cố ý giữ nguyên dấu
  thanh tiếng Việt** — không dùng NFKD/strip combining marks, vì dấu thanh tiếng Việt mang nghĩa
  (khác dấu phụ tiếng Pháp): bug thật đã phát hiện lúc viết test — "đi chơi" (đi, bỏ dấu) từng bị
  chuẩn hoá trùng với "đĩ" (một từ tục) khi dùng NFKD, gây false positive nghiêm trọng cho câu hoàn
  toàn vô hại. Đã sửa và thêm test `cleanText_isNotFlagged` để khoá lại hành vi đúng. Áp dụng ở
  `post-service` (`createPost`/`updatePost`) và `comment-service` (`createComment`/`updateComment`)
  — chặn ngay lúc tạo/sửa, trả lỗi 400 trước khi ghi DB. Có giới hạn đã biết và ghi rõ trong Javadoc:
  so khớp substring gây false positive kiểu "Scunthorpe problem" (từ vô hại chứa chuỗi con là từ
  cấm) — chấp nhận đánh đổi vì đơn giản, không cần NLP.
- **User report + admin review queue** (`moderation-service`, port 8095, Postgres `moderation_db`,
  service thứ 18): user gọi `POST /api/moderation/reports` báo cáo nội dung (targetType/targetId/
  reason/description), tự xem lại report của mình qua `GET /api/moderation/reports/mine`. Admin
  (role `ADMIN`, gate bằng `CurrentUserContext.getRoles()`) xem hàng đợi `GET /api/moderation/reports`
  (filter theo status/targetType), xử lý qua `POST /api/moderation/reports/{id}/resolve` với 2 hành
  động: `DISMISS` (đóng report, không hành động) hoặc `REMOVE_CONTENT` (đánh dấu `ACTION_TAKEN` +
  publish `ContentRemovedEvent` lên Kafka topic `content-removed-events`).
- **Xoá nội dung xuyên service qua Kafka** (event-driven, không gọi REST đồng bộ): `post-service` và
  `comment-service` đều có `@KafkaListener` mới lắng nghe `ContentRemovedEvent`, lọc theo
  `targetType` (`"POST"`/`"COMMENT"`), gọi `removeForModeration(targetId)` — post-service xoá cứng,
  comment-service soft-delete (nhất quán với cách xoá thường của từng service). Đây là lần đầu
  comment-service có `@KafkaListener` (trước đó chỉ producer).
- **Cấp quyền ADMIN lúc đăng ký** (`auth-service` → `AdminEmailAllowlist`): đọc danh sách email admin
  từ `ADMIN_EMAILS` (comma-separated, env var, mặc định rỗng) — email nào khớp allowlist được gán
  role `["USER","ADMIN"]` lúc `register()`, còn lại chỉ `["USER"]`. Đơn giản hoá có chủ đích cho đồ
  án (không cần UI quản trị cấp quyền riêng) — ghi rõ trong `application.yml` với comment giải thích.

Build+test toàn reactor (18 module, gồm `moderation-service` mới — 19 module thật ra vẫn tính là
18 service nghiệp vụ+hạ tầng vì `common-lib` không tính là service riêng): **mvn clean install pass,
`ModerationServiceTest` 11/11 pass** (createReport có/không auth, getMyReports, listReports/getReport
gate ADMIN, resolveReport 2 nhánh DISMISS/REMOVE_CONTENT + verify đúng `ContentRemovedEvent` publish
qua `ArgumentCaptor`, resolveReport report đã xử lý rồi → 409 Conflict).

`docker-compose.yml`: thêm `moderation_db` vào `POSTGRES_MULTIPLE_DATABASES`, container
`moderation-service` (port 8095), `ADMIN_EMAILS: admin@social.app` vào `x-app-env` dùng chung.
`api-gateway`: thêm route `/api/moderation/**` → `MODERATION-SERVICE` (cùng rate-limit mặc định như
các service khác).

**Đã verify bằng luồng thật qua Docker + curl (không chỉ unit test mock)**, redeploy full 18 service:
1. Đăng ký `admin@social.app` (nằm trong `ADMIN_EMAILS`) → token JWT có đúng `"roles":["USER","ADMIN"]`; đăng ký email thường → chỉ `["USER"]`.
2. `POST /api/posts` với nội dung tục ("...fucking...") → **400 "Content violates community guidelines"**, không tạo được.
3. Tạo post sạch, user thường report qua `POST /api/moderation/reports` → 200, status `PENDING`.
4. User thường gọi `GET /api/moderation/reports` (hàng đợi) → **403 "Admin access required"** — đúng gate.
5. Admin gọi cùng endpoint → thấy đúng report vừa tạo, status `PENDING`.
6. Admin gọi `PUT /api/moderation/reports/{id}/resolve` với `REMOVE_CONTENT` → 200, status chuyển `ACTION_TAKEN`, `reviewedBy` đúng accountId admin.
7. Gọi lại `GET /api/posts/{id}` (không qua moderation-service, thẳng post-service) → **404 "Post not found"** — xác nhận `ContentRemovedEvent` đã đi qua Kafka thật, `post-service` nhận và xoá đúng object trong Postgres, không phải giả lập.

**Sự cố phát hiện lúc verify (đã fix, không phải bug code)**: volume Postgres đã tồn tại từ lần chạy
trước, nên script `init-multi-db.sh` (chỉ chạy khi khởi tạo volume rỗng) không tự tạo `moderation_db`
mới — phải tạo thủ công (`CREATE DATABASE moderation_db`) rồi restart container. Đây là đặc thù môi
trường dev cục bộ (volume tái sử dụng), không ảnh hưởng lúc `docker compose down -v` sạch hoặc deploy
lần đầu.

## ✅ Mở rộng content moderation sang reels/story/group/fanpage

Áp dụng đúng pattern đã có ở post/comment cho 4 service còn lại trong danh sách `ReportTargetType`
(POST/COMMENT/REEL/STORY/GROUP/FANPAGE — nay đã đủ 6/6, không còn service nào thiếu):

- **Profanity filter lúc tạo nội dung**: `ReelService.createReel()` (field `caption`),
  `StoryService.createStory()` (field `caption`, có thể null vì story chủ yếu là media — filter xử lý
  null/blank an toàn), `GroupService.createGroup()` (`name` + `description`),
  `FanpageService.createPage()` (`name` + `description`). Cả 4 service này vốn chỉ có create, không
  có update — nên không cần đụng vào luồng edit.
- **`removeForModeration(id)`** thêm vào cả 4 service, theo đúng kiểu idempotent (no-op nếu đã bị xoá)
  như post/comment. Khác biệt so với post (xoá đơn giản, 1 bảng): `group-service` và `fanpage-service`
  có bảng phụ thuộc (`GroupMember`, `PageAdmin`/`PageFollower`) — phải xoá cascade thủ công trước khi
  xoá entity chính (không có cascade ORM sẵn), nên thêm `deleteByGroupId`/`deleteByPageId` vào các
  repository tương ứng.
- **Kafka listener nghe `ContentRemovedEvent`**: `reels-service` đã có `ReelEventListener` sẵn (dùng
  cho comment/reaction denormalized count) — chỉ thêm 1 method mới vào đó. 3 service còn lại
  (`story-service`, `group-service`, `fanpage-service`) **chưa từng có `@KafkaListener` nào** — đây là
  lần đầu chúng trở thành Kafka consumer chứ không chỉ producer, tương tự việc comment-service lần đầu
  có listener ở đợt post/comment trước đó. **Phát hiện gap cấu hình**: `group-service` và
  `fanpage-service`'s `application.yml` trước đó chỉ có `spring.kafka.producer`, hoàn toàn thiếu
  `spring.kafka.consumer` — phải thêm cả block consumer (group-id, deserializer, trusted-packages) thì
  `${spring.kafka.consumer.group-id}` trong `@KafkaListener` mới resolve được, nếu không sẽ lỗi khởi
  động ngay khi bean được tạo.

Build+test toàn reactor (18 module): **mvn clean install pass**. Test mới/cập nhật: `ReelServiceTest`
13 test (thêm 3: profanity + removeForModeration hit/miss), `ReelEventListenerTest` 10 test (constructor
đổi vì thêm `ReelService` dependency, thêm 2 test `onContentRemoved`), `StoryServiceTest` 14 test (+3),
`StoryEventListenerTest` 2 test (file mới), `GroupServiceTest` 32 test (+4, bao gồm verify
`deleteByGroupId` được gọi trước `delete`), `GroupModerationListenerTest` 2 test (file mới),
`FanpageServiceTest` 28 test (+4, verify cả `deleteByPageId` cho follower lẫn admin), 
`FanpageModerationListenerTest` 2 test (file mới) — tổng 103 test qua 8 file, tất cả pass.

**Đã verify bằng luồng thật qua Docker + curl** (redeploy 4 service, không chỉ unit test mock), chọn
2 case phức tạp nhất — `group-service` và `fanpage-service` — vì đây là 2 service duy nhất có cascade
cleanup (bảng phụ thuộc `GroupMember`, `PageAdmin`/`PageFollower`):
1. Tạo group tên tục ("...fucking...") → **400**; tạo fanpage description tục → **400** — profanity filter chặn đúng ở cả 2.
2. Tạo group/fanpage sạch, report qua `POST /api/moderation/reports` với `targetType: GROUP`/`FANPAGE`.
3. Admin `PUT /api/moderation/reports/{id}/resolve` với `REMOVE_CONTENT` → 200, `resolutionNote` lưu đúng.
4. `GET /api/groups/{id}` và `GET /api/pages/{id}` sau đó → **404** — xác nhận `ContentRemovedEvent` đã xoá thật qua Kafka.
5. Log container xác nhận đúng listener xử lý: `GroupModerationListener` và `FanpageModerationListener` đều log dòng "Removing {type} {id} — moderation report ..." trước khi gọi `removeForModeration`.

`reels-service`/`story-service` dùng đúng pattern đơn giản hơn (không có bảng phụ thuộc, giống hệt
post-service) nên không lặp lại live-verify — đã được cover đầy đủ qua unit test.

## Việc cần làm tiếp theo (theo thứ tự ưu tiên đề xuất)

1. **Metrics/dashboard** — đã có tracing+log, còn thiếu Prometheus (metrics) + dashboard Grafana trực quan (request rate, latency p99, error rate theo service) — cố ý bỏ qua đợt trước để không thêm quá nhiều container cùng lúc.
2. **Nâng cấp nhỏ theo từng service** — xem cột "Còn thiếu" ở bảng trên.
3. **(tuỳ chọn) Gộp Swagger UI qua gateway** thành 1 điểm truy cập chung thay vì phải nhớ port từng service.
4. **(tuỳ chọn) Hạ `sampling.probability` xuống thấp hơn 1.0** trước khi coi là "production-ready" — 100% sampling chỉ hợp lý cho demo/dev.

## ✅ Đã test WebSocket thật (chat + notification)

Xem "Lỗi thứ 8" ở trên — cả 2 luồng real-time đã verify PASS qua gateway thật với JWT thật, không phải qua mock.
