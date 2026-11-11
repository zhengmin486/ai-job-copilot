package com.getjobs.application.controller;

import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨平台「有没有回复」检查。
 *
 * <p>判据取自站点自己给出的信号，不做臆测：
 * <ul>
 *   <li>Boss / 智联：顶部导航的「消息 N」未读角标 + 聊天列表首屏会话</li>
 *   <li>实习僧：「我的投递」里被查看/初筛/面试等状态分页（见 ShixisengController#replies）</li>
 * </ul>
 *
 * <p>所有页面都在**临时标签页**里打开，抓完即关，不打断 Boss/智联的常驻页面和后台监控。
 */
@Slf4j
@RestController
@RequestMapping("/api/replies")
@RequiredArgsConstructor
public class ReplyCheckController {

    private final PlaywrightManager playwrightManager;

    /**
     * 三平台未读总览：返回各平台导航栏「消息」文本、聊天页首屏会话、以及页面正文片段。
     */
    @GetMapping("/unread")
    public ResponseEntity<Map<String, Object>> unread() {
        Map<String, Object> out = new HashMap<>();
        try {
            Map<String, Object> data = playwrightManager.callOnPlaywright(() -> {
                Map<String, Object> r = new LinkedHashMap<>();
                BrowserContext ctx = playwrightManager.getContext();
                if (ctx == null) {
                    r.put("error", "浏览器未就绪");
                    return r;
                }
                r.put("boss", probe(ctx, "https://www.zhipin.com/web/geek/chat"));
                // 用「我的智联」而不是首页：这里才有「N 我的投递 / N 面试邀请」的准确计数
                r.put("zhilian", probe(ctx, "https://i.zhaopin.com/"));
                return r;
            });
            out.put("success", true);
            out.putAll(data);
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "查询失败: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /** 在临时标签页打开 url，抓「消息」角标、会话列表项、正文片段，然后关掉标签页。 */
    private Map<String, Object> probe(BrowserContext ctx, String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        Page tmp = null;
        try {
            tmp = ctx.newPage();
            tmp.setDefaultTimeout(30_000);
            tmp.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(30_000));
            Thread.sleep(5_000);
            m.put("url", tmp.url());
            m.put("title", tmp.title());

            // 导航栏里的「消息 N」角标：文本里出现「消息」的元素，取最靠前几个
            Object badges = tmp.evaluate(
                    "(function(){" +
                    " var out=[];" +
                    " document.querySelectorAll('a,span,div,li').forEach(function(e){" +
                    "   var t=(e.innerText||'').replace(/\\s+/g,'').trim();" +
                    "   if(t.length<=12 && /^消息\\d*$/.test(t) && !out.includes(t)) out.push(t);" +
                    " });" +
                    " return out.slice(0,6);" +
                    "})()");
            m.put("msgBadges", badges);

            // 聊天列表首屏会话（Boss 是 li 列表，取前若干个的标题+摘要+时间）
            Object chats = tmp.evaluate(
                    "(function(){" +
                    " var sels=['.geek-chat-list li','.chat-user-list li','.user-list li','.chat-list li','[class*=chat-item]'];" +
                    " for(var i=0;i<sels.length;i++){" +
                    "   var ns=document.querySelectorAll(sels[i]);" +
                    "   if(ns.length){ return Array.from(ns).slice(0,15).map(function(e){" +
                    "     return (e.innerText||'').replace(/\\s+/g,' ').trim().slice(0,120);}); }" +
                    " }" +
                    " return [];" +
                    "})()");
            m.put("chats", chats);

            m.put("text", tmp.evaluate(
                    "document.body?document.body.innerText.replace(/\\s+/g,' ').slice(0,1200):''"));
        } catch (Exception e) {
            m.put("error", e.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    tmp.close();
                } catch (Exception ignore) {
                }
            }
        }
        return m;
    }
}
