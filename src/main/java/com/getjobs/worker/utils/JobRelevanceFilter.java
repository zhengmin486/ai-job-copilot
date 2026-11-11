package com.getjobs.worker.utils;

import java.util.List;

/**
 * 岗位名相关性过滤：判断一个岗位是否值得投。
 * <p>
 * 背景：BOSS/智联 按关键词搜索时，结果里会大量混入无关岗位——实测搜「Python」，
 * 第一页就有「安全服务工程师（残疾人专岗）」「it工程师」这类完全不对口的职位。
 * 原来的代码不做任何岗位级校验，搜到什么就投什么，白白消耗投递额度、拉低回复率。
 * <p>
 * 规则：先看排除词（命中即淘汰），再看匹配词（命中才通过）。
 * 想放宽或收紧投递范围，直接改下面两个列表。
 */
public final class JobRelevanceFilter {

    private JobRelevanceFilter() {
    }

    /**
     * 方向匹配词：岗位名包含其中任意一个，才认为与求职方向相关。
     * 覆盖 Python 后端 / AI 应用 / 测试开发 / 前端 这几类岗位的常见命名。
     */
    public static final List<String> MATCH_WORDS = List.of(
            "python", "后端", "服务端", "全栈",
            "开发工程师", "软件开发", "软件工程师", "研发工程师", "程序开发", "开发",
            "算法", "人工智能", "大模型", "llm", "机器学习", "深度学习", "ai",
            "数据开发", "数据仓库", "数据挖掘", "数据分析",
            "测试", "qa", "前端",
            "产品", "需求分析",
            "java", "golang", "go开发", "c++", "node"
    );

    /**
     * 排除词：岗位名命中任意一个直接跳过，优先级高于匹配词。
     * 这些岗位投了基本没人回，只会拖低回复率。
     * <p>
     * 注意：「产品经理」不在排除列表里——产品岗是有效求职方向，
     * 靠 EXCLUDE 里的「运营」「销售」挡住产品线里的偏销售岗位即可。
     */
    public static final List<String> EXCLUDE_WORDS = List.of(
            "销售", "客服", "运营", "市场", "行政", "人事", "招聘专员",
            "保险", "中介", "经纪人", "咨询顾问", "讲师", "助教", "培训师",
            "残疾人", "安全服务", "渗透测试", "等保",
            "项目经理", "实施工程师", "技术支持"
    );

    /**
     * 大厂名单：命中即不自动投递，改为「待人工定制」。
     * <p>
     * 原因：大厂的研发岗基本走官网校招或单独流程，Boss 上的大厂帖子多为销售/管培/FAE/外包，
     * 用批量通用简历投出去既浪费打招呼次数，又会让 HR 先看到一个不对口的版本。
     * 这类岗位的正确打法是人工挑、按 JD 定制后再投。
     * <p>
     * 用「包含」匹配，所以写品牌主词即可（「华为」能覆盖「华为技术有限公司」）。
     */
    public static final List<String> BIG_COMPANIES = List.of(
            "华为", "腾讯", "阿里", "字节", "百度", "美团", "京东", "网易", "小米",
            "海康", "深信服", "中兴", "大疆", "拼多多", "快手", "滴滴", "携程",
            "蔚来", "理想汽车", "比亚迪", "oppo", "vivo", "荣耀", "浪潮",
            "用友", "金蝶", "科大讯飞", "商汤", "旷视", "商汤科技", "奇安信",
            "哔哩哔哩", "b站", "小红书", "得物", "shein", "tp-link", "普联",
            "新华三", "紫光", "中国移动", "中国电信", "中国联通", "国家电网",
            "中国银行", "工商银行", "建设银行", "农业银行", "交通银行", "招商银行"
    );

