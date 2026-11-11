package com.getjobs.application.controller;

import com.getjobs.application.entity.BossConfigEntity;
import com.getjobs.application.entity.BossOptionEntity;
import com.getjobs.application.service.BossResumeFeatureService;
import com.getjobs.application.service.BossResumeFeatureService.ResumeRule;
import com.getjobs.application.service.BossResumeFeatureService.ReviewGateConfig;
import com.getjobs.application.service.BossResumeFeatureService.ReviewQueueItem;
import com.getjobs.application.service.BossService;
import com.getjobs.application.service.BossResumeImageService;
import com.getjobs.application.service.BossResumeImageService.ResumeImageInfo;
import com.getjobs.application.entity.BlacklistEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.CrossOrigin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/boss/config")
public class BossConfigController {

    private final BossService bossService;
    private final BossResumeImageService bossResumeImageService;
    private final BossResumeFeatureService bossResumeFeatureService;

    public BossConfigController(BossService bossService, BossResumeImageService bossResumeImageService,
                                BossResumeFeatureService bossResumeFeatureService) {
        this.bossService = bossService;
        this.bossResumeImageService = bossResumeImageService;
        this.bossResumeFeatureService = bossResumeFeatureService;
    }

    /**
     * 获取所有Boss配置信息（包括所有选项和黑名单）
     */
    @GetMapping
    public Map<String, Object> getAllBossConfig() {
        Map<String, Object> result = new HashMap<>();

        // 获取配置
        BossConfigEntity config = bossService.getFirstConfig();
        if (config == null) {
            config = new BossConfigEntity();
        }

        // 获取所有选项并按类型分组
        if (config.getRepeatChatMessages() == null) {
            config.setRepeatChatMessages(com.getjobs.worker.boss.BossConfig.DEFAULT_REPEAT_CHAT_MESSAGES);
        }

        Map<String, List<BossOptionEntity>> options = new HashMap<>();
        options.put("city", bossService.getOptionsByType("city"));
        options.put("industry", bossService.getOptionsByType("industry"));
        options.put("experience", bossService.getOptionsByType("experience"));
        options.put("jobType", bossService.getOptionsByType("jobType"));
        options.put("salary", bossService.getOptionsByType("salary"));
        options.put("degree", bossService.getOptionsByType("degree"));
        options.put("scale", bossService.getOptionsByType("scale"));
        options.put("stage", bossService.getOptionsByType("stage"));

        // 获取黑名单列表
        List<BlacklistEntity> blacklist = bossService.getAllBlacklist();

        result.put("config", config);
        result.put("options", options);
        result.put("blacklist", blacklist);
        result.put("resumeImage", bossResumeImageService.getMetadata());

        return result;
    }

    /**
     * 更新Boss配置
     */
  @PutMapping
  public BossConfigEntity updateConfig(@RequestBody BossConfigEntity config) {
        // 关键词标准化：将来自前端的逗号分隔或括号列表统一转换为 JSON 字符串列表
        config.setKeywords(normalizeKeywords(config.getKeywords()));

        // 将前端可能传来的『代码列表』转换并保存成『中文名称列表/值』
        // 城市：保存中文名（单值）
        if (config.getCityCode() != null) {
            String cityName = bossService.normalizeCityToName(config.getCityCode());
            config.setCityCode(cityName);
        }
        // 其它多选：保存为中文名称的括号列表
        if (config.getIndustry() != null) {
            java.util.List<String> names = bossService.toNames("industry", bossService.parseListString(config.getIndustry()));
            config.setIndustry(bossService.toBracketListString(names));
        }
        if (config.getExperience() != null) {
            java.util.List<String> names = bossService.toNames("experience", bossService.parseListString(config.getExperience()));
            config.setExperience(bossService.toBracketListString(names));
        }
        if (config.getDegree() != null) {
            java.util.List<String> names = bossService.toNames("degree", bossService.parseListString(config.getDegree()));
            config.setDegree(bossService.toBracketListString(names));
        }
        if (config.getScale() != null) {
            java.util.List<String> names = bossService.toNames("scale", bossService.parseListString(config.getScale()));
            config.setScale(bossService.toBracketListString(names));
        }
        if (config.getStage() != null) {
            java.util.List<String> names = bossService.toNames("stage", bossService.parseListString(config.getStage()));
            config.setStage(bossService.toBracketListString(names));
        }
        if (config.getSalary() != null) {
            java.util.List<String> names = bossService.toNames("salary", bossService.parseListString(config.getSalary()));
            config.setSalary(bossService.toBracketListString(names));
        }

        // 职位类型：保存为中文名（单值）
        if (config.getJobType() != null) {
            java.util.List<String> list = bossService.parseListString(config.getJobType());
            java.util.List<String> names = bossService.toNames("jobType", list);
            String name = names != null && !names.isEmpty()
                    ? names.get(0)
                    : (bossService.getOptionByTypeAndCode("jobType", config.getJobType()) != null
                        ? bossService.getOptionByTypeAndCode("jobType", config.getJobType()).getName()
                        : config.getJobType());
            config.setJobType(name);
        }

        // 为避免每次新增导致错乱：当ID缺失时也执行“选择性更新第一条”策略
        // 若存在ID，按ID更新；否则更新首条记录（若不存在则插入）
        if (config.getId() != null) {
            return bossService.updateConfig(config);
        }
        return bossService.saveOrUpdateFirstSelective(config);
  }

