package com.travel.module.user.biz.api.dto;

import com.travel.module.user.biz.infra.persistence.InspirationPO;
import com.travel.module.user.biz.infra.persistence.JourneyPointPO;
import com.travel.module.user.biz.infra.persistence.JourneyPO;
import com.travel.module.user.biz.infra.persistence.TravelNotePO;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Atomic persistence request for an AI generated plan. */
@Data
@NoArgsConstructor
public class SaveAiPlanRequest {
    private String target;
    private TravelNotePO note;
    private InspirationPO inspiration;
    private JourneyPO journey;
    private List<JourneyPointPO> points;
}
