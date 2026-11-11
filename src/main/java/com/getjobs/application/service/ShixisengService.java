package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.ShixisengJobDataEntity;
import com.getjobs.application.mapper.ShixisengJobDataMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实习僧数据服务。
 * <p>
 * 只做「采集入库 + 查询 + 状态维护」，投递环节暂不接（见 docs 里的接入评估）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ShixisengService {

    private final ShixisengJobDataMapper shixisengJobDataMapper;
    private final DataSource dataSource;

    @PostConstruct
    public void ensureTableExists() {
        String createSql = "CREATE TABLE IF NOT EXISTS shixiseng_data (" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " job_id VARCHAR(64)," +
                " job_title VARCHAR(200)," +
                " job_link VARCHAR(300)," +
                " salary VARCHAR(100)," +
                " city VARCHAR(100)," +
                " degree VARCHAR(100)," +
                " industry VARCHAR(100)," +
                " company_name VARCHAR(200)," +
                " tags VARCHAR(200)," +
                " refresh_time VARCHAR(100)," +
                " delivery_status VARCHAR(20) DEFAULT '未投递'," +
                " create_time DATETIME," +
                " update_time DATETIME" +
                ")";
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createSql);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_shixiseng_job_id ON shixiseng_data(job_id)");
            log.info("确保 shixiseng_data 表已存在");
        } catch (Exception e) {
            log.warn("创建 shixiseng_data 表失败: {}", e.getMessage());
        }
    }

    public boolean existsByJobId(String jobId) {
        if (jobId == null || jobId.trim().isEmpty()) return false;
        QueryWrapper<ShixisengJobDataEntity> w = new QueryWrapper<>();
        w.eq("job_id", jobId).last("LIMIT 1");
        Long c = shixisengJobDataMapper.selectCount(w);
        return c != null && c > 0;
    }

    /**
     * 入库，返回 true 表示新岗位。
     * 实习僧岗位以 uuid 唯一，重复采集直接跳过，不做更新（避免覆盖人工改过的状态）。
     */
    public boolean insertJob(ShixisengJobDataEntity entity) {
        if (entity == null || entity.getJobId() == null) return false;
        if (existsByJobId(entity.getJobId())) return false;
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        if (entity.getDeliveryStatus() == null) entity.setDeliveryStatus("未投递");
        shixisengJobDataMapper.insert(entity);
        return true;
    }

    public List<ShixisengJobDataEntity> list(String keyword, String status, int page, int size) {
        QueryWrapper<ShixisengJobDataEntity> w = new QueryWrapper<>();
        if (keyword != null && !keyword.trim().isEmpty()) {
            String k = keyword.trim();
            w.and(q -> q.like("job_title", k).or().like("company_name", k));
        }
        if (status != null && !status.trim().isEmpty()) {
            w.eq("delivery_status", status.trim());
        }
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(size, 200));
        w.orderByDesc("id").last("LIMIT " + s + " OFFSET " + ((long) (p - 1) * s));
        return shixisengJobDataMapper.selectList(w);
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<ShixisengJobDataEntity> all = shixisengJobDataMapper.selectList(new QueryWrapper<>());
        long delivered = 0, pending = 0, custom = 0;
        for (ShixisengJobDataEntity e : all) {
            String s = e.getDeliveryStatus() == null ? "" : e.getDeliveryStatus();
            if (s.contains("已投递")) delivered++;
            else if (s.contains("待人工定制")) custom++;
            else pending++;
        }
        out.put("total", all.size());
        out.put("delivered", delivered);
        out.put("pending", pending);
        out.put("manual", custom);
        return out;
    }

    public void markStatus(Long id, String status) {
        if (id == null || status == null) return;
        ShixisengJobDataEntity upd = new ShixisengJobDataEntity();
        upd.setId(id);
        upd.setDeliveryStatus(status);
        upd.setUpdateTime(LocalDateTime.now());
        shixisengJobDataMapper.updateById(upd);
    }

    public ShixisengJobDataEntity getById(Long id) {
        if (id == null) return null;
        return shixisengJobDataMapper.selectById(id);
    }
}
