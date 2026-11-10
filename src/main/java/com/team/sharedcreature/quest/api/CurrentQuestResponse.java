package com.team.sharedcreature.quest.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;

@Getter
@RequiredArgsConstructor
public class CurrentQuestResponse {

        private final Long assignmentId;
        private final Long periodId;
        private final String periodType;
        private final Instant periodStartAt;
        private final Instant periodEndAt;
        private final Integer slotNumber;
        private final String templateCode;
        private final String title;
        private final String description;
        private final String difficulty;
        private final String verificationType;
        private final Long creatureExperience;
        private final Long contributionScore;
        private final boolean completed;
        private final Instant completedAt;
        private final Long completionId;
        private final ProgressResponse progress;
        private final Integer photoAttemptsRemaining;

        @Getter
        @RequiredArgsConstructor
        public static class ProgressResponse {
                private final Integer current;
                private final Integer target;
        }
}