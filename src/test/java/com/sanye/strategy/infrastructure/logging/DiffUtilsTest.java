package com.sanye.strategy.infrastructure.logging;

import com.sanye.strategy.domain.enums.RoleStatusEnum;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DiffUtils 单测 — POJO diff（标量/枚举/凭据剔除/PII 掩码/审计字段忽略）+ 关联集 diff + JSON 序列化
 * <p>测试用 record 承载样例字段（反射读私有 final 组件字段）。</p>
 */
class DiffUtilsTest {

    /** 测试 POJO：id/roleName/status/password/phone/createTime 覆盖忽略/标量/枚举/凭据/PII 全形态 */
    record Sample(Long id, String roleName, RoleStatusEnum status,
                  String password, String phone, String createTime) {
    }

    private static Sample sample(Long id, String roleName, RoleStatusEnum status,
                                 String password, String phone, String createTime) {
        return new Sample(id, roleName, status, password, phone, createTime);
    }

    @Test
    void reportsOnlyChangedScalarAndEnumFields() {
        Sample oldS = sample(1L, "运营", RoleStatusEnum.NORMAL, "secret", "13812345678", "2026-01-01");
        Sample newS = sample(1L, "运营专员", RoleStatusEnum.DISABLED, "secret", "13812345678", "2026-01-01");

        List<Map<String, Object>> diff = DiffUtils.diffBean(oldS, newS);

        assertEquals(2, diff.size());
        assertEquals("roleName", diff.get(0).get("field"));
        assertEquals("运营", diff.get(0).get("old"));
        assertEquals("运营专员", diff.get(0).get("new"));
        assertEquals("status", diff.get(1).get("field"));
        assertEquals("1", diff.get(1).get("old"));   // getCode() 落码值
        assertEquals("0", diff.get(1).get("new"));
    }

    @Test
    void ignoresNewNullAndUnchangedFields() {
        Sample oldS = sample(1L, "运营", RoleStatusEnum.NORMAL, "secret", "13812345678", "2026-01-01");
        // password new=null → 部分更新未涉及不报；其余字段未变 → 空 diff
        Sample newS = sample(1L, "运营", RoleStatusEnum.NORMAL, null, "13812345678", "2026-01-01");

        assertTrue(DiffUtils.diffBean(oldS, newS).isEmpty());
    }

    @Test
    void ignoresIdAndAuditFields() {
        Sample oldS = sample(1L, "运营", RoleStatusEnum.NORMAL, "secret", "13812345678", "2026-01-01");
        // id 变 + createTime 变 → 均在忽略清单，不报
        Sample newS = sample(2L, "运营", RoleStatusEnum.NORMAL, "secret", "13812345678", "2026-01-02");

        assertTrue(DiffUtils.diffBean(oldS, newS).isEmpty());
    }

    @Test
    void credentialFieldEmitsChangedPlaceholder() {
        Sample oldS = sample(1L, "运营", RoleStatusEnum.NORMAL, "old-secret", "13812345678", "2026-01-01");
        Sample newS = sample(1L, "运营", RoleStatusEnum.NORMAL, "new-secret", "13812345678", "2026-01-01");

        List<Map<String, Object>> diff = DiffUtils.diffBean(oldS, newS);

        assertEquals(1, diff.size());
        assertEquals("password", diff.get(0).get("field"));
        assertEquals("changed", diff.get(0).get("op"));
        assertNull(diff.get(0).get("old"));   // 凭据值永不出现（规格 7.5）
    }

    @Test
    void piiFieldMasksOldAndNew() {
        Sample oldS = sample(1L, "运营", RoleStatusEnum.NORMAL, "secret", "13812345678", "2026-01-01");
        Sample newS = sample(1L, "运营", RoleStatusEnum.NORMAL, "secret", "13987654321", "2026-01-01");

        List<Map<String, Object>> diff = DiffUtils.diffBean(oldS, newS);

        assertEquals(1, diff.size());
        assertEquals("phone", diff.get(0).get("field"));
        assertEquals("138****5678", diff.get(0).get("old"));
        assertEquals("139****4321", diff.get(0).get("new"));
    }

    @Test
    void diffIdSetAddsAndRemoves() {
        List<Map<String, Object>> diff = DiffUtils.diffIdSet("roleIds", Set.of(1L, 2L), Set.of(2L, 3L));

        assertEquals(2, diff.size());
        assertEquals("add", diff.get(0).get("op"));
        assertEquals(List.of(3L), diff.get(0).get("ids"));
        assertEquals("remove", diff.get(1).get("op"));
        assertEquals(List.of(1L), diff.get(1).get("ids"));
    }

    @Test
    void toChangeDiffJsonSerializesArray() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("field", "roleName");
        entry.put("old", "a");
        entry.put("new", "b");
        List<Map<String, Object>> entries = List.of(entry);

        assertEquals("[{\"field\":\"roleName\",\"old\":\"a\",\"new\":\"b\"}]", DiffUtils.toChangeDiffJson(entries));
    }

    @Test
    void emptyDiffReturnsNullJson() {
        assertNull(DiffUtils.toChangeDiffJson(List.of()));
        assertNull(DiffUtils.toChangeDiffJson(null));
    }
}