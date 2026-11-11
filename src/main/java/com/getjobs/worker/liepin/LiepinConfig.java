package com.getjobs.worker.liepin;

import lombok.Data;

import java.util.List;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 */
@Data
public class LiepinConfig {
    /**
     * 搜索关键词列表
     */
    private List<String> keywords;

    /**
     * 城市编码
     */
    private String cityCode;

    /**
     * 薪资范围
     */
    private String salary;

    /**
     * 单轮投递上限，null 或 <=0 表示用默认值（10）
     */
    private Integer maxDeliverCount;

    /**
     * 岗位间等待基数（秒），实际取 [waitTime/2, waitTime]；null 表示用默认值（40）
     */
    private Integer waitTime;

}
