package com.sanye.strategy.common.model;
import java.io.Serializable;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public interface IBasePage<T> extends Serializable {

    List<T> getRecords();

    IBasePage<T> setRecords(List<T> records);

    long getTotal();

    IBasePage<T> setTotal(long total);

    long getSize();

    IBasePage<T> setSize(long size);

    long getCurrent();

    IBasePage<T> setCurrent(long current);

    // 默认计算偏移量
    default long offset() {
        long current = getCurrent();
        return current <= 1L ? 0L : Math.max((current - 1L) * getSize(), 0L);
    }

    // 默认计算总页数
    default long getPages() {
        if (getSize() == 0L) {
            return 0L;
        }
        long pages = getTotal() / getSize();
        if (getTotal() % getSize() != 0L) {
            pages++;
        }
        return pages;
    }

    // 类型转换 DO -> VO
    default <R> IBasePage<R> convert(Function<? super T, ? extends R> mapper) {
        List<R> collect = getRecords().stream()
                .map(mapper)
                .collect(Collectors.toList());

        IBasePage<R> newPage = newEmptyPage();
        newPage.setRecords(collect);
        newPage.setTotal(getTotal());
        newPage.setSize(getSize());
        newPage.setCurrent(getCurrent());
        return newPage;
    }

    // 提供空页实例
    default <R> IBasePage<R> newEmptyPage() {
        return new BasePage<>();
    }

    // 是否查询count
    default boolean searchCount() {
        return true;
    }
}