    /**
     * 将关键词字符串标准化为 JSON 字符串列表。
     * 支持输入形式：
     * 1) 逗号分隔："大模型, Python, Golang"
     * 2) 中文逗号："大模型，Python，Golang"
     * 3) 括号列表："[大模型,Python]" 或 "[\"大模型\",\"Python\"]"
     * 4) JSON 数组："["大模型","Python"]"
     */
    private String normalizeKeywords(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return "[]";

        ObjectMapper mapper = new ObjectMapper();
        // 优先尝试 JSON 解析
        if (s.startsWith("[") && s.endsWith("]")) {
            try {
                JsonNode node = mapper.readTree(s);
                if (node.isArray()) {
                    java.util.List<String> list = new ArrayList<>();
                    node.forEach(it -> list.add(it.asText().trim()));
                    return mapper.writeValueAsString(list);
                }
            } catch (Exception ignore) {
                // 非严格 JSON，继续走分隔解析
            }
            // 去除括号后按逗号拆分
            s = s.substring(1, s.length() - 1);
        }

        java.util.List<String> items = Arrays.stream(s.split("[,，]"))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .map(v -> v.replaceAll("^\"|\"$", ""))
                .collect(Collectors.toList());
        try {
            return mapper.writeValueAsString(items);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 获取指定类型的选项列表
     */
    @GetMapping("/options/{type}")
    public List<BossOptionEntity> getOptionsByType(@PathVariable String type) {
        return bossService.getOptionsByType(type);
    }

    /**
     * 获取黑名单列表
     */
    @GetMapping("/blacklist")
    public List<BlacklistEntity> getBlacklist() {
        return bossService.getAllBlacklist();
    }

    /**
     * 添加黑名单
     */
    @PostMapping("/blacklist")
    public BlacklistEntity addBlacklist(@RequestBody BlacklistEntity blacklist) {
        // 添加黑名单，将keyword映射到value
        String value = blacklist.getValue() != null ? blacklist.getValue() : "";
        String type = blacklist.getType() != null ? blacklist.getType() : "boss";

        boolean success = bossService.addBlacklist(type, value);
        if (success) {
            // 添加成功，返回新创建的实体
            blacklist.setId(null); // 重新从数据库获取
            return blacklist;
        } else {
            // 已存在或添加失败
            return blacklist;
        }
    }

    /**
     * 删除黑名单
     */
    @DeleteMapping("/blacklist/{id}")
    public boolean deleteBlacklist(@PathVariable Long id) {
        // 直接通过ID查找并删除
        List<BlacklistEntity> allBlacklist = bossService.getAllBlacklist();
        BlacklistEntity entity = allBlacklist.stream()
                .filter(e -> e.getId().equals(id))
                .findFirst()
                .orElse(null);
        if (entity != null) {
            return bossService.removeBlacklist(entity.getType(), entity.getValue());
        }
        return false;
    }

    /** 上传或替换 Boss 图片简历。 */
    @PostMapping(value = "/resume-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadResumeImage(@RequestParam("file") MultipartFile file) {
        try {
            bossResumeImageService.save(file);
            return ResponseEntity.ok(Map.<String, Object>of(
                    "success", true,
                    "message", "图片简历上传成功",
                    "resumeImage", bossResumeImageService.getMetadata()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.<String, Object>of(
                    "success", false,
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.<String, Object>of(
                    "success", false,
                    "message", "图片简历保存失败：" + e.getMessage()
            ));
        }
    }

    /** 返回图片内容供管理页面预览。 */
    @GetMapping("/resume-image/content")
    public ResponseEntity<Resource> getResumeImageContent() {
        return bossResumeImageService.getCurrentImage()
                .map(this::resumeImageResponse)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.<String, Object>of(
                "success", false,
                "message", "图片大小不能超过 10MB"
        ));
    }

    private ResponseEntity<Resource> resumeImageResponse(ResumeImageInfo info) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(info.contentType()))
                .contentLength(info.size())
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(info.path()));
    }

