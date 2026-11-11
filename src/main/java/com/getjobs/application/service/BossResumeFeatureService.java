package com.getjobs.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Boss 投递增强功能配置（多简历匹配规则、优质岗人工门、待处理队列）。
 * <p>
 * 这些是新功能特有的配置，刻意用 JSON 文件存储（data/boss/ 下），不改动原有数据库表结构，
 * 降低迁移风险。规则/开关由前端通过接口读写，投递主流程在运行时读取并生效。
 */
@Service
public class BossResumeFeatureService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path dataDir = Paths.get(System.getProperty("user.dir"), "data", "boss").toAbsolutePath().normalize();
    private final Path rulesFile = dataDir.resolve("resume-rules.json");
    private final Path gateFile = dataDir.resolve("review-gate.json");
    private final Path queueFile = dataDir.resolve("review-queue.json");
    private final BossResumeImageService resumeImageService;

    public BossResumeFeatureService(BossResumeImageService resumeImageService) {
        this.resumeImageService = resumeImageService;
    }

    // ===================== 简历匹配规则 =====================

    public synchronized List<ResumeRule> getResumeRules() {
        return readJson(rulesFile, new TypeReference<List<ResumeRule>>() {}, new ArrayList<>());
    }

    public synchronized void saveResumeRules(List<ResumeRule> rules) {
        if (rules == null) rules = new ArrayList<>();
        writeJson(rulesFile, rules);
    }

    /**
     * 按规则为岗位选择简历：遍历规则，命中第一条则返回对应简历路径；都不命中返回 fallback。
     */
    public Optional<java.nio.file.Path> selectResumeForJob(com.getjobs.worker.utils.Job job, Optional<java.nio.file.Path> fallback) {
        for (ResumeRule rule : getResumeRules()) {
            if (ruleMatches(rule, job)) {
                Optional<java.nio.file.Path> path = resumeImageService.getResumePath(rule.resumeId());
                if (path.isPresent()) {
                    return path;
                }
            }
        }
        return fallback;
    }

    /** 返回建议使用的简历 id（用于待处理队列提示），无匹配/无默认时返回 null。 */
    public Long suggestResumeId(com.getjobs.worker.utils.Job job) {
        for (ResumeRule rule : getResumeRules()) {
            if (ruleMatches(rule, job)) {
                return rule.resumeId();
            }
        }
        return resumeImageService.getDefaultResumeId().orElse(null);
    }

    private boolean ruleMatches(ResumeRule rule, com.getjobs.worker.utils.Job job) {
        if (rule.field() == null || rule.value() == null) return false;
        String target;
        switch (rule.field().toLowerCase()) {
            case "company" -> target = job.getCompanyName();
            case "job", "keyword" -> target = job.getJobName();
            default -> target = "";
        }
        return target != null && target.toLowerCase().contains(rule.value().toLowerCase());
    }

    // ===================== 优质岗人工门 =====================

    public synchronized ReviewGateConfig getReviewGate() {
        ReviewGateConfig cfg = readJson(gateFile, new TypeReference<ReviewGateConfig>() {}, null);
        return cfg != null ? cfg : new ReviewGateConfig(false, 0, List.of(), List.of());
    }

    public synchronized void saveReviewGate(ReviewGateConfig cfg) {
        writeJson(gateFile, cfg != null ? cfg : new ReviewGateConfig(false, 0, List.of(), List.of()));
    }

    /** 岗位是否命中「优质岗」：开关开启且（薪资≥阈值 或 公司命中 或 关键词命中）任一成立。 */
    public boolean isHighValueJob(com.getjobs.worker.utils.Job job) {
        ReviewGateConfig gate = getReviewGate();
        if (gate == null || !gate.enabled()) return false;

        boolean hit = false;
        if (gate.salaryMin() > 0) {
            int min = parseSalaryMin(job.getSalary());
            if (min >= gate.salaryMin()) hit = true;
        }
        if (!hit && gate.companies() != null) {
            String company = job.getCompanyName() == null ? "" : job.getCompanyName().toLowerCase();
            for (String c : gate.companies()) {
                if (!c.isBlank() && company.contains(c.toLowerCase())) {
                    hit = true;
                    break;
                }
            }
        }
        if (!hit && gate.keywords() != null) {
            String name = job.getJobName() == null ? "" : job.getJobName().toLowerCase();
            for (String k : gate.keywords()) {
                if (!k.isBlank() && name.contains(k.toLowerCase())) {
                    hit = true;
                    break;
                }
            }
        }
        return hit;
    }

    private int parseSalaryMin(String salary) {
        if (salary == null) return 0;
        // 形如 "15-25K"、"20K以上"、"面议"：取第一个连续数字
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(salary);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // ===================== 待处理队列 =====================

    public synchronized List<ReviewQueueItem> getReviewQueue() {
        List<ReviewQueueItem> list = readJson(queueFile, new TypeReference<List<ReviewQueueItem>>() {}, new ArrayList<>());
        list.sort(Comparator.comparingLong(ReviewQueueItem::createdAt).reversed());
        return list;
    }

    public synchronized void enqueueHighValueJob(com.getjobs.worker.utils.Job job, Long suggestedResumeId) {
        List<ReviewQueueItem> list = readJson(queueFile, new TypeReference<List<ReviewQueueItem>>() {}, new ArrayList<>());
        long id = list.stream().mapToLong(ReviewQueueItem::id).max().orElse(0L) + 1L;
        list.add(new ReviewQueueItem(id,
                job.getCompanyName(),
                job.getJobName(),
                job.getSalary(),
                job.getHref(),
                suggestedResumeId,
                "PENDING",
                System.currentTimeMillis()));
        writeJson(queueFile, list);
    }

    public synchronized void markQueueItemDone(long id) {
        List<ReviewQueueItem> list = readJson(queueFile, new TypeReference<List<ReviewQueueItem>>() {}, new ArrayList<>());
        list.replaceAll(item -> item.id() == id ? new ReviewQueueItem(item.id(), item.company(), item.jobName(),
                item.salary(), item.jobUrl(), item.suggestedResumeId(), "DONE", item.createdAt()) : item);
        writeJson(queueFile, list);
    }

    // ===================== 通用 JSON 读写 =====================

    private <T> T readJson(Path file, TypeReference<T> ref, T fallback) {
        if (!Files.isRegularFile(file)) return fallback;
        try {
            return objectMapper.readValue(file.toFile(), ref);
        } catch (IOException e) {
            return fallback;
        }
    }

    private void writeJson(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
        } catch (IOException e) {
            throw new IllegalStateException("写入功能配置文件失败：" + file, e);
        }
    }

    // ===================== 记录类型 =====================

    /** 简历匹配规则：field=company|job|keyword，value=匹配串（包含即命中），resumeId=使用的简历库 id。 */
    public record ResumeRule(String field, String value, long resumeId) {
    }

    public record ReviewGateConfig(boolean enabled, int salaryMin, List<String> companies, List<String> keywords) {
    }

    public record ReviewQueueItem(long id, String company, String jobName, String salary, String jobUrl,
                                  Long suggestedResumeId, String status, long createdAt) {
    }
}
