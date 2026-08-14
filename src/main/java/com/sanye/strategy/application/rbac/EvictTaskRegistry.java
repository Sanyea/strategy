package com.sanye.strategy.application.rbac;

import com.sanye.strategy.interfaces.rbac.vo.EvictTaskVO;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * <p>
 * 异步批量踢任务注册表 — 内存 ConcurrentHashMap + 独立线程池
 * </p>
 * <p>
 * 受限：服务重启丢任务（生产大批量变更需规避，见 spec 待办）；任务完成后清理防内存泄漏。
 * </p>
 * <p>
 * 设计说明：
 * <ul>
 *   <li>角色：大批量踢的异步执行器 + 进度注册表（Task 11/12/13 依赖）；线程池守护线程、随 JVM 退出。</li>
 *   <li>优缺点：实现简单、无外部依赖、状态即时可查；代价为重启丢任务、单实例内存态
 *       （集群/多实例下跨实例进度不可见，见 spec 待办——后续落库 {@code ums_evict_task} 升级）。</li>
 *   <li>清理：完成 24h 过期任务 + 存量超 {@code MAX_RETAIN} 100 的溢出淘汰，防注册表无限增长。</li>
 * </ul>
 * </p>
 */
@Service
public class EvictTaskRegistry {

    private static final int MAX_RETAIN = 100;
    private static final Duration RETAIN_DURATION = Duration.ofHours(24);

    private final ConcurrentHashMap<String, EvictTaskVO> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "evict-worker");
                t.setDaemon(true);
                return t;
            });

    /**
     * 提交异步批量踢任务
     *
     * @param action     踢动作（返回踢中会话数）
     * @param sourceDesc 来源描述（日志/进度查询展示）
     * @return 任务ID
     */
    public String submit(Supplier<Integer> action, String sourceDesc) {
        String taskId = "evict_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        EvictTaskVO vo = new EvictTaskVO();
        vo.setTaskId(taskId);
        vo.setSourceDesc(sourceDesc);
        vo.setStatus("PENDING");
        vo.setCreatedAt(LocalDateTime.now());
        tasks.put(taskId, vo);
        executor.submit(() -> {
            vo.setStatus("RUNNING");
            try {
                vo.setKicked(action.get());
                vo.setStatus("SUCCESS");
            } catch (Exception e) {
                vo.setStatus("FAILED");
                vo.setError(e.getMessage());
            } finally {
                vo.setDoneAt(LocalDateTime.now());
            }
        });
        evictStale();
        return taskId;
    }

    /**
     * 按任务ID查进度（不存在返回 null）
     */
    public EvictTaskVO get(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 清理：已结束超 24h 的任务 + 存量超上限溢出淘汰
     */
    private void evictStale() {
        List<String> stale = tasks.entrySet().stream()
                .filter(e -> e.getValue().getDoneAt() != null
                        && e.getValue().getDoneAt().plus(RETAIN_DURATION).isBefore(LocalDateTime.now()))
                .map(Map.Entry::getKey)
                .toList();
        stale.forEach(tasks::remove);
        int overflow = tasks.size() - MAX_RETAIN;
        if (overflow > 0) {
            // 溢出淘汰：优先移除已完成的旧任务（按 createdAt 升序，最旧先移，保留进行中任务进度可查）；
            // 无足够已完成任务时回退任意条目淘汰（按 taskId 串序，仅防注册表超限）
            List<Map.Entry<String, EvictTaskVO>> completed = tasks.entrySet().stream()
                    .filter(e -> e.getValue().getDoneAt() != null)
                    .sorted(Comparator.comparing((Map.Entry<String, EvictTaskVO> e) -> e.getValue().getCreatedAt()))
                    .toList();
            int removed = 0;
            for (Map.Entry<String, EvictTaskVO> e : completed) {
                if (removed >= overflow) {
                    break;
                }
                tasks.remove(e.getKey());
                removed++;
            }
            if (removed < overflow) {
                tasks.keySet().stream().sorted().limit(overflow - removed).forEach(tasks::remove);
            }
        }
    }
}
