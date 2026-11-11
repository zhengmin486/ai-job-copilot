package com.getjobs.worker.service;

import com.getjobs.application.service.ShixisengService;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.shixiseng.Shixiseng;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实习僧采集任务服务。
 * <p>
 * 采集不需要登录，但 Playwright 连接是单线程的，所以任务必须和其他平台一样
 * 跑在 {@link PlaywrightManager#callOnPlaywright} 上，否则会和 Boss/智联的页面操作互相打断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShixisengJobService {

    private final PlaywrightManager playwrightManager;
    private final ShixisengService shixisengService;
    private final ObjectProvider<Shixiseng> shixisengProvider;

    private volatile boolean isRunning = false;

    public boolean isRunning() {
        return isRunning;
    }

    /**
     * 执行一次采集。同步返回结果，前端调用时会等待（单次采集通常 10-60 秒）。
     */
    public Map<String, Object> collect(List<String> keywords, String city, int pages) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (isRunning) {
            out.put("success", false);
            out.put("message", "采集任务已在运行中");
            return out;
        }
        isRunning = true;
        try {
            playwrightManager.ensureReady();
            Page anchor = playwrightManager.getBossPage();
            if (anchor == null) {
                out.put("success", false);
                out.put("message", "浏览器页面未初始化，请先打开任一平台页面");
                return out;
            }

            Shixiseng shixiseng = shixisengProvider.getObject();
            Shixiseng.CollectResult r = playwrightManager.callOnPlaywright(() -> {
                // 用独立标签页，避免污染 Boss/智联当前页面
                Page work = anchor.context().newPage();
                try {
                    return shixiseng.collect(work, keywords, city, pages, (msg, cur, total) ->
                            log.info("[实习僧] {}", msg));
                } finally {
                    try {
                        work.close();
                    } catch (Exception ignore) {
                    }
                }
            });

            out.put("success", true);
            out.put("scanned", r == null ? 0 : r.scanned);
            out.put("inserted", r == null ? 0 : r.inserted);
            out.put("pages", r == null ? 0 : r.pages);
            out.put("detailFetched", r == null ? 0 : r.detailFetched);
            out.put("message", r == null ? "采集完成" :
                    String.format("采集完成：扫描 %d 条，新入库 %d 条（为还原标题补抓详情 %d 条）",
                            r.scanned, r.inserted, r.detailFetched));
            log.info("实习僧采集结束：{}", out);
            return out;
        } catch (Exception e) {
            log.error("实习僧采集失败", e);
            out.put("success", false);
            out.put("message", "采集失败: " + e.getMessage());
            return out;
        } finally {
            isRunning = false;
        }
    }
}
