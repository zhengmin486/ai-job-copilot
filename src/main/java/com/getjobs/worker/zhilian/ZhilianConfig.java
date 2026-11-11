package com.getjobs.worker.zhilian;

import com.getjobs.worker.utils.JobUtils;
import lombok.Data;
import lombok.SneakyThrows;

import java.util.List;
import java.util.Objects;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 */
@Data
public class ZhilianConfig {
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
     * 单次任务最多成功投递多少家，0 或 null 表示不限。
     * <p>
     * 智联没有经验过滤、也没有投递量控制，不加这个字段会一路翻到第 50 页把所有能点的岗位点完。
     */
    private Integer maxDeliverCount;

    /**
     * 关键词 → 附件简历文件名。
     * <p>智联投递发的是「默认投递」标记的那份附件 PDF，所以按关键词批次切换即可，
     * 不需要（也没法）逐岗切换。
     */
    private java.util.Map<String, String> resumeMap;


    // 注意：已改为在 ZhilianJobService 中通过 ConfigService 构建配置
    // 保留空的 init 以兼容旧调用，但建议不要再使用
    @SneakyThrows
    public static ZhilianConfig init() {
        throw new UnsupportedOperationException("请在 ZhilianJobService 中通过 ConfigService 构建配置");
    }

}
