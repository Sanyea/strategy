package com.sanye.strategy.common.base;

/**
 * @author 31372
 */
public interface IPersistEnum<T> {
    /**
     * 获取要存到数据库的值（类型可以是 Integer/String/Long/Byte）
     */
    T getPersistValue();
}
