package com.team.sharedcreature.avatar.application;

import com.team.sharedcreature.avatar.api.AvatarResponse;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * 아바타 장착·해제 중 발생한 DB 동시성 오류를 제한적으로 재시도합니다. (첫 시도 포함 최대 3회) CONCURRENT_MODIFICATION
 * FIRST_USE_CONFIRMATION_REQUIRED, SLOT_MISMATCH, RESOURCE_NOT_FOUND 재시도 안함
 * 같은 CONCURRENT_MODIFICATION 응답이어도 업무 예외는 재시도하지 않습니다.
 * 각 시도는 AvatarService의 별도 트랜잭션으로 실행합니다.
 */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AvatarCommandRetryService {

    private static final int MAX_ATTEMPTS = 3;

    private final AvatarService avatarService;

    /**
     * 실제 장착·해제를 처리할 Service를 주입받습니다.
     *
     * @param avatarService 아바타 업무 Service
     */
    public AvatarCommandRetryService(AvatarService avatarService) {
        this.avatarService = avatarService;
    }

    /**
     * DB 동시성 오류가 발생하면 장착을 최대 3번까지 시도합니다.
     *
     * @param userId 로그인한 사용자 ID
     * @param slot 장착할 슬롯
     * @param ownedItemId 장착할 보유 아이템 ID
     * @param confirmFirstUse 최초 사용 동의 여부
     * @return 변경된 아바타 정보
     * @throws PessimisticLockingFailureException 모든 시도에서 잠금 처리가 실패한 경우
     * @throws OptimisticLockingFailureException 모든 시도에서 버전 충돌이 발생한 경우
     */
    public AvatarResponse equipAvatarItem(
            Long userId,
            String slot,
            Long ownedItemId,
            boolean confirmFirstUse
    ) {
        return executeWithRetry(() ->
                avatarService.equipAvatarItem(
                        userId,
                        slot,
                        ownedItemId,
                        confirmFirstUse
                )
        );
    }

    /**
     * DB 동시성 오류가 발생하면 해제를 최대 3번까지 시도합니다.
     *
     * @param userId 로그인한 사용자 ID
     * @param slot 해제할 슬롯
     * @return 변경된 아바타 정보
     * @throws PessimisticLockingFailureException 모든 시도에서 잠금 처리가 실패한 경우
     * @throws OptimisticLockingFailureException 모든 시도에서 버전 충돌이 발생한 경우
     */
    public AvatarResponse unequipAvatarItem(Long userId, String slot) {
        return executeWithRetry(() ->
                avatarService.unequipAvatarItem(userId, slot)
        );
    }

    /**
     * 전달받은 작업을 실행하고 DB 동시성 오류만 재시도합니다.
     *
     * @param action 실행할 아바타 변경 작업
     * @return 성공한 작업의 결과
     */
    private AvatarResponse executeWithRetry(
            Supplier<AvatarResponse> action
    ) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (PessimisticLockingFailureException
                     | OptimisticLockingFailureException exception) {
                // 마지막 시도도 실패하면 공통 예외 처리기가 409로 변환합니다.
                if (attempt >= MAX_ATTEMPTS) {
                    throw exception;
                }

                // 이전 트랜잭션의 롤백이 끝난 뒤 새 트랜잭션으로 다시 호출합니다.
            }
        }
    }
}