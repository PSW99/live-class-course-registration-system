package com.liveclass.registration.service;

import com.liveclass.registration.controller.dto.CreateCourseClassRequest;
import com.liveclass.registration.domain.ClassStatus;
import com.liveclass.registration.domain.CourseClass;
import com.liveclass.registration.domain.User;
import com.liveclass.registration.global.exception.ForbiddenAccessException;
import com.liveclass.registration.global.exception.InvalidStatusTransitionException;
import com.liveclass.registration.global.exception.NotFoundException;
import com.liveclass.registration.repository.CourseClassRepository;
import com.liveclass.registration.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 강의 등록과 상태 전이를 담당하는 서비스.
 *
 * 상태 전이의 BR-05 검증은 도메인 메서드(open/close)에 위임하고,
 * 본 서비스는 권한 검증(소유자 확인)과 디스패치만 담당한다.
 */
@Service
@RequiredArgsConstructor
public class CourseClassService {

    private final CourseClassRepository courseClassRepository;
    private final UserRepository userRepository;

    /** 강의 등록. status=DRAFT, currentCount=0으로 시작한다. */
    @Transactional
    public CourseClass create(long requesterId, CreateCourseClassRequest request) {
        User creator = userRepository.findById(requesterId)
                .orElseThrow(() -> new NotFoundException("user", requesterId));
        CourseClass cls = new CourseClass(
                creator,
                request.title(),
                request.description(),
                request.price(),
                request.capacity(),
                request.startDate(),
                request.endDate()
        );
        return courseClassRepository.save(cls);
    }

    /**
     * 강의 상태 전이.
     *
     * 검증 순서: 존재 확인 → 소유자 확인 → 도메인 전이 규칙.
     * 순서가 어긋나면 존재하지 않는 강의에 403이 나가는 등 응답 의미가 무너진다.
     */
    @Transactional
    public CourseClass changeStatus(long classId, long requesterId, ClassStatus nextStatus) {
        CourseClass cls = courseClassRepository.findById(classId)
                .orElseThrow(() -> new NotFoundException("class", classId));
        assertOwner(cls, requesterId);
        // 모든 enum 값을 명시 처리한다. default로 빠지면 NoOp 200이 나가는 버그가 숨는다.
        switch (nextStatus) {
            case OPEN -> cls.open();
            case CLOSED -> cls.close();
            case DRAFT -> throw new InvalidStatusTransitionException(cls.getId(), cls.getStatus(), nextStatus);
        }
        return cls;
    }

    private void assertOwner(CourseClass cls, long requesterId) {
        if (cls.getCreator().getId() != requesterId) {
            throw new ForbiddenAccessException("class", cls.getId(), requesterId);
        }
    }
}
