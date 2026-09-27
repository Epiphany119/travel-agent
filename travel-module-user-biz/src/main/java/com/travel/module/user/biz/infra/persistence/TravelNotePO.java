package com.travel.module.user.biz.infra.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("travel_note")
public class TravelNotePO {
    @TableId(type = IdType.AUTO) private Long id;
    private String userId;
    private String title;
    private String destination;
    private String noteType;
    private String sourceType;
    private Long sourceId;
    /** 由 A2A 运行时创建的可恢复计划标识。 */
    private String planId;
    private Integer templateVersion;
    private String status;
    private String visibility;
    private String shareToken;
    private String coverUrl;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer totalDays;
    private Integer travelers;
    private java.math.BigDecimal budget;
    private String contentJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
