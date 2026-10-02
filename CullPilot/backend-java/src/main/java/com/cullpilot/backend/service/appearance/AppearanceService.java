package com.cullpilot.backend.service.appearance;

import com.cullpilot.backend.api.ApiException;
import com.cullpilot.backend.api.appearance.AppearanceRequests.SubmitOnboarding;
import com.cullpilot.backend.api.appearance.AppearanceRequests.UpdateAppearance;
import com.cullpilot.backend.api.appearance.AppearanceResponses.CurrentAppearance;
import com.cullpilot.backend.api.appearance.AppearanceResponses.Onboarding;
import com.cullpilot.backend.api.appearance.AppearanceResponses.Recommendation;
import com.cullpilot.backend.repository.user.UserAppearanceRepository;
import com.cullpilot.backend.repository.user.UserAppearanceRepository.Appearance;
import com.cullpilot.backend.repository.user.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppearanceService {

    private final UserAppearanceRepository appearances;
    private final UserRepository users;
    private final AppearanceRecommender recommender;
    private final ObjectMapper mapper;

    public AppearanceService(UserAppearanceRepository appearances, UserRepository users,
                             AppearanceRecommender recommender, ObjectMapper mapper) {
        this.appearances = appearances;
        this.users = users;
        this.recommender = recommender;
        this.mapper = mapper;
    }

    public boolean onboardingRequired(String userId) {
        return appearances.find(userId)
                .map(item -> "pending".equals(item.onboardingStatus()))
                .orElse(true);
    }

    @Transactional
    public Onboarding questionnaire(String userId) {
        Appearance current = currentRow(userId);
        return new Onboarding(AppearanceCatalog.VERSION, AppearanceCatalog.questions(),
                AppearanceCatalog.colors(), AppearanceCatalog.styles(),
                "pending".equals(current.onboardingStatus()));
    }

    @Transactional
    public CurrentAppearance current(String userId) {
        return response(currentRow(userId));
    }

    @Transactional
    public Recommendation submit(String userId, SubmitOnboarding request) {
        Appearance current = currentRow(userId);
        if ("manual".equals(current.source()) && !request.restart()) {
            throw new ApiException(HttpStatus.CONFLICT, "MANUAL_APPEARANCE_PROTECTED",
                    "已手动选择外观；重新开始问卷时请明确设置 restart=true");
        }
        AppearanceRecommender.Result result = recommender.recommend(
                request.questionnaireVersion(), request.answers());
        if (request.apply()) {
            if (!appearances.applyRecommendation(userId, result.recommended().colorId(),
                    result.recommended().styleId(), request.questionnaireVersion(), request.restart())) {
                throw new ApiException(HttpStatus.CONFLICT, "MANUAL_APPEARANCE_PROTECTED",
                        "外观已经手动修改，请重新开始问卷");
            }
        } else {
            appearances.completeWithoutApplying(userId);
        }
        appearances.saveRecord(userId, request.questionnaireVersion(), json(request.answers()),
                json(result.scoreDetail()), json(result.recommended()), request.apply());
        return new Recommendation(result.recommended(), result.alternatives(),
                result.scoreDetail(), request.questionnaireVersion(), request.apply());
    }

    @Transactional
    public CurrentAppearance skip(String userId) {
        currentRow(userId);
        appearances.skip(userId);
        return response(appearances.find(userId).orElseThrow());
    }

    @Transactional
    public CurrentAppearance update(String userId, UpdateAppearance request) {
        if (AppearanceCatalog.color(request.colorId()) == null
                || AppearanceCatalog.style(request.styleId()) == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_APPEARANCE_ID",
                    "未知的主题色或组件风格 ID");
        }
        currentRow(userId);
        appearances.manualUpdate(userId, request.colorId(), request.styleId());
        return response(appearances.find(userId).orElseThrow());
    }

    private Appearance currentRow(String userId) {
        if (!users.existsById(userId)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "登录状态无效或已过期");
        }
        appearances.ensureDefault(userId, AppearanceCatalog.DEFAULT_COLOR_ID, AppearanceCatalog.DEFAULT_STYLE_ID);
        return appearances.find(userId).orElseThrow();
    }

    private CurrentAppearance response(Appearance item) {
        return new CurrentAppearance(item.colorId(), item.styleId(), item.source(),
                item.questionnaireVersion(), item.onboardingStatus(),
                "pending".equals(item.onboardingStatus()), item.updatedAt());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize appearance record", exception);
        }
    }
}
