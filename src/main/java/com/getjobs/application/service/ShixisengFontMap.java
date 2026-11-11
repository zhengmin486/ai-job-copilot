package com.getjobs.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实习僧字体加密映射表。
 * <p>
 * 实习僧把岗位名里的部分汉字换成了字符引用，例如「数据挖掘实习&#xf069」实际上是
 * 「数据挖掘实习生」。这些引用在 SSR 数据里是<b>字面文本</b>（多数还缺少结尾分号），
 * 浏览器渲染时才解码成图标字体，所以直接读 JSON 拿不到汉字。
 * <p>
 * 详情页没有这层加密（{@code state.internInfo.iname} 是明文），且与列表名逐字对应。
 * 于是可以对齐学习：把引用 token 视作一个占位符，「数据挖掘实习〔t〕」+「数据挖掘实习生」
 * ⇒ 〔t〕= 生。学到的映射落在 {@code data/shixiseng/font-map.json}，之后同类岗位直接还原，
 * 不必再为每条岗位多跑一次详情页。
 */
@Slf4j
@Service
public class ShixisengFontMap {

    /**
     * 字体加密 token：&#xf069 / &#xf069; / &#61417; 三种形态都见过。
     * 这里不加结尾分号要求，因为 SSR 数据里普遍没有。
     */
    private static final Pattern TOKEN = Pattern.compile("&#x?[0-9a-fA-F]{2,6};?");

    /** 骨架里的占位符 */
    private static final char SLOT = '\u0001';

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path storePath = Paths.get("data", "shixiseng", "font-map.json");
    private final Map<String, String> map = new LinkedHashMap<>();

    public ShixisengFontMap() {
        load();
    }

    public synchronized void load() {
        try {
            if (!Files.exists(storePath)) return;
            String json = Files.readString(storePath, StandardCharsets.UTF_8);
            Map<String, String> raw = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
            map.clear();
            raw.forEach((k, v) -> {
                if (k != null && !k.isEmpty() && v != null && !v.isEmpty()) map.put(k, v);
            });
            log.info("已加载实习僧字体映射 {} 条", map.size());
        } catch (Exception e) {
            log.warn("加载实习僧字体映射失败：{}", e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(storePath.getParent());
            Files.writeString(storePath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(map),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("保存实习僧字体映射失败：{}", e.getMessage());
        }
    }

    /** 按顺序取出文本里的所有加密 token */
    private List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** 骨架：每个 token 压成一个占位符，用于和明文逐字对齐 */
    private String skeleton(String text) {
        return TOKEN.matcher(text).replaceAll(String.valueOf(SLOT));
    }

    /** 用已有映射还原；未知 token 原样保留 */
    public synchronized String apply(String text) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            String mapped = map.get(m.group());
            sb.append(mapped != null ? mapped : m.group());
            last = m.end();
        }
        sb.append(text.substring(last));
        return sb.toString();
    }

    /** 是否还有没学会的 token */
    public synchronized boolean hasUnknown(String text) {
        if (text == null) return false;
        for (String t : tokens(text)) {
            if (!map.containsKey(t)) return true;
        }
        return false;
    }

    /**
     * 从「列表名（含 token）」与「详情名（明文）」逐字对齐学习。
     *
     * @return 本次新学到的 token 数
     */
    public synchronized int learn(String obfuscated, String plain) {
        if (obfuscated == null || plain == null) return 0;
        String a = obfuscated.trim();
        String b = plain.trim();
        String skel = skeleton(a);
        if (skel.length() != b.length()) {
            log.debug("长度不一致，跳过学习：{}（骨架长度 {}） vs {}（长度 {}）", a, skel.length(), b, b.length());
            return 0;
        }
        List<String> toks = tokens(a);
        int learned = 0;
        int ti = 0;
        for (int i = 0; i < skel.length(); i++) {
            if (skel.charAt(i) == SLOT) {
                if (ti >= toks.size()) break;
                String token = toks.get(ti++);
                String target = String.valueOf(b.charAt(i));
                if (!target.isBlank() && !map.containsKey(token)) {
                    map.put(token, target);
                    learned++;
                }
            }
        }
        if (learned > 0) {
            log.info("学到 {} 个实习僧加密 token，累计 {} 个", learned, map.size());
        }
        return learned;
    }

    /** 入库前的兜底：把仍未还原的 token 去掉，避免页面出现 &#xf069 这类乱码 */
    public String stripUnknown(String text) {
        if (text == null) return null;
        String out = TOKEN.matcher(text).replaceAll("").trim();
        return out.isEmpty() ? null : out;
    }

    public synchronized int size() {
        return map.size();
    }

    /** 供排查用：当前映射快照 */
    public synchronized Map<String, String> snapshot() {
        return new HashMap<>(map);
    }
}
