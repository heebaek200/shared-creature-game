package com.team.sharedcreature.season.api;

import com.team.sharedcreature._core.api.ApiResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CreatureApiContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 현재_시즌_응답은_현행_필드명으로_직렬화한다() throws Exception {
        var response = new SeasonResponse(
                1L,
                3,
                "ACTIVE",
                Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-12-01T00:00:00Z")
        );

        String json = objectMapper.writeValueAsString(ApiResponse.success(response));

        assertThat(json).contains(
                "\"seasonId\":1",
                "\"seasonNumber\":3",
                "\"startAt\":\"2026-11-01T00:00:00Z\"",
                "\"endAt\":\"2026-12-01T00:00:00Z\""
        );
        assertThat(json).doesNotContain("\"sequence\"", "startsAt", "endsAt");
    }

    @Test
    void 크리처_홈_응답은_온라인_구성원과_Snapshot_커서를_계약대로_직렬화한다() throws Exception {
        Instant snapshotAt = Instant.parse("2026-11-10T00:00:00Z");
        UUID streamInstanceId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var equippedItem = new CreatureHomeResponse.EquippedItemResponse(101L, 201L, "새싹 모자", "hat-seed");
        var equipments = new CreatureHomeResponse.EquipmentSlotsResponse(equippedItem, null, null, null);
        var onlineMember = new CreatureHomeResponse.OnlineMemberResponse(
                7L,
                "새싹지기",
                null,
                30L,
                new CreatureHomeResponse.AvatarResponse(equipments)
        );
        var contribution = new CreatureHomeResponse.ContributionActivityResponse(
                7L,
                "새싹지기",
                100L,
                snapshotAt.minusSeconds(60)
        );
        var response = new CreatureHomeResponse(
                10L,
                "테스트 그룹",
                new SeasonResponse(1L, 3, "ACTIVE", snapshotAt.minusSeconds(60), snapshotAt.plusSeconds(60)),
                new CreatureHomeResponse.CreatureResponse(20L, 30L, "HATCHLING", 100L, 45000L, snapshotAt),
                100L,
                List.of(onlineMember),
                List.of(contribution),
                snapshotAt,
                streamInstanceId,
                4L
        );

        String json = objectMapper.writeValueAsString(ApiResponse.success(response));

        assertThat(json).contains(
                "\"groupName\":\"테스트 그룹\"",
                "\"groupCreatureId\":20",
                "\"creatureSpeciesId\":30",
                "\"growthStage\":\"HATCHLING\"",
                "\"totalExperience\":100",
                "\"nextStageAtExperience\":45000",
                "\"onlineMembers\"",
                "\"contributionScore\":30",
                "\"HEAD\":{",
                "\"FACE\":null",
                "\"ownedItemId\":101",
                "\"amount\":100",
                "\"snapshotAt\":\"2026-11-10T00:00:00Z\"",
                "\"streamInstanceId\":\"00000000-0000-0000-0000-000000000001\"",
                "\"sequence\":4"
        );
        assertThat(json).doesNotContain(
                "presentAvatars",
                "speciesName",
                "currentStageThreshold",
                "hatchesAt",
                "selectionSource"
        );
    }

    @Test
    void 알_상태의_종_ID는_선택_여부에_따라_null_또는_ID로_직렬화한다() throws Exception {
        Instant now = Instant.parse("2026-11-10T00:00:00Z");
        SeasonResponse season = new SeasonResponse(1L, 3, "ACTIVE", now.minusSeconds(60), now.plusSeconds(60));
        CreatureHomeResponse unselectedEgg = creatureHomeWith(
                season,
                new CreatureHomeResponse.CreatureResponse(20L, null, "EGG", 0L, 45_000L, now),
                now
        );
        CreatureHomeResponse selectedEgg = creatureHomeWith(
                season,
                new CreatureHomeResponse.CreatureResponse(20L, 30L, "EGG", 0L, 45_000L, now),
                now
        );

        String unselectedJson = objectMapper.writeValueAsString(ApiResponse.success(unselectedEgg));
        String selectedJson = objectMapper.writeValueAsString(ApiResponse.success(selectedEgg));

        assertThat(unselectedJson).contains("\"growthStage\":\"EGG\"", "\"creatureSpeciesId\":null");
        assertThat(selectedJson).contains("\"growthStage\":\"EGG\"", "\"creatureSpeciesId\":30");
    }

    @Test
    void 활성_종과_관리자_선택_응답은_현행_ID_필드를_사용한다() throws Exception {
        var species = new CreatureSpeciesResponse(5L, "MOSS_GOLEM", "이끼 골렘", "moss-golem", 1);
        var selection = new CreatureSelectionResponse(
                10L,
                1L,
                5L,
                7L,
                Instant.parse("2026-11-10T00:00:00Z"),
                Instant.parse("2026-11-13T00:00:00Z")
        );

        String speciesJson = objectMapper.writeValueAsString(ApiResponse.success(List.of(species)));
        String selectionJson = objectMapper.writeValueAsString(ApiResponse.success(selection));

        assertThat(speciesJson).contains("\"creatureSpeciesId\":5").doesNotContain("\"speciesId\"");
        assertThat(selectionJson).contains(
                "\"groupId\":10",
                "\"seasonId\":1",
                "\"creatureSpeciesId\":5",
                "\"selectedByUserId\":7",
                "\"selectionDeadlineAt\":\"2026-11-13T00:00:00Z\""
        );
        assertThat(selectionJson).doesNotContain("speciesName", "selectionSource", "changeableUntil");
    }

    private CreatureHomeResponse creatureHomeWith(
            SeasonResponse season,
            CreatureHomeResponse.CreatureResponse creature,
            Instant snapshotAt
    ) {
        return new CreatureHomeResponse(
                10L,
                "테스트 그룹",
                season,
                creature,
                0L,
                List.of(),
                List.of(),
                snapshotAt,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                0L
        );
    }
}
