package com.team.sharedcreature.avatar.integration;

import com.team.sharedcreature._core.security.LoginUser;
import com.team.sharedcreature._core.security.SessionAuthenticationFilter;
import com.team.sharedcreature.avatar.support.SessionCookieClient;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 실제 MySQL·Spring Session JDBC·CSRF·Controller·JPA를 연결해 이슈 20을 검증합니다.
 *
 * 환경변수 또는 .env에 TEST_DB_USERNAME이 설정된 준비된 test 프로필 스키마만 사용합니다.
 * 전체 애플리케이션 기동에 필요한 테이블은 전용 테스트 DB에서 update로 준비합니다.
 * 기존 테이블을 삭제하지 않으며 테스트가 삽입한 행과 세션만 정리합니다.
 * 테스트 전체를 트랜잭션으로 감싸지 않아 HTTP 명령의 실제 Commit도 검증합니다.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.sql.init.mode=always"
})
@AutoConfigureMockMvc
@Import(AvatarHttpDatabaseIntegrationTest.JdbcSessionTestConfiguration.class)
@ActiveProfiles("test")
@EnabledIf(value = "testDatabaseConfigured", disabledReason = "환경변수 또는 .env의 테스트 DB 설정이 필요합니다.")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AvatarHttpDatabaseIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-11T00:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @SuppressWarnings("rawtypes") private SessionRepository sessions;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> shopIds = new ArrayList<>();
    private final List<Long> purchaseIds = new ArrayList<>();
    private long ownerId;
    private long headShopId;
    private long inactiveShopId;
    private Item headItem;
    private Item foreignItem;
    private SessionCookieClient browser;

    /**
     * Spring 기동 전에도 환경변수와 .env의 테스트 DB 설정 존재 여부를 확인합니다.
     *
     * 실제 접속값은 기존 Spring 설정이 로드하며 이 조건은 비밀값을 출력하지 않습니다.
     *
     * @return 테스트 DB 사용자 설정이 존재하면 true
     */
    static boolean testDatabaseConfigured() throws java.io.IOException {
        String environmentValue = System.getenv("TEST_DB_USERNAME");
        if (environmentValue != null && !environmentValue.isBlank()) {
            return true;
        }
        Path environmentFile = Path.of(".env");
        if (!Files.isRegularFile(environmentFile)) {
            return false;
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(environmentFile, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return !properties.getProperty("TEST_DB_USERNAME", "").isBlank();
    }

    @BeforeEach
    void setUp() {
        ownerId = insertUser();
        long otherId = insertUser();
        headShopId = insertShop("HEAD", "ACTIVE");
        long bodyShopId = insertShop("BODY", "ACTIVE");
        inactiveShopId = insertShop("FACE", "INACTIVE");
        headItem = insertItem(ownerId, headShopId, "HEAD", "OWNED_UNUSED");
        insertItem(ownerId, bodyShopId, "BODY", "OWNED_UNUSED");
        insertItem(ownerId, headShopId, "HEAD", "REVOKED");
        foreignItem = insertItem(otherId, headShopId, "HEAD", "OWNED_UNUSED");
        browser = new SessionCookieClient(mvc, createLoginCookie(ownerId));
    }

    @AfterEach
    void tearDown() {
        // 공유 테이블을 비우지 않고 이번 테스트가 생성한 식별자로만 정리합니다.
        if (browser != null) {
            for (String sessionId : browser.sessionIds()) {
                sessions.deleteById(sessionId);
            }
        }
        deleteByIds("avatar_equipments", "user_id", userIds);
        deleteByIds("refund_requests", "purchase_id", purchaseIds);
        deleteByIds("owned_items", "purchase_id", purchaseIds);
        deleteByIds("purchases", "id", purchaseIds);
        deleteByIds("shop_items", "id", shopIds);
        deleteByIds("users", "id", userIds);
    }

    @Test
    void JDBC_세션으로_내_아이템만_조회하고_필터와_COUNT_페이지를_검증한다() throws Exception {
        browser.perform(get("/api/v1/owned-items").queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.totalElements").value(3))
                .andExpect(jsonPath("$.meta.totalPages").value(3));

        browser.perform(get("/api/v1/owned-items")
                        .queryParam("slot", "HEAD").queryParam("status", "OWNED_UNUSED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].ownedItemId").value(headItem.ownedItemId()))
                .andExpect(jsonPath("$.data[0].equipped").value(false));

        browser.perform(get("/api/v1/owned-items").queryParam("status", "REVOKED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].status").value("REVOKED"));
    }

    @Test
    void 최초_사용_거부_후_장착_해제_재장착은_DB의_첫_사용_시각을_보존한다() throws Exception {
        browser.perform(equip(headItem, false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FIRST_USE_CONFIRMATION_REQUIRED"));
        assertThat(itemStatus(headItem)).isEqualTo("OWNED_UNUSED");
        assertThat(usedAt(headItem)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from avatar_equipments where user_id = ?",
                Long.class, ownerId)).isZero();

        browser.perform(equip(headItem, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.equipments.HEAD.ownedItemId").value(headItem.ownedItemId()));
        Instant firstUsedAt = usedAt(headItem);
        assertThat(firstUsedAt).isNotNull();
        assertThat(itemStatus(headItem)).isEqualTo("OWNED_USED");

        // 다음 HTTP 요청은 이전 요청의 영속성 컨텍스트를 공유하지 않습니다.
        browser.perform(get("/api/v1/avatar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.equipments.HEAD.ownedItemId").value(headItem.ownedItemId()));

        browser.perform(withRealCsrf(delete("/api/v1/avatar/equipments/HEAD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.equipments.HEAD").value(org.hamcrest.Matchers.nullValue()));
        assertThat(usedAt(headItem)).isEqualTo(firstUsedAt);
        assertThat(itemStatus(headItem)).isEqualTo("OWNED_USED");

        browser.perform(withRealCsrf(put("/api/v1/avatar/equipments/HEAD"))
                        .contentType("application/json")
                        .content("{\"ownedItemId\":" + headItem.ownedItemId() + "}"))
                .andExpect(status().isOk());
        assertThat(usedAt(headItem)).isEqualTo(firstUsedAt);
        browser.perform(get("/api/v1/owned-items").queryParam("status", "OWNED_USED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].equipped").value(true));
    }

    @Test
    void 다른_사용자의_아이템을_HTTP로_장착해도_DB는_변경되지_않는다() throws Exception {
        browser.perform(equip(foreignItem, true))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(itemStatus(foreignItem)).isEqualTo("OWNED_UNUSED");
        assertThat(usedAt(foreignItem)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTED", "CANCEL_PENDING", "CANCEL_UNKNOWN"})
    void 실제_환불_행이_진행_중이면_장착을_거부한다(String status) throws Exception {
        insert("""
                insert into refund_requests
                    (purchase_id, requested_by_user_id, request_reason, status,
                     requested_at, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, 0, ?, ?)
                """, headItem.purchaseId(), ownerId, "이슈 20 환불 경합 검사", status,
                CREATED_AT, CREATED_AT, CREATED_AT);

        browser.perform(equip(headItem, true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

        assertThat(itemStatus(headItem)).isEqualTo("OWNED_UNUSED");
        assertThat(usedAt(headItem)).isNull();
    }

    @Test
    void 상점_상세와_목록의_활성_상품_조회가_JPA에서_동작한다() throws Exception {
        browser.perform(get("/api/v1/shop-items/" + headShopId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shopItemId").value(headShopId));
        browser.perform(get("/api/v1/shop-items/" + inactiveShopId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        var result = browser.perform(get("/api/v1/shop-items")
                        .queryParam("slot", "HEAD").queryParam("size", "1"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var root = mapper.readTree(result.getContentAsString());
        long expectedCount = Objects.requireNonNull(jdbc.queryForObject(
                "select count(*) from shop_items where status = 'ACTIVE' and slot_code = 'HEAD'", Long.class));
        assertThat(root.get("meta").get("totalElements").longValue()).isEqualTo(expectedCount);
        assertThat(root.get("data").get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(root.get("data").get(0).get("slot").asText()).isEqualTo("HEAD");
    }

    /** 실제 JDBC 세션을 생성하고 기존 표준 Cookie 형식으로 전달합니다. */
    @SuppressWarnings("unchecked")
    private Cookie createLoginCookie(long userId) {
        Session session = (Session) sessions.createSession();
        session.setAttribute(SessionAuthenticationFilter.LOGIN_USER,
                new LoginUser(userId, "이슈20 통합검증", Set.of()));
        sessions.save(session);
        // 기존 Spring Session 기본 Cookie 직렬화는 세션 ID를 Base64로 인코딩합니다.
        String value = Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8));
        return new Cookie("SESSION", value);
    }

    /** 실제 CSRF 조회 API에서 받은 헤더와 JDBC 세션 Cookie를 요청에 붙입니다. */
    private MockHttpServletRequestBuilder withRealCsrf(MockHttpServletRequestBuilder request) throws Exception {
        var response = browser.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var token = mapper.readTree(response.getContentAsString()).get("data");
        return request.header(token.get("headerName").asText(), token.get("token").asText());
    }

    /** 보유 아이템의 슬롯에 대해 최초 사용 동의를 명시한 HTTP 요청을 만듭니다. */
    private MockHttpServletRequestBuilder equip(Item item, boolean confirm) throws Exception {
        return withRealCsrf(put("/api/v1/avatar/equipments/HEAD")).contentType("application/json")
                .content("{\"ownedItemId\":" + item.ownedItemId() + ",\"confirmFirstUse\":" + confirm + "}");
    }

    private String itemStatus(Item item) {
        return jdbc.queryForObject("select status from owned_items where id = ?", String.class, item.ownedItemId());
    }

    private Instant usedAt(Item item) {
        Timestamp value = jdbc.queryForObject("select used_at from owned_items where id = ?",
                Timestamp.class, item.ownedItemId());
        return value == null ? null : value.toInstant();
    }

    /** HTTP 요청이 별도 트랜잭션에서 읽을 수 있도록 테스트 행을 실제로 저장합니다. */
    private long insertUser() {
        long id = insert("""
                insert into users
                    (status, global_role, last_login_at, last_active_at, version, created_at, updated_at)
                values ('ACTIVE', 'USER', ?, ?, 0, ?, ?)
                """, CREATED_AT, CREATED_AT, CREATED_AT, CREATED_AT);
        userIds.add(id);
        return id;
    }

    private long insertShop(String slot, String status) {
        long id = insert("""
                insert into shop_items
                    (code, name, description, price_krw, slot_code, asset_key, status, created_at, updated_at)
                values (?, ?, ?, 1000, ?, ?, ?, ?, ?)
                """, "I20_" + UUID.randomUUID(), "이슈20 테스트 상품", "통합 테스트 전용",
                slot, "avatar/issue20-test", status, CREATED_AT, CREATED_AT);
        shopIds.add(id);
        return id;
    }

    private Item insertItem(long userId, long shopId, String slot, String status) {
        long purchaseId = insert("""
                insert into purchases
                    (order_number, user_id, shop_item_id, item_code_snapshot, item_name_snapshot,
                     slot_code_snapshot, price_krw_snapshot, currency, status, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 1000, 'KRW', 'PAID', 0, ?, ?)
                """, UUID.randomUUID().toString(), userId, shopId, "I20_ITEM", "이슈20 테스트 상품",
                slot, CREATED_AT, CREATED_AT);
        purchaseIds.add(purchaseId);
        long itemId = insert("""
                insert into owned_items
                    (purchase_id, user_id, shop_item_id, status, revoked_at, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, 0, ?, ?)
                """, purchaseId, userId, shopId, status,
                "REVOKED".equals(status) ? CREATED_AT : null, CREATED_AT, CREATED_AT);
        return new Item(purchaseId, itemId);
    }

    /** JDBC가 생성한 식별자를 반환하며 시각은 UTC Instant에서 변환합니다. */
    private long insert(String sql, Object... arguments) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < arguments.length; index++) {
                Object value = arguments[index];
                statement.setObject(index + 1, value instanceof Instant instant ? Timestamp.from(instant) : value);
            }
            return statement;
        }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    /** 이번 테스트가 삽입한 ID만 삭제하며 스키마나 기존 행은 변경하지 않습니다. */
    private void deleteByIds(String table, String column, List<Long> ids) {
        if (!ids.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            jdbc.update("delete from " + table + " where " + column + " in (" + placeholders + ")", ids.toArray());
        }
    }

    private record Item(long purchaseId, long ownedItemId) {
    }

    /** 기존 세션 설정을 변경하지 않고 이 통합 테스트에서만 JDBC 세션을 활성화합니다. */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableJdbcHttpSession(tableName = "spring_session")
    static class JdbcSessionTestConfiguration {
    }
}
