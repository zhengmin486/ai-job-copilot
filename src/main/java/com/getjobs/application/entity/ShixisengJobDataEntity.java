package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * shixiseng_data 表实体（实习僧实习岗位）
 */
@Data
@TableName("shixiseng_data")
public class ShixisengJobDataEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 实习僧岗位 uuid，如 inn_xxxx */
    @TableField("job_id")
    private String jobId;

    @TableField("job_title")
    private String jobTitle;

    @TableField("job_link")
    private String jobLink;

    /** 薪资展示文本，如 200-300元/天 */
    @TableField("salary")
    private String salary;

    @TableField("city")
    private String city;

    @TableField("degree")
    private String degree;

    @TableField("industry")
    private String industry;

    @TableField("company_name")
    private String companyName;

    /** 标签，如 可转正、大牛带 */
    @TableField("tags")
    private String tags;

    /** 页面上的刷新时间文本 */
    @TableField("refresh_time")
    private String refreshTime;

    /** 未投递 / 待人工定制 / 已投递 / 已过滤 */
    @TableField("delivery_status")
    private String deliveryStatus;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