    /**
     * 是否为大厂（命中则留给人工定制简历，不自动投）。
     *
     * @param companyName 公司名称
     */
    public static boolean isBigCompany(String companyName) {
        if (companyName == null || companyName.trim().isEmpty()) {
            return false;
        }
        String name = companyName.toLowerCase();
        for (String brand : BIG_COMPANIES) {
            if (name.contains(brand.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断岗位名是否与求职方向相关。
     *
     * @param jobName 岗位名称
     * @return true=可以投；false=无关岗位，应跳过
     */
    public static boolean isRelevant(String jobName) {
        if (jobName == null || jobName.trim().isEmpty()) {
            return false;
        }
        String name = jobName.toLowerCase();
        for (String word : EXCLUDE_WORDS) {
            if (name.contains(word)) {
                return false;
            }
        }
        for (String word : MATCH_WORDS) {
            if (name.contains(word)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 智联专用：方向级通用附件（后端 / AI / 测试）的岗位名过滤
    // ------------------------------------------------------------------
    // 为什么单独一套：isRelevant 被 Boss / BossService / 51job / 智联四处共用，
    // 它的 MATCH_WORDS 含裸词「开发 / 算法 / 测试 / 产品 / ai」，几乎任何岗都能命中。
    // 实测 11-09 智联投出的 30 岗里，它放进了「AI制作师（可小白）」
    // 「产品策划助理」「音频算法工程师」「功能测试工程师」等 13 个杂岗。
    // 直接改 isRelevant 会波及另外三个平台，所以智联单独走这里。
    //
    // 规则：先去掉空白再比对（「AI 应用」与「AI应用」都命中），
    // 硬排除优先，再要求命中强方向信号——不设裸词白名单。
    // 回测（zhilian_data 417 岗）：旧放行 354 → 新放行 232；
    // 11-09 已投 30 岗放行 17 / 挡 13；未投池 289 岗仍放行 157，不会没量。
    // 对口岗（软件测试工程师、含接口自动化的测试岗、AI Agent 开发）全部保住。
    // ------------------------------------------------------------------

    /** 智联硬排除：命中即跳过，优先级高于方向信号。 */
    public static final List<String> ZHILIAN_BLOCK_WORDS = List.of(
            "音频", "视觉", "图像", "多模态", "语音", "机器人", "智能驾驶", "车载",
            "硬件", "嵌入式", "驱动", "芯片", "半导体", "射频", "机械", "电子工程师",
            "机器学习", "深度学习", "模型训练", "训练工程师",
            "算法工程师", "算法研究", "算法开发", "算法岗",
            "产品经理", "产品策划", "产品助理", "产品专员", "需求分析",
            "数据分析", "数据挖掘", "数据仓库", "数据工程师", "数据开发",
            "ai制作", "制作师", "可小白", "兼职", "包吃住", "朝九晚六", "急招", "/双休",
            "前端", "安卓", "ios", "运维", "实施", "技术支持",
            "销售", "客服", "运营", "市场", "行政", "人事", "讲师", "助教",
            "测试技术员", "测试助理"
    );

    /** 智联强方向信号：必须命中至少一个，才认为与三份方向级通用稿对得上。 */
    public static final List<String> ZHILIAN_STRONG_WORDS = List.of(
            // 后端稿：FastAPI / 服务端 / 接口 / 数据库
            "python", "后端", "服务端", "全栈", "java", "golang", "go开发",
            "接口开发", "api开发",
            // AI 稿：RAG / 检索 / 模型服务化 / 评测（不含算法研究）
            "ai应用", "aiagent", "agent", "智能体", "大模型应用", "大模型开发", "大模型工程",
            "rag应用", "rag开发", "检索增强", "llm应用", "ai程序员", "ai工程师", "ai开发",
            // 测试稿：接口自动化 / pytest / 用例管理（不含纯功能测试）
            "测试开发", "接口测试", "接口自动化", "自动化测试", "自动化软件开发测试",
            "测试用例", "qa", "软件测试",
            // 通用软件开发
            "软件开发工程师", "软件工程师", "研发工程师"
    );

    /**
     * 智联专用岗位名过滤。Boss / 51job 仍走 isRelevant，不受影响。
     *
     * @param jobName 岗位名称
     * @return true=可以投；false=无关岗位，应跳过
     */
    public static boolean isRelevantForZhilian(String jobName) {
        if (jobName == null || jobName.trim().isEmpty()) {
            return false;
        }
        String name = jobName.toLowerCase().replaceAll("\\s+", "");
        for (String word : ZHILIAN_BLOCK_WORDS) {
            if (name.contains(word)) {
                return false;
            }
        }
        for (String word : ZHILIAN_STRONG_WORDS) {
            if (name.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** 放行：不限 / 应届 / 在校 / 无经验 / 1年以内 */
    private static final java.util.regex.Pattern YEAR_RANGE =
            java.util.regex.Pattern.compile("(\\d+)\\s*[-~至]\\s*(\\d+)\\s*年");
    private static final java.util.regex.Pattern YEAR_ABOVE =
            java.util.regex.Pattern.compile("(\\d+)\\s*年(以上|及以上|起)");

    /**
     * 判断岗位的经验要求是否超出「应届/在校」能投的范围。
     * <p>
     * 搜索页的 experience 参数只是筛选项，两个平台都会混入要求工作经验的岗位，
     * 所以投递前必须再看一眼岗位自己写的经验要求。
     *
     * @return true=要求 1 年以上经验，应跳过
     */
    public static boolean isExperienceTooHigh(String experience) {
        if (experience == null) return false;
        String exp = experience.trim();
        if (exp.isEmpty()) return false;
        if (exp.contains("不限") || exp.contains("应届") || exp.contains("在校") || exp.contains("无经验")) {
            return false;
        }
        if (exp.contains("1年以内") || exp.contains("1年以下")
                || exp.contains("一年以内") || exp.contains("一年以下")) {
            return false;
        }
        java.util.regex.Matcher range = YEAR_RANGE.matcher(exp);
        if (range.find()) {
            try {
                return Integer.parseInt(range.group(1)) >= 1;
            } catch (NumberFormatException ignore) {
                return true;
            }
        }
        java.util.regex.Matcher above = YEAR_ABOVE.matcher(exp);
        if (above.find()) {
            try {
                return Integer.parseInt(above.group(1)) >= 1;
            } catch (NumberFormatException ignore) {
                return true;
            }
        }
        return false;
    }

    /** 月薪天数折算：把日薪换算成月薪时用 */
    private static final int WORK_DAYS_PER_MONTH = 22;

    /**
     * 判断岗位薪资是否落在期望区间之外。
     * <p>
     * 支持四种写法：
     * <ul>
     *   <li>月薪 K：{@code 6-10K} / {@code 6-10K·13薪}</li>
     *   <li>月薪万：{@code 1-2万}</li>
     *   <li>日薪：{@code 200-300元/天}，按 22 个工作日折算成月薪</li>
     *   <li>面议 / 无数值：无法判断，直接放行</li>
     * </ul>
     * 无法解析时一律返回 false —— 无法判断不等于不符合，宁可放行也不要把好岗位误杀。
     *
     * @param salaryText 岗位薪资原文
     * @param minK       期望月薪下限（单位 K）
     * @param maxK       期望月薪上限（单位 K）
     * @return true=确实超出区间，应跳过
     */
    public static boolean isSalaryOutOfRange(String salaryText, Integer minK, Integer maxK) {
        if (salaryText == null) return false;
        String s = salaryText.trim();
        if (s.isEmpty() || s.contains("面议")) return false;

        boolean dayPay = s.contains("元/天") || s.contains("元／天");
        boolean wanPay = s.contains("万");
        boolean kPay = s.contains("K") || s.contains("k");
        if (!dayPay && !wanPay && !kPay) return false;

        // 去掉单位与年终奖后缀，只留数字区间
        String numeric = s.replace("元/天", "").replace("元／天", "")
                .replaceAll("·\\d+薪", "")
                .replace("万元", "").replace("万", "")
                .replace("K", "").replace("k", "")
                .trim();

        String[] parts = numeric.split("[-~至]");
        Integer low = parseIntOrNull(parts.length > 0 ? parts[0] : null);
        Integer high = parseIntOrNull(parts.length > 1 ? parts[1] : parts[0]);
        if (low == null) return false;          // 解析不出下限，无法判断
        if (high == null) high = low;

        long lowYuan;
        long highYuan;
        if (dayPay) {
            lowYuan = (long) low * WORK_DAYS_PER_MONTH;
            highYuan = (long) high * WORK_DAYS_PER_MONTH;
        } else if (wanPay) {
            lowYuan = (long) low * 10000;
            highYuan = (long) high * 10000;
        } else {
            lowYuan = (long) low * 1000;
            highYuan = (long) high * 1000;
        }

        // 岗位最高薪资都够不到期望下限 → 给得太少
        if (minK != null && minK > 0 && highYuan < minK * 1000L) {
            return true;
        }
        // 岗位最低薪资已高于期望上限 → 明显是资深岗，不投
        return maxK != null && maxK > 0 && lowYuan > maxK * 1000L;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null) return null;
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
