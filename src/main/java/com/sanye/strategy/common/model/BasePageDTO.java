package com.sanye.strategy.common.model;

public class BasePageDTO<T> extends BasePage<T> {

    public BasePageDTO() {
    }

    public BasePageDTO(long current, long size) {
        super(current, size);
    }

    public BasePageDTO(long current, long size, long total) {
        super(current, size, total);
    }

    // 静态工厂方法，和MP风格一致
    public static <T> BasePageDTO<T> of(long current, long size) {
        return new BasePageDTO<>(current, size);
    }

    public static <T> BasePageDTO<T> of(long current, long size, long total) {
        return new BasePageDTO<>(current, size, total);
    }

    // 业务扩展字段示例
    public boolean isHasMore() {
        return getCurrent() < getPages();
    }

    @Override
    public String toString() {
        return "BasePageDTO{} " + super.toString();
    }
}