    // ===================== 多简历库 =====================

    /** 上传一份新简历到简历库（需带 name 参数）。 */
    @PostMapping(value = "/resume", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadResume(@RequestParam("file") MultipartFile file,
                                                            @RequestParam(value = "name", defaultValue = "简历") String name) {
        try {
            BossResumeImageService.ResumeMeta meta = bossResumeImageService.saveResume(file, name);
            return ResponseEntity.ok(Map.<String, Object>of("success", true, "message", "简历已加入简历库", "data", meta));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.<String, Object>of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.<String, Object>of("success", false, "message", "简历保存失败：" + e.getMessage()));
        }
    }

    /** 列出简历库所有简历。 */
    @GetMapping("/resumes")
    public ResponseEntity<Map<String, Object>> listResumes() {
        return ResponseEntity.ok(Map.<String, Object>of("success", true, "data", bossResumeImageService.listResumes()));
    }

    /** 设置某份简历为默认（普通岗未命中规则时使用）。 */
    @PostMapping("/resume/{id}/default")
    public ResponseEntity<Map<String, Object>> setDefaultResume(@PathVariable("id") long id) {
        try {
            bossResumeImageService.setDefault(id);
            return ResponseEntity.ok(Map.<String, Object>of("success", true, "message", "已设为默认简历"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.<String, Object>of("success", false, "message", e.getMessage()));
        }
    }

    /** 删除一份简历。 */
    @DeleteMapping("/resume/{id}")
    public ResponseEntity<Map<String, Object>> deleteResume(@PathVariable("id") long id) {
        try {
            bossResumeImageService.deleteResume(id);
            return ResponseEntity.ok(Map.<String, Object>of("success", true, "message", "简历已删除"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.<String, Object>of("success", false, "message", e.getMessage()));
        }
    }

    /** 预览某份简历图片。 */
    @GetMapping("/resume/{id}/content")
    public ResponseEntity<Resource> getResumeContent(@PathVariable("id") long id) {
        return bossResumeImageService.getResumeMeta(id)
                .map(meta -> bossResumeImageService.getResumePath(id)
                        .map(path -> resumeImageResponse(new ResumeImageInfo(path, meta.fileName(),
                                meta.fileName().endsWith(".png") ? "image/png"
                                        : meta.fileName().endsWith(".gif") ? "image/gif" : "image/jpeg",
                                sizeOf(path), 0L)))
                        .orElse(ResponseEntity.notFound().build()))
                .orElse(ResponseEntity.notFound().build());
    }

    private static long sizeOf(java.nio.file.Path path) {
        try {
            return java.nio.file.Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }

    // ===================== 简历匹配规则 =====================

    @GetMapping("/resume-rules")
    public ResponseEntity<Map<String, Object>> getResumeRules() {
        return ResponseEntity.ok(Map.<String, Object>of("success", true, "data", bossResumeFeatureService.getResumeRules()));
    }

    @PostMapping("/resume-rules")
    public ResponseEntity<Map<String, Object>> saveResumeRules(@RequestBody List<ResumeRule> rules) {
        bossResumeFeatureService.saveResumeRules(rules);
        return ResponseEntity.ok(Map.<String, Object>of("success", true, "message", "匹配规则已保存"));
    }

    // ===================== 优质岗人工门 =====================

    @GetMapping("/review-gate")
    public ResponseEntity<Map<String, Object>> getReviewGate() {
        return ResponseEntity.ok(Map.<String, Object>of("success", true, "data", bossResumeFeatureService.getReviewGate()));
    }

    @PostMapping("/review-gate")
    public ResponseEntity<Map<String, Object>> saveReviewGate(@RequestBody ReviewGateConfig config) {
        bossResumeFeatureService.saveReviewGate(config);
        return ResponseEntity.ok(Map.<String, Object>of("success", true, "message", "人工门设置已保存"));
    }

    @GetMapping("/review-queue")
    public ResponseEntity<Map<String, Object>> getReviewQueue() {
        return ResponseEntity.ok(Map.<String, Object>of("success", true, "data", bossResumeFeatureService.getReviewQueue()));
    }

    @PostMapping("/review-queue/{id}/done")
    public ResponseEntity<Map<String, Object>> markQueueItemDone(@PathVariable("id") long id) {
        bossResumeFeatureService.markQueueItemDone(id);
        return ResponseEntity.ok(Map.<String, Object>of("success", true, "message", "已标记为处理完成"));
    }
}